package com.techinterviewer.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * The remaining strictness settings that have no {@code spring.jackson.*} property: a JSON number or boolean is never
 * accepted for a text property (Jackson would otherwise turn {@code 5} into the string {@code "5"}). The other
 * scalar coercions, unknown properties, enum ordinals, duplicate keys and trailing content are configured in
 * {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
class JsonStrictnessConfig {

    @Bean
    JsonMapperBuilderCustomizer strictTextCoercion() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
