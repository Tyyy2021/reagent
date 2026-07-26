package com.reagent.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared response-body ownership for trusted internal HTTP clients. */
public final class BoundedHttpBody {

    private BoundedHttpBody() {
    }

    public static byte[] send(
            HttpClient client,
            HttpRequest request,
            int maximumBytes,
            Duration completeResponseTimeout
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(completeResponseTimeout, "completeResponseTimeout");
        if (completeResponseTimeout.isZero() || completeResponseTimeout.isNegative()) {
            throw new IllegalArgumentException("completeResponseTimeout must be positive");
        }

        BodyCancellation cancellation = new BodyCancellation();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> inFlight = executor.submit(() -> {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body();
            if (!cancellation.register(body)) {
                throw new InterruptedException("HTTP response was cancelled");
            }
            try {
                return read(response, maximumBytes);
            } finally {
                cancellation.clear(body);
            }
        });
        try {
            return inFlight.get(
                    completeResponseTimeout.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            cancellation.cancel();
            inFlight.cancel(true);
            throw new HttpTimeoutException("HTTP complete response deadline exceeded");
        } catch (InterruptedException interrupted) {
            cancellation.cancel();
            inFlight.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            return rethrow(failure.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    public static byte[] read(HttpResponse<InputStream> response, int maximumBytes)
            throws IOException {
        if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes must allow a one-byte overflow probe");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP response status is not successful");
            }
            long declaredLength =
                    response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maximumBytes) {
                throw new IOException("HTTP response exceeds configured limit");
            }
            byte[] bytes = body.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IOException("HTTP response exceeds configured limit");
            }
            return bytes;
        }
    }

    private static byte[] rethrow(Throwable failure)
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
        throw new IOException("HTTP response failed", failure);
    }

    private static final class BodyCancellation {
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

        private synchronized void clear(InputStream candidate) {
            if (body == candidate) {
                body = null;
            }
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
                    // The complete-response deadline remains the stable public failure.
                }
            }
        }
    }
}
