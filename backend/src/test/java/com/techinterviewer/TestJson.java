package com.techinterviewer;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The application's own {@link JsonMapper}, configured by the real {@code application.yml}, without starting the application. */
public final class TestJson {

    private static final JsonMapper APPLICATION_MAPPER = build();

    private TestJson() {
    }

    /** Same settings as the mapper of the running application (strict on unknown properties, ISO dates). */
    public static JsonMapper applicationMapper() {
        return APPLICATION_MAPPER;
    }

    private static JsonMapper build() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return mapper.get();
    }

    /** JSON equality that treats {@code 78} and {@code 78.0} as the same number. */
    public static boolean equivalent(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.isObject() && actual.isObject()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            for (var entry : expected.properties()) {
                JsonNode other = actual.get(entry.getKey());
                if (other == null || !equivalent(entry.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!equivalent(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }
}
