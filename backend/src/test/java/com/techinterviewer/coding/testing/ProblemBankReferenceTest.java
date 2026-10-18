package com.techinterviewer.coding.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.coding.References;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.problem.CodingProblem;
import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.problem.TestCase;
import com.techinterviewer.coding.problem.ValueType;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every expected output of the problem bank is checked in every build, without Docker: the Java reference solution of each
 * problem is compiled and run in this JVM on every test. (The sandbox test runs the Java, JavaScript and TypeScript
 * references in containers.) An expected value in the bank therefore never rests on a model or on one author's arithmetic.
 */
class ProblemBankReferenceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path classes;

    static Stream<String> problems() {
        return ProblemBank.load().all().stream().map(CodingProblem::id);
    }

    @ParameterizedTest
    @MethodSource("problems")
    void theJavaReferenceSolutionPassesEveryTest(String problemId) throws Exception {
        CodingProblem problem = ProblemBank.load().find(problemId).orElseThrow();
        Path source = classes.resolve("Solution.java");
        Files.writeString(source, References.solution(problemId, CodeLanguage.JAVA), StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, "-d", classes.toString(), source.toString())).isZero();

        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> solution = loader.loadClass("Solution");
            Method method = Stream.of(solution.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(problem.signature().name()))
                    .findFirst().orElseThrow();
            method.setAccessible(true);
            for (TestCase test : problem.tests()) {
                var constructor = solution.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object instance = constructor.newInstance();
                Object[] arguments = new Object[test.arguments().size()];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = toJava(test.arguments().get(i), problem.signature().parameters().get(i).type());
                }
                JsonNode actual = JSON.valueToTree(method.invoke(instance, arguments));
                assertThat(ValueComparison.matches(actual, test.expected(), problem.comparison()))
                        .as("%s %s (%s): got %s, expected %s", problemId, test.id(), test.name(), shorten(actual), shorten(test.expected()))
                        .isTrue();
            }
        }
    }

    private static Object toJava(JsonNode value, ValueType type) {
        return switch (type) {
            case INT -> value.asInt();
            case LONG -> value.asLong();
            case BOOLEAN -> value.asBoolean();
            case STRING -> value.asString();
            case INT_ARRAY -> ints(value);
            case STRING_ARRAY -> {
                List<String> strings = new ArrayList<>();
                value.forEach(element -> strings.add(element.asString()));
                yield strings.toArray(String[]::new);
            }
            case INT_MATRIX -> {
                int[][] matrix = new int[value.size()][];
                for (int i = 0; i < matrix.length; i++) {
                    matrix[i] = ints(value.get(i));
                }
                yield matrix;
            }
        };
    }

    private static int[] ints(JsonNode array) {
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asInt();
        }
        return values;
    }

    private static String shorten(JsonNode value) {
        String text = value.toString();
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
