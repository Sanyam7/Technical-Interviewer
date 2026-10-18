package com.techinterviewer.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter of type {@link AuthenticatedUser} that is filled from the verified access token.
 * It is never read from the request, so a client cannot choose it. On a request that is not authenticated with a
 * valid access token the call fails with {@code 401 UNAUTHENTICATED} (that cannot happen on a path the security
 * configuration protects, it is a second line of defence).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
