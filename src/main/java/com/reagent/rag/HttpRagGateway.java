package com.reagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.reagent.obs.Trace;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Bounded HTTP implementation of the fixed internal RAG contract. */
@Component
public class HttpRagGateway implements RagGateway {

    private static final String SEARCH_PATH = "/internal/rag/search";

    private final RagProperties properties;
    private final URI baseUrl;
    private final HttpClient client;
    private final ObjectWriter requestWriter;
    private final ObjectReader searchReader;
    private final ObjectReader activeReader;
    private final Tracer tracer;

    @Autowired
    public HttpRagGateway(
            RagProperties properties,
            ObjectMapper applicationMapper,
            Tracer tracer
    ) {
        this.properties = properties;
        this.baseUrl = properties.requireTrustedBaseUrl();
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        ObjectMapper writerMapper = applicationMapper.copy();
        this.requestWriter = writerMapper.writerFor(RagSearchRequest.class);
        this.searchReader = RagSearchResponse.reader(applicationMapper);
        this.activeReader = ActiveIndexResponse.reader(applicationMapper);
        this.tracer = tracer;
    }

    public HttpRagGateway(RagProperties properties, ObjectMapper applicationMapper) {
        this(properties, applicationMapper,
                OpenTelemetry.noop().getTracer(Trace.INSTRUMENTATION_NAME));
    }

    @Override
    public String requireActiveVersion(String knowledgeBaseId) {
        return inHttpSpan(() -> requireActiveVersionTraced(knowledgeBaseId));
    }

    private String requireActiveVersionTraced(String knowledgeBaseId) {
        RagContract.requireCodePoints(knowledgeBaseId, 1, 64, "knowledgeBaseId");
        String path = "/internal/rag/indexes/" + encodePathSegment(knowledgeBaseId) + "/active";
        HttpRequest request = requestBuilder(endpoint(path)).GET().build();
        ActiveIndexResponse response = exchange(request, activeReader, ActiveIndexResponse.class,
                value -> {
                    if (!knowledgeBaseId.equals(value.knowledgeBaseId()) || !value.ready()) {
                        throw new RagContractException(
                                "RAG_RESPONSE_MISMATCH",
                                "active index response does not match the request");
                    }
                });
        return response.indexVersion();
    }

    @Override
    public RagSearchResponse search(RagSearchRequest request) {
        return inHttpSpan(() -> searchTraced(request));
    }

    private RagSearchResponse searchTraced(RagSearchRequest request) {
        byte[] requestBody;
        try {
            requestBody = requestWriter.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new RagContractException("RAG_REQUEST_INVALID", "RAG request could not be serialized");
        }
        HttpRequest httpRequest = requestBuilder(endpoint(SEARCH_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        return exchange(httpRequest, searchReader, RagSearchResponse.class,
                response -> {
                    try {
                        response.validateFor(request);
                    } catch (IllegalArgumentException exception) {
                        throw new RagContractException(
                                "RAG_RESPONSE_MISMATCH",
                                "RAG search response does not match the request");
                    }
                });
    }

    private <T> T inHttpSpan(java.util.function.Supplier<T> operation) {
        Span span = tracer.spanBuilder("rag.http").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return operation.get();
        } finally {
            span.end();
        }
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json");
        Trace.currentW3cHeaders().forEach(builder::header);
        return builder;
    }

    private <T> T exchange(HttpRequest request, ObjectReader reader, Class<T> responseType,
                           Consumer<T> validator) {
        for (int attempt = 0; attempt <= properties.getRetries(); attempt++) {
            try {
                return completeAttempt(request, reader, responseType, validator);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RagContractException("RAG_INTERRUPTED", "RAG request was interrupted");
            } catch (IOException ioFailure) {
                if (attempt >= properties.getRetries()) {
                    throw new RagContractException("RAG_IO", "RAG service I/O failed after one retry");
                }
                backoff();
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private <T> T completeAttempt(HttpRequest request, ObjectReader reader, Class<T> responseType,
                                  Consumer<T> validator) throws IOException, InterruptedException {
        AttemptCancellation cancellation = new AttemptCancellation();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> inFlight = executor.submit(() -> {
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                if (!cancellation.register(response.body())) {
                    throw new InterruptedException("RAG request was cancelled");
                }
                T value = readResponse(response, reader, responseType);
                validator.accept(value);
                return value;
            });
            try {
                return inFlight.get(properties.getRequestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                cancellation.cancel();
                inFlight.cancel(true);
                throw new HttpTimeoutException("RAG complete response deadline exceeded");
            } catch (InterruptedException interrupted) {
                cancellation.cancel();
                inFlight.cancel(true);
                throw interrupted;
            } catch (ExecutionException failure) {
                return rethrowAttemptFailure(failure.getCause());
            }
        }
    }

    private static <T> T rethrowAttemptFailure(Throwable failure)
            throws IOException, InterruptedException {
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("RAG request failed", failure);
    }

    private <T> T readResponse(HttpResponse<InputStream> response, ObjectReader reader,
                               Class<T> responseType) throws IOException {
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new RagContractException(
                        "RAG_HTTP_STATUS", "RAG service returned HTTP status " + status);
            }
            byte[] bytes = readBounded(body, response);
            try {
                return responseType.cast(reader.readValue(bytes));
            } catch (IOException | RuntimeException invalidBody) {
                throw new RagContractException("RAG_RESPONSE_INVALID", "RAG response contract is invalid");
            }
        }
    }

    private byte[] readBounded(InputStream body, HttpResponse<?> response) throws IOException {
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        int maximum = properties.getMaximumResponseBytes();
        if (declaredLength > maximum) {
            throw new RagContractException("RAG_RESPONSE_TOO_LARGE", "RAG response exceeds 64 KiB limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8_192));
        byte[] buffer = new byte[Math.min(maximum + 1, 8_192)];
        int total = 0;
        while (true) {
            int allowed = Math.min(buffer.length, maximum - total + 1);
            int read = body.read(buffer, 0, allowed);
            if (read < 0) {
                return output.toByteArray();
            }
            total += read;
            if (total > maximum) {
                throw new RagContractException("RAG_RESPONSE_TOO_LARGE", "RAG response exceeds 64 KiB limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private void backoff() {
        Duration delay = properties.getRetryBackoff();
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RagContractException("RAG_INTERRUPTED", "RAG retry backoff was interrupted");
        }
    }

    private URI endpoint(String rawSuffix) {
        String basePath = baseUrl.getRawPath();
        if (basePath == null || "/".equals(basePath)) {
            basePath = "";
        } else if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        return URI.create(baseUrl.getScheme() + "://" + baseUrl.getRawAuthority() + basePath + rawSuffix);
    }

    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int unsigned = Byte.toUnsignedInt(item);
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '.' || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(HexFormat.of().withUpperCase().toHexDigits(item));
            }
        }
        return encoded.toString();
    }

    private static final class AttemptCancellation {
        private InputStream body;
        private boolean cancelled;

        private boolean register(InputStream candidate) throws IOException {
            synchronized (this) {
                if (!cancelled) {
                    body = candidate;
                    return true;
                }
            }
            candidate.close();
            return false;
        }

        private void cancel() {
            InputStream current;
            synchronized (this) {
                cancelled = true;
                current = body;
                body = null;
            }
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Cancellation is already in progress; the bounded public error is fixed.
                }
            }
        }
    }
}
