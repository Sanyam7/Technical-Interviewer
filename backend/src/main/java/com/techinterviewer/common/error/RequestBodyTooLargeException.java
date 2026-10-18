package com.techinterviewer.common.error;

import java.io.IOException;

/**
 * Thrown while a request body is being read once it has grown past the configured limit (a body with a declared
 * length is refused up front, a chunked one is cut off here). The exception handler answers it with 413.
 */
public class RequestBodyTooLargeException extends IOException {

    private final long limitBytes;

    public RequestBodyTooLargeException(long limitBytes) {
        super("The request body is larger than " + limitBytes + " bytes.");
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
