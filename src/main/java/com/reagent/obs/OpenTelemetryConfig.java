package com.reagent.obs;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M6:OpenTelemetry 手动装配 —— 自建 {@code TracerProvider} + OTLP 导出(到 Jaeger),<b>不上 starter</b>。
 *
 * <p>为什么手动:要的是业务 span(task/step/llm/tool/sandbox),不是底层 HTTP span;手动可控、依赖清晰、
 * 能讲清自己搭的 provider / exporter / propagator。</p>
 *
 * <ul>
 *   <li>{@code reagent.tracing.enabled=false} → 返回 {@link OpenTelemetry#noop()}:零侵入,不连 Jaeger 也能跑 demo;</li>
 *   <li>{@link BatchSpanProcessor} 异步导出,Jaeger 没起也只 warn、不阻塞 / 不拖垮主流程;</li>
 *   <li>{@link W3CTraceContextPropagator} 单体暂用不上,为 M7 跨 worker 传 trace 留缝;</li>
 *   <li>返回的 {@link OpenTelemetrySdk} 实现 {@code Closeable},容器关闭时 Spring 自动 close → flush 余量 span。</li>
 * </ul>
 */
@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${reagent.tracing.enabled:true}") boolean enabled,
            @Value("${reagent.tracing.otlp-endpoint:http://localhost:4318/v1/traces}") String endpoint,
            @Value("${reagent.tracing.service-name:reagent}") String serviceName) {

        if (!enabled) {
            log.info("tracing 已关闭(reagent.tracing.enabled=false),使用 no-op OpenTelemetry。");
            return OpenTelemetry.noop();
        }

        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), serviceName)
                .build();

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        log.info("tracing 已启用,OTLP 导出 -> {}(service.name={})", endpoint, serviceName);
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(Trace.INSTRUMENTATION_NAME);
    }
}
