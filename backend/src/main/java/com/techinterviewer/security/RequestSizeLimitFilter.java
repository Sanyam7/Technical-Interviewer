package com.techinterviewer.security;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.RequestBodyTooLargeException;
import com.techinterviewer.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses oversized request bodies before they are read, so one anonymous client cannot exhaust the heap with a
 * giant JSON document. A body that declares its length is answered with 413 immediately; a chunked body (no length)
 * is counted while it is read and cut off with {@link RequestBodyTooLargeException}, which the exception handler also
 * turns into 413. The public {@code /api/v1/auth/*} endpoints get a much smaller limit than the rest, the résumé upload
 * ({@code PUT /api/v1/profile/resume}) a larger one.
 *
 * <p>It runs right after the correlation id filter and before Spring Security, so the refusal costs nothing else
 * (see {@link RequestSizeLimitConfig}). It is deliberately not a {@code @Component}: a filter bean would be pulled
 * into every web test slice together with the beans it needs.
 */
class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PREFIX = ApiPaths.BASE + "/auth/";
    static final String RESUME_PATH = ApiPaths.BASE + "/profile/resume";

    private final long maxBodyBytes;
    private final long authMaxBodyBytes;
    private final long resumeMaxBodyBytes;
    private final ProblemResponseWriter problems;

    RequestSizeLimitFilter(AppProperties properties, ProblemResponseWriter problems) {
        this.maxBodyBytes = properties.limits().maxBodyBytes();
        this.authMaxBodyBytes = properties.limits().authMaxBodyBytes();
        this.resumeMaxBodyBytes = properties.limits().resumeMaxBodyBytes();
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        long limit = uri.startsWith(AUTH_PREFIX) ? authMaxBodyBytes
                : uri.equals(RESUME_PATH) && "PUT".equals(request.getMethod()) ? resumeMaxBodyBytes
                : maxBodyBytes;
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            problems.write(request, response, new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "The request body may be at most " + limit + " bytes."));
            return;
        }
        chain.doFilter(declared >= 0 ? request : new CountingRequest(request, limit), response);
    }

    /** A request whose body stream fails once more than {@code limit} bytes have been read. */
    private static final class CountingRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        CountingRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called for this request");
            }
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        CountingStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
