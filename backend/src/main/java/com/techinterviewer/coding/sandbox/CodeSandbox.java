package com.techinterviewer.coding.sandbox;

/**
 * Runs untrusted code in isolation. The only implementation runs a throw-away Docker container per job; a remote runner
 * service (separate hosts, gVisor or Firecracker) would implement the same interface.
 */
public interface CodeSandbox {

    /** Whether execution is enabled at all. A failure at run time is still possible and reported as unavailable. */
    boolean enabled();

    /**
     * @throws SandboxUnavailableException when the job cannot be run (disabled, Docker unreachable, image missing, busy)
     */
    SandboxResult run(SandboxJob job);

    /** The sandbox when execution is off. */
    CodeSandbox DISABLED = new CodeSandbox() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public SandboxResult run(SandboxJob job) {
            throw new SandboxUnavailableException("Code execution is turned off on this server.", false);
        }
    };
}
