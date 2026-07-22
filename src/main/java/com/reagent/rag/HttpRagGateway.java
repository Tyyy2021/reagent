package com.reagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.reagent.obs.Trace;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

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

    public HttpRagGateway(RagProperties properties, ObjectMapper applicationMapper) {
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
    }

    @Override
    public String requireActiveVersion(String knowledgeBaseId) {
        RagContract.requireCodePoints(knowledgeBaseId, 1, 64, "knowledgeBaseId");
        String path = "/internal/rag/indexes/" + encodePathSegment(knowledgeBaseId) + "/active";
        HttpRequest request = requestBuilder(endpoint(path)).GET().build();
        ActiveIndexResponse response = exchange(request, activeReader, ActiveIndexResponse.class);
        if (!knowledgeBaseId.equals(response.knowledgeBaseId()) || !response.ready()) {
            throw new RagContractException(
                    "RAG_RESPONSE_MISMATCH", "active index response does not match the request");
        }
        return response.indexVersion();
    }

    @Override
    public RagSearchResponse search(RagSearchRequest request) {
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
        RagSearchResponse response = exchange(httpRequest, searchReader, RagSearchResponse.class);
        try {
            response.validateFor(request);
        } catch (IllegalArgumentException exception) {
            throw new RagContractException(
                    "RAG_RESPONSE_MISMATCH", "RAG search response does not match the request");
        }
        return response;
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json");
        Trace.currentW3cHeaders().forEach(builder::header);
        return builder;
    }

    private <T> T exchange(HttpRequest request, ObjectReader reader, Class<T> responseType) {
        for (int attempt = 0; attempt <= properties.getRetries(); attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                return readResponse(response, reader, responseType);
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
}
