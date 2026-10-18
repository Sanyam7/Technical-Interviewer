package com.techinterviewer.coding.sandbox;

/** The sandbox cannot run code now: disabled, Docker unreachable, an image missing, or every slot busy. */
public class SandboxUnavailableException extends RuntimeException {

    private final boolean busy;

    public SandboxUnavailableException(String message, boolean busy) {
        super(message);
        this.busy = busy;
    }

    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.busy = false;
    }

    /** True when it is only busy: trying again shortly can work. */
    public boolean busy() {
        return busy;
    }
}
