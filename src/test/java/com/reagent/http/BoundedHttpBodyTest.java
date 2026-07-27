package com.reagent.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedHttpBodyTest {

    @Test
    void missingContentLengthIsAcceptedWithinTheStreamLimitAndClosed() throws Exception {
        TrackingInputStream body =
                new TrackingInputStream("safe".getBytes(StandardCharsets.UTF_8));

        byte[] actual = BoundedHttpBody.read(response(body, Map.of()), 4);

        assertArrayEquals("safe".getBytes(StandardCharsets.UTF_8), actual);
        assertEquals(4, body.bytesRead());
        assertTrue(body.closed());
    }

    @Test
    void oversizedDeclaredLengthIsRejectedBeforeReadingAndClosed() {
        TrackingInputStream body = new TrackingInputStream(new byte[]{1});

        assertThrows(IOException.class, () -> BoundedHttpBody.read(
                response(body, Map.of("Content-Length", List.of("5"))), 4));

        assertEquals(0, body.bytesRead());
        assertTrue(body.closed());
    }

    @Test
    void streamedBodyReadsAtMostMaximumPlusOneAndCloses() {
        TrackingInputStream body = new TrackingInputStream(new byte[100]);

        assertThrows(IOException.class,
                () -> BoundedHttpBody.read(response(200, body, Map.of()), 4));

        assertEquals(5, body.bytesRead());
        assertTrue(body.closed());
    }

    @Test
    void nonSuccessStatusIsRejectedWithoutReadingAndClosed() {
        TrackingInputStream body = new TrackingInputStream(new byte[100]);

        assertThrows(IOException.class,
                () -> BoundedHttpBody.read(response(503, body, Map.of()), 4));

        assertEquals(0, body.bytesRead());
        assertTrue(body.closed());
    }

    private static HttpResponse<InputStream> response(
            InputStream body,
            Map<String, List<String>> headers
    ) {
        return response(200, body, headers);
    }

    private static HttpResponse<InputStream> response(
            int status,
            InputStream body,
            Map<String, List<String>> headers
    ) {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    private static final class TrackingInputStream extends InputStream {
        private final byte[] bytes;
        private int position;
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            if (position >= bytes.length) {
                return -1;
            }
            return bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (position >= bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, destination, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }

        private int bytesRead() {
            return position;
        }

        private boolean closed() {
            return closed;
        }
    }
}
