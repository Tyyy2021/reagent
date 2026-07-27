package com.reagent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Trusted, bounded connection settings for the internal RAG service. */
@ConfigurationProperties(prefix = "reagent.rag")
public class RagProperties {

    private URI baseUrl = URI.create("http://localhost:8090");
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration requestTimeout = Duration.ofSeconds(2);
    private int maximumResponseBytes = 65_536;
    private int retries = 1;
    private Duration retryBackoff = Duration.ofMillis(100);

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaximumResponseBytes() {
        return maximumResponseBytes;
    }

    public void setMaximumResponseBytes(int maximumResponseBytes) {
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    URI requireTrustedBaseUrl() {
        URI candidate = Objects.requireNonNull(baseUrl, "reagent.rag.base-url");
        String scheme = candidate.getScheme();
        if (!candidate.isAbsolute()
                || scheme == null
                || !("http".equals(scheme.toLowerCase(Locale.ROOT))
                || "https".equals(scheme.toLowerCase(Locale.ROOT)))
                || candidate.getHost() == null
                || candidate.getHost().isBlank()
                || candidate.getUserInfo() != null
                || candidate.getRawQuery() != null
                || candidate.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "reagent.rag.base-url must be an absolute trusted HTTP(S) URI without user info, query, or fragment");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        if (maximumResponseBytes < 1 || maximumResponseBytes > 65_536) {
            throw new IllegalArgumentException("reagent.rag.maximum-response-bytes must be between 1 and 65536");
        }
        if (retries != 1) {
            throw new IllegalArgumentException("reagent.rag.retries must be exactly 1");
        }
        Objects.requireNonNull(retryBackoff, "reagent.rag.retry-backoff");
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("reagent.rag.retry-backoff must not be negative");
        }
        return candidate;
    }

    private static void requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, "reagent.rag." + field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("reagent.rag." + field + " must be positive");
        }
    }
}
