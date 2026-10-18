package com.techinterviewer.security;

import java.security.SecureRandom;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfig {

    /** BCrypt work factor: about a quarter of a second per hash on current server hardware. */
    static final int BCRYPT_COST = 12;

    /**
     * New passwords are hashed with BCrypt and stored with the {@code {bcrypt}} prefix. Going through the
     * delegating encoder means a stronger algorithm can be introduced later while existing hashes keep verifying.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_COST)));
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
