package com.techinterviewer.coding.testing;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads what the harness printed. Only lines carrying the run's nonce are harness lines; everything else is the
 * candidate's own output (debug prints), kept apart. The first report of a test wins: the code under test shares the
 * process with the harness, but expected values never enter it, so a forged line can only claim what the function could
 * have returned anyway.
 *
 * @param compileError the compiler's or loader's message, when the code did not compile
 * @param results      per test index: status, time and the JSON payload
 * @param finished     the harness reached its end
 */
record HarnessOutput(@Nullable String compileError, Map<Integer, Raw> results, boolean finished, String candidateOutput) {

    /** One reported test: OK (payload is the returned value as JSON), ERROR (payload is a JSON string) or TIMEOUT. */
    record Raw(String status, long millis, String payload) {
    }

    static HarnessOutput parse(String stdout, String nonce) {
        String marker = nonce + " ";
        Map<Integer, Raw> results = new HashMap<>();
        StringBuilder candidate = new StringBuilder();
        StringBuilder compile = null;
        boolean finished = false;
        for (String line : stdout.split("\n", -1)) {
            int at = line.indexOf(marker);
            if (at < 0) {
                if (compile != null) {
                    compile.append(line).append('\n');
                } else if (!line.isEmpty()) {
                    candidate.append(line).append('\n');
                }
                continue;
            }
            if (at > 0) {
                candidate.append(line, 0, at).append('\n');
            }
            String[] parts = line.substring(at + marker.length()).split(" ", 5);
            switch (parts[0]) {
                case "COMPILE_ERROR" -> compile = new StringBuilder();
                case "DONE" -> finished = true;
                case "RESULT" -> {
                    if (parts.length == 5) {
                        try {
                            int index = Integer.parseInt(parts[1]);
                            results.putIfAbsent(index, new Raw(parts[2], Long.parseLong(parts[3]), parts[4]));
                        } catch (NumberFormatException malformed) {
                            // a forged or broken line: ignored
                        }
                    }
                }
                default -> {
                    // unknown keyword: ignored
                }
            }
        }
        return new HarnessOutput(compile == null ? null : compile.toString().strip(), Map.copyOf(results), finished,
                candidate.toString().strip());
    }
}
