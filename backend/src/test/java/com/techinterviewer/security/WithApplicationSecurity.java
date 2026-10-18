package com.techinterviewer.security;

import com.techinterviewer.TestClock;
import com.techinterviewer.config.AppProperties;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Puts the real security of the application (filter chain, JWT decoder, problem handlers, {@link CurrentUser}
 * resolver) into a {@code @WebMvcTest} slice, which does not scan configuration classes. The slice needs no database:
 *
 * <pre>
 * &#64;WebMvcTest(InterviewController.class)
 * &#64;WithApplicationSecurity
 * class InterviewControllerTest {
 *     &#64;Autowired MockMvc mockMvc;
 *     &#64;Autowired TestClock clock;      // the Clock of the context: advance it to expire tokens
 *     &#64;Autowired ApplicationContext context;
 *     // TestAuth.forContext(context).asUser(userId, Role.CANDIDATE), or TestAuth.bearer(userId, Role.CANDIDATE)
 * }
 * </pre>
 *
 * The slice has a {@link TestClock} as its {@code Clock} bean and the test signing secret of
 * {@code src/test/resources/application.properties}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({SecurityConfig.class, JwtConfig.class, ProblemDetailAuthenticationEntryPoint.class,
    ProblemDetailAccessDeniedHandler.class, ProblemResponseWriter.class, RateLimiters.class, CurrentUserWebConfig.class,
    WithApplicationSecurity.SliceBeans.class})
public @interface WithApplicationSecurity {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    class SliceBeans {

        @Bean
        TestClock clock() {
            return new TestClock();
        }
    }
}
