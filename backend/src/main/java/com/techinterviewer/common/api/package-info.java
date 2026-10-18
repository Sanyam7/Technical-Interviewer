/**
 * API response conventions.
 *
 * <ul>
 *   <li>Successful responses are plain DTO records. There is no {@code { data, error }} envelope.
 *   <li>Lists that can grow are returned as {@link com.techinterviewer.common.api.PageResponse}.
 *   <li>Every error is an RFC 9457 {@code application/problem+json} document (see
 *       {@code com.techinterviewer.common.error}). The only exception is the health endpoint, which
 *       returns its status document for both 200 and 503.
 *   <li>Timestamps are ISO-8601 UTC instants. Unknown JSON properties in requests are rejected.
 * </ul>
 */
package com.techinterviewer.common.api;
