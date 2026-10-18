package com.techinterviewer.coding.testing;

import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.Signature;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.coding.problem.ValueType;
import com.techinterviewer.coding.sandbox.SandboxJob;
import com.techinterviewer.coding.sandbox.SandboxJob.SandboxFile;
import com.techinterviewer.coding.sandbox.SandboxProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the container job for a solution: the candidate's file, the harness of its language and the test inputs. The
 * expected outputs are deliberately not in it.
 */
public final class Harnesses {

    static final String JAVA_SCRIPT = """
            set -e
            mkdir -p /tmp/w && cd /tmp/w && tar -xf -
            if ! javac -J-Xmx256m -J-XX:-UsePerfData -encoding UTF-8 -nowarn -d out Solution.java Main.java > javac.txt 2>&1; then
              echo "$1 COMPILE_ERROR"; head -c 4000 javac.txt; exit 0
            fi
            exec java -Xmx256m -Xss8m -XX:-UsePerfData -XX:+UseSerialGC -cp out Main "$1"
            """;

    static final String NODE_SCRIPT = """
            set -e
            mkdir -p /tmp/w && cd /tmp/w && tar -xf -
            exec node --max-old-space-size=256 --disable-warning=ExperimentalWarning harness.js "$1"
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String javaTemplate;
    private final String nodeTemplate;
    private final SandboxProperties.Images images;

    public Harnesses(SandboxProperties.Images images) {
        this.javaTemplate = read("coding-harness/Main.java.template");
        this.nodeTemplate = read("coding-harness/harness.js");
        this.images = images;
    }

    public SandboxJob job(CodingProblem problem, CodeLanguage language, String code, List<TestCase> tests, String nonce) {
        byte[] input = input(tests);
        return switch (language) {
            case JAVA -> new SandboxJob(images.java(), JAVA_SCRIPT, List.of(
                    file("Solution.java", code),
                    file("Main.java", javaHarness(problem)),
                    new SandboxFile("input.json", input)), nonce);
            case JAVASCRIPT, TYPESCRIPT -> new SandboxJob(images.node(), NODE_SCRIPT, List.of(
                    file(language == CodeLanguage.TYPESCRIPT ? "solution.ts" : "solution.js", code),
                    file("harness.js", nodeHarness(problem, language)),
                    new SandboxFile("input.json", input)), nonce);
            case PYTHON, GO -> throw new IllegalArgumentException(language.code() + " has no test harness");
        };
    }

    String javaHarness(CodingProblem problem) {
        return javaTemplate
                .replace("__TIME_LIMIT_MS__", Integer.toString(problem.timeLimitMs()))
                .replace("__CALL__", javaCall(problem.signature()));
    }

    String nodeHarness(CodingProblem problem, CodeLanguage language) {
        return nodeTemplate
                .replace("__FUNCTION__", problem.signature().name())
                .replace("__LANGUAGE__", language.code())
                .replace("__TIME_LIMIT_MS__", Integer.toString(problem.timeLimitMs()));
    }

    /** {@code return new Solution().twoSum(toIntArray(a.get(0)), toInt(a.get(1)));} */
    static String javaCall(Signature signature) {
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < signature.parameters().size(); i++) {
            arguments.add(converter(signature.parameters().get(i).type()) + "(a.get(" + i + "))");
        }
        return "return new Solution()." + signature.name() + "(" + String.join(", ", arguments) + ");";
    }

    private static String converter(ValueType type) {
        return switch (type) {
            case INT -> "toInt";
            case LONG -> "toLong";
            case BOOLEAN -> "toBoolean";
            case STRING -> "toStr";
            case INT_ARRAY -> "toIntArray";
            case STRING_ARRAY -> "toStringArray";
            case INT_MATRIX -> "toIntMatrix";
        };
    }

    /** {@code [{"args": [...]}, ...]}: the inputs only. */
    static byte[] input(List<TestCase> tests) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (TestCase test : tests) {
            ObjectNode entry = array.addObject();
            ArrayNode arguments = entry.putArray("args");
            test.arguments().forEach(arguments::add);
        }
        return JSON.writeValueAsBytes(array);
    }

    private static SandboxFile file(String name, String text) {
        return new SandboxFile(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String location) {
        try {
            return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException("cannot read the test harness " + location, missing);
        }
    }
}
