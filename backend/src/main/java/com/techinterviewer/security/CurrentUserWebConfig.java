package com.techinterviewer.security;

import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link CurrentUser} argument resolver. Public so that a {@code @WebMvcTest} slice, which does not
 * scan configuration classes, can {@code @Import} it.
 */
@Configuration(proxyBeanMethods = false)
public class CurrentUserWebConfig implements WebMvcConfigurer {

    static {
        // The parameter is filled by the server and must not show up as an input of the API documentation.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
