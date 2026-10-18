package com.techinterviewer.security;

import com.techinterviewer.config.AppProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Wires the token infrastructure: signing key, issuer and the decoder the resource server uses. */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    /**
     * Static, so it is created while the bean post-processors are registered, before any other bean (the database
     * migrations included): a missing secret must stop the application at once, not after it has already changed
     * the database. It reads the properties from the environment because the properties bean does not exist yet.
     */
    @Bean
    static BeanPostProcessor jwtSecretValidator(Environment environment) {
        JwtKeyProvider.validate(Binder.get(environment).bindOrCreate("app.security.jwt", AppProperties.Jwt.class));
        return new BeanPostProcessor() {
        };
    }

    /** Fails the startup (see {@link MissingJwtSecretFailureAnalyzer}) when there is no usable secret. */
    @Bean
    JwtKeyProvider jwtKeyProvider(AppProperties properties) {
        return JwtKeyProvider.from(properties.security().jwt());
    }

    @Bean
    JwtService jwtService(JwtKeyProvider keys, AppProperties properties, Clock clock) {
        return new JwtService(keys, properties, clock);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyProvider keys, AppProperties properties, Clock clock) {
        return AccessTokenDecoder.create(keys.signingKey(), properties.security().jwt().issuer(), clock);
    }
}
