package com.techinterviewer.security;

import com.techinterviewer.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers the {@link RequestSizeLimitFilter} with the servlet container, right after the correlation id filter. */
@Configuration(proxyBeanMethods = false)
class RequestSizeLimitConfig {

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(AppProperties properties, ProblemResponseWriter problems) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(properties, problems));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
