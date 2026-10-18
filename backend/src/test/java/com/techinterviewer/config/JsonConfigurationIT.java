package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JsonConfigurationIT {

    record Sample(String name, Instant at) {
    }

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    RequestMappingHandlerAdapter handlerAdapter;

    @Test
    void requestsWithUnknownPropertiesAreRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"name\":\"a\",\"extra\":1}", Sample.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void springMvcUsesTheConfiguredJsonMapper() {
        assertThat(handlerAdapter.getMessageConverters())
                .filteredOn(JacksonJsonHttpMessageConverter.class::isInstance)
                .map(JacksonJsonHttpMessageConverter.class::cast)
                .isNotEmpty()
                .allSatisfy(converter -> assertThat(converter.getMapper()).isSameAs(jsonMapper));
    }

    @Test
    void instantsAreWrittenAsIso8601Strings() {
        String json = jsonMapper.writeValueAsString(new Sample("a", Instant.parse("2026-10-05T14:03:00Z")));

        assertThat(json).contains("\"at\":\"2026-10-05T14:03:00Z\"");
    }

    enum Level {
        EASY, HARD
    }

    record Typed(int count, boolean flag, Level level, String text) {
    }

    private Typed read(String json) {
        return jsonMapper.readValue(json, Typed.class);
    }

    @Test
    void wellTypedRequestsAreStillAccepted() {
        assertThat(read("{\"count\":45,\"flag\":true,\"level\":\"HARD\",\"text\":\"x\"}"))
                .isEqualTo(new Typed(45, true, Level.HARD, "x"));
    }

    @Test
    void valuesOfTheWrongTypeAreRejectedInsteadOfCoerced() {
        java.util.List<String> accepted = new java.util.ArrayList<>();
        for (String json : new String[] {
            "{\"count\":\"45\",\"flag\":true,\"level\":\"HARD\",\"text\":\"x\"}",     // a string for a number
            "{\"count\":45.5,\"flag\":true,\"level\":\"HARD\",\"text\":\"x\"}",         // a fraction for an integer
            "{\"count\":45,\"flag\":\"true\",\"level\":\"HARD\",\"text\":\"x\"}",     // a string for a boolean
            "{\"count\":45,\"flag\":1,\"level\":\"HARD\",\"text\":\"x\"}",            // a number for a boolean
            "{\"count\":45,\"flag\":true,\"level\":1,\"text\":\"x\"}",                 // an enum ordinal
            "{\"count\":45,\"flag\":true,\"level\":\"hard\",\"text\":\"x\"}",         // an enum in the wrong case
            "{\"count\":45,\"flag\":true,\"level\":\"HARD\",\"text\":5}",             // a number for a string
        }) {
            try {
                read(json);
                accepted.add(json);
            } catch (tools.jackson.core.JacksonException expected) {
                // rejected, as intended
            }
        }

        assertThat(accepted).as("wrongly typed requests that were accepted").isEmpty();
    }

    @Test
    void duplicateKeysAndTrailingContentAreRejected() {
        assertThatThrownBy(() -> read("{\"count\":1,\"count\":2,\"flag\":true,\"level\":\"HARD\",\"text\":\"x\"}"))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(() -> read("{\"count\":1,\"flag\":true,\"level\":\"HARD\",\"text\":\"x\"} {\"extra\":1}"))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
