package com.reagent.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.context.properties.ConfigurationPropertiesReportEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.env.EnvironmentEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.management.HeapDumpWebEndpointAutoConfiguration;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.actuate.management.HeapDumpWebEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorEndpointRegistrationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("management.endpoints.web.exposure.include=*")
            .withConfiguration(AutoConfigurations.of(
                    EndpointAutoConfiguration.class,
                    EnvironmentEndpointAutoConfiguration.class,
                    ConfigurationPropertiesReportEndpointAutoConfiguration.class,
                    HeapDumpWebEndpointAutoConfiguration.class));

    @Test
    void sensitiveActuatorEndpointsAreNotRegistered() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(EnvironmentEndpoint.class);
            assertThat(context).doesNotHaveBean(ConfigurationPropertiesReportEndpoint.class);
            assertThat(context).doesNotHaveBean(HeapDumpWebEndpoint.class);
        });
    }
}
