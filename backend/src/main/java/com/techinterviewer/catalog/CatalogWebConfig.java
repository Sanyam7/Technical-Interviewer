package com.techinterviewer.catalog;

import com.techinterviewer.common.api.ApiPaths;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Adds an {@code ETag} to the catalog answers and answers a matching {@code If-None-Match} with 304, so a client
 * whose cache expired re-validates for the price of a header instead of the whole document.
 */
@Configuration(proxyBeanMethods = false)
class CatalogWebConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> catalogEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns(ApiPaths.BASE + "/catalog/*");
        registration.setName("catalogEtagFilter");
        return registration;
    }
}
