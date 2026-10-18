package com.techinterviewer.security;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.common.logging.CorrelationIdFilter;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.Role;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.Header;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless, deny-by-default HTTP security with JWT bearer authentication.
 *
 * <ul>
 *   <li>Public: the health and documentation reads, {@code POST /api/v1/auth/{register,login,refresh,logout}}
 *       and {@code GET/HEAD /api/v1/catalog/**}.
 *   <li>{@code /api/v1/admin/**} needs {@code ROLE_ADMIN}.
 *   <li>Every other {@code /api/v1/**} request needs a valid access token.
 *   <li>Everything else is denied.
 * </ul>
 *
 * <p>Request limits (see {@link RateLimiters}) sit in the chain: the limit of the public auth endpoints before
 * authentication, the per-user limit of answer submissions right after it.
 *
 * <p>The access token is read from the {@code Authorization} header only: never from a query parameter, a form
 * body or a cookie. A token sent to a public path is ignored there (so an expired token never blocks
 * {@code /auth/refresh} or the public catalog).
 *
 * <p>CSRF protection is off because the API is authenticated with a bearer token sent in a header,
 * which a cross-site form cannot set. Endpoints that use a cookie (the refresh endpoints) add their own protection.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /**
     * What a browser is told about every API response (SECURITY.md, WEB-1). The API returns JSON and nothing a page should load, so
     * the policy forbids everything: if a response were ever rendered as a document, no script, style, image, frame or form could
     * run from it, and the API cannot be framed. The permissions policy switches off the browser features the API has no use for.
     * Only {@code /api/**}: the API documentation of the local profile is a page and needs its scripts.
     */
    static final HeaderWriter API_HEADERS = new StaticHeadersWriter(List.of(
            new Header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"),
            new Header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()"),
            new Header("Cross-Origin-Resource-Policy", "same-origin"),
            new Header("Cross-Origin-Opener-Policy", "same-origin")));

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            RateLimiters rateLimiters)
            throws Exception {
        BearerTokenResolver headerOnly = new DefaultBearerTokenResolver();
        RequestMatcher publicEndpoints = PublicEndpoints.requestMatcher();
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                PathPatternRequestMatcher.withDefaults().matcher(ApiPaths.BASE + "/**"), API_HEADERS)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(request -> publicEndpoints.matches(request) ? null : headerOnly.resolve(request))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new AccessTokenAuthenticationConverter())))
                .addFilterBefore(rateLimiters.authFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimiters.answersFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimiters.writesFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimiters.codeRunsFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimiters.resumeUploadsFilter(), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(publicEndpoints).permitAll()
                        .requestMatchers(ApiPaths.BASE + "/admin/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(ApiPaths.BASE + "/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "If-None-Match", "X-Requested-With", CorrelationIdFilter.HEADER));
        cors.setExposedHeaders(List.of(CorrelationIdFilter.HEADER, "Retry-After", "Location", "ETag", "WWW-Authenticate"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
