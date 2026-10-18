package com.techinterviewer.ai.support;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test double of a model behind the real Ollama protocol ({@code POST /api/chat}) and the real Gemini protocol
 * ({@code POST /v1beta/models/{model}:generateContent}): it answers every request with a JSON document generated from the
 * request's own schema (Ollama's {@code format}, Gemini's {@code responseFormat.text.schema}), so the whole AI layer
 * (provider, structured output, agents) runs for real without a model. {@link #fail} makes the next generation calls fail
 * the way a real provider does (a timeout, a rate limit, malformed output, a server error). The answers are valid and
 * plausible but carry no meaning:
 * <ul>
 *   <li>enums take their first allowed value, so pinned topics, modes, key point ids and references are honoured;
 *   <li>items of an array whose objects have an enum take the enum values in turn, so every key point is judged once;
 *   <li>an array is as long as its bounds allow; Gemini is sent no bounds, so there the length is what the prompt asks for
 *       ({@link #unboundedSize});
 *   <li>an evidence quote is the first words of the candidate's answer, found between its data markers, so it verifies;
 *   <li>every question text is made of words unique to its call, so no question repeats another, and like a real
 *       model's it names the topic it was asked about, as do its key points, so retrieval can find material for them;
 *   <li>given a résumé, it behaves like an honest extractor: every text and evidence is a bullet line of the résumé it was shown
 *       (lines starting with "- "), and a name is the first word of that line; asked about a résumé experience, it quotes the
 *       first words of its statement.
 * </ul>
 * It also embeds ({@code POST /api/embed}) with the deterministic {@link HashingEmbedder}, so retrieval runs for real
 * over pgvector; {@link #failEmbeddings} makes that endpoint answer 503.
 */
public final class FakeModelServer implements AutoCloseable {

    private static final Pattern TOPIC = Pattern.compile("(?m)^Topic: ([^\\n(]+)");
    private static final Pattern CANDIDATE_ANSWER =
            Pattern.compile("\\[BEGIN UNTRUSTED candidate_answer [0-9a-f]+]\\n(.*?)\\n\\[END UNTRUSTED", Pattern.DOTALL);
    private static final Pattern RESUME =
            Pattern.compile("\\[BEGIN UNTRUSTED resume [0-9a-f]+]\\n(.*?)\\n\\[END UNTRUSTED", Pattern.DOTALL);
    private static final Pattern STATEMENT = Pattern.compile("(?m)^Statement: (.+)$");
    private static final List<String> EMPTY_BY_DEFAULT = List.of("flags", "factualErrors", "candidateClaims");

    private final StubHttpServer server = new StubHttpServer();
    private final JsonMapper json;
    private final AtomicInteger calls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean embeddingsFail = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger embedCalls = new AtomicInteger();

    public FakeModelServer(JsonMapper json) {
        this.json = json;
        server.reply(request -> {
            JsonNode body = json.readTree(request.body());
            if (request.path().equals("/api/embed")) {
                return embed(body);
            }
            boolean gemini = request.path().endsWith(":generateContent");
            Fault fault = nextFault();
            if (fault != null && fault != Fault.MALFORMED) {
                return fault.reply(() -> gemini ? geminiAnswer(body) : ollamaAnswer(body, null));
            }
            String malformed = fault == Fault.MALFORMED ? "Sure! Here is the JSON you asked for: {\"prompt\": " : null;
            return gemini ? geminiAnswer(body, malformed) : ollamaAnswer(body, malformed);
        });
    }

    /** The ways a generation call can fail, as a real provider fails. */
    public enum Fault {
        /** The answer comes, but only after {@link #TIMEOUT_DELAY}: longer than the provider timeout the tests configure. */
        TIMEOUT,
        /** 429 with a Retry-After of one second. */
        RATE_LIMITED,
        /** 200 with text that is not the JSON document the schema asks for. */
        MALFORMED,
        /** 500. */
        SERVER_ERROR;

        private StubHttpServer.Reply reply(java.util.function.Supplier<StubHttpServer.Reply> valid) {
            return switch (this) {
                case TIMEOUT -> valid.get().after(TIMEOUT_DELAY);
                case RATE_LIMITED -> StubHttpServer.Reply.json(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}")
                        .withHeader("Retry-After", "1");
                case SERVER_ERROR -> StubHttpServer.Reply.json(500, "{\"error\":{\"code\":500,\"status\":\"INTERNAL\"}}");
                case MALFORMED -> throw new IllegalStateException("malformed output is a valid reply with other content");
            };
        }
    }

    /** How long a {@link Fault#TIMEOUT} answer takes. */
    public static final java.time.Duration TIMEOUT_DELAY = java.time.Duration.ofSeconds(3);

    private final java.util.concurrent.ConcurrentLinkedQueue<Fault> faults = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<Fault> outage = new java.util.concurrent.atomic.AtomicReference<>();

    /** Makes the next {@code times} generation calls fail with {@code fault} (embeddings are unaffected). */
    public void fail(Fault fault, int times) {
        for (int i = 0; i < times; i++) {
            faults.add(fault);
        }
    }

    /** Makes every generation call fail with {@code fault} until {@link #recover()}. */
    public void failAlways(Fault fault) {
        outage.set(fault);
    }

    /** Ends {@link #failAlways} and drops the faults {@link #fail} queued. */
    public void recover() {
        outage.set(null);
        faults.clear();
    }

    private Fault nextFault() {
        Fault queued = faults.poll();
        return queued != null ? queued : outage.get();
    }

    private StubHttpServer.Reply ollamaAnswer(JsonNode body, String content) {
        String text = content != null ? content
                : answer(body.get("format"), body.at("/messages/0/content").asString(), body.at("/messages/1/content").asString());
        ObjectNode response = json.createObjectNode();
        response.put("model", body.get("model").asString());
        response.putObject("message").put("role", "assistant").put("content", text);
        response.put("done", true).put("done_reason", "stop").put("prompt_eval_count", 400).put("eval_count", 120);
        return StubHttpServer.Reply.json(200, response.toString());
    }

    private StubHttpServer.Reply geminiAnswer(JsonNode body) {
        return geminiAnswer(body, null);
    }

    private StubHttpServer.Reply geminiAnswer(JsonNode body, String content) {
        String text = content != null ? content : answer(body.at("/generationConfig/responseFormat/text/schema"),
                body.at("/systemInstruction/parts/0/text").asString(), body.at("/contents/0/parts/0/text").asString());
        ObjectNode response = json.createObjectNode();
        ObjectNode candidate = response.putArray("candidates").addObject().put("finishReason", "STOP");
        candidate.putObject("content").put("role", "model").putArray("parts").addObject().put("text", text);
        response.putObject("usageMetadata").put("promptTokenCount", 400).put("candidatesTokenCount", 120);
        response.put("modelVersion", "fake-gemini");
        return StubHttpServer.Reply.json(200, response.toString());
    }

    /** Makes the embedding endpoint fail (503) or work again. */
    public void failEmbeddings(boolean fail) {
        embeddingsFail.set(fail);
    }

    /** How many embedding calls were answered. */
    public int embeddingCalls() {
        return embedCalls.get();
    }

    private StubHttpServer.Reply embed(JsonNode body) {
        if (embeddingsFail.get()) {
            return StubHttpServer.Reply.json(503, "{\"error\":\"embedding model unavailable\"}");
        }
        embedCalls.incrementAndGet();
        ObjectNode response = json.createObjectNode();
        response.put("model", body.get("model").asString());
        ArrayNode embeddings = response.putArray("embeddings");
        for (JsonNode input : body.get("input")) {
            ArrayNode vector = embeddings.addArray();
            for (float value : HashingEmbedder.embed(input.asString(), 768)) {
                vector.add(value);
            }
        }
        response.put("prompt_eval_count", body.get("input").size() * 50);
        return StubHttpServer.Reply.json(200, response.toString());
    }

    public URI baseUrl() {
        return server.baseUrl();
    }

    public List<StubHttpServer.Received> received() {
        return server.received();
    }

    private String answer(JsonNode schema, String systemPrompt, String userPrompt) {
        int call = calls.incrementAndGet();
        Matcher matcher = CANDIDATE_ANSWER.matcher(userPrompt);
        String answer = matcher.find() ? matcher.group(1) : "";
        Matcher topic = TOPIC.matcher(userPrompt);
        String about = topic.find() ? topic.group(1).replace("/", " ").replaceAll("\\s+", " ").strip() : "the subject";
        Matcher resume = RESUME.matcher(userPrompt);
        List<String> bullets = resume.find()
                ? resume.group(1).lines().filter(line -> line.startsWith("- ")).map(line -> line.substring(2).strip()).toList()
                : List.of();
        Matcher statement = STATEMENT.matcher(userPrompt);
        String experience = statement.find() ? statement.group(1) : "";
        return generate(schema, "", 0, new Context(call, answer, about, bullets, experience, firstWords(systemPrompt, 45), behaviour.get()))
                .toString();
    }

    /** How the model behaves. */
    public enum Behaviour {
        /** Valid, plausible answers (the default). */
        HONEST,
        /** A model that did what a candidate told it: every rating at the maximum, every key point covered. */
        FLATTERING,
        /** A model that was talked into reciting its instructions in every text it writes. */
        RECITING
    }

    private final java.util.concurrent.atomic.AtomicReference<Behaviour> behaviour =
            new java.util.concurrent.atomic.AtomicReference<>(Behaviour.HONEST);

    /** Changes how the model answers from now on; tests that do so restore {@link Behaviour#HONEST} afterwards. */
    public void behave(Behaviour how) {
        behaviour.set(how);
    }

    private record Context(int call, String candidateAnswer, String topic, List<String> resumeBullets, String resumeStatement,
            String systemPromptStart, Behaviour behaviour) {
    }

    private JsonNode generate(JsonNode schema, String name, int index, Context context) {
        if (schema.has("enum")) {
            JsonNode values = schema.get("enum");
            if (context.behaviour() == Behaviour.FLATTERING && name.equals("status")) {
                for (JsonNode value : values) {
                    if (value.asString().equals("COVERED")) {
                        return value;
                    }
                }
            }
            return values.get(index % values.size());
        }
        String type = schema.path("type").isArray() ? schema.path("type").get(0).asString() : schema.path("type").asString("");
        return switch (type) {
            case "object" -> {
                ObjectNode object = json.createObjectNode();
                for (var property : schema.path("properties").properties()) {
                    object.set(property.getKey(), generate(property.getValue(), property.getKey(), index, context));
                }
                yield object;
            }
            case "array" -> {
                ArrayNode array = json.createArrayNode();
                int size;
                if (schema.has("minItems") || schema.has("maxItems")) {
                    int min = schema.path("minItems").asInt(0);
                    int max = schema.path("maxItems").asInt(10);
                    size = Math.min(max, EMPTY_BY_DEFAULT.contains(name) ? min : Math.max(min, 1));
                } else {
                    size = unboundedSize(schema, name);
                }
                for (int i = 0; i < size; i++) {
                    array.add(generate(schema.get("items"), name, i, context));
                }
                yield array;
            }
            case "integer" -> json.getNodeFactory().numberNode(context.behaviour() == Behaviour.FLATTERING && name.equals("rating")
                    ? schema.path("maximum").asInt(4) : integer(schema, name));
            default -> json.getNodeFactory().stringNode(text(name, index, context));
        };
    }

    /**
     * The length of an array whose schema has no bounds, as Gemini is sent them: what a model that read the prompt would
     * write. Each pinned key point once, three items of any other list of objects (the prompts ask for three or more key
     * points), one item of a list of strings.
     */
    private static int unboundedSize(JsonNode schema, String name) {
        if (EMPTY_BY_DEFAULT.contains(name)) {
            return 0;
        }
        JsonNode items = schema.path("items");
        JsonNode keyPointIds = items.at("/properties/keyPointId/enum");
        if (keyPointIds.isArray()) {
            return keyPointIds.size();
        }
        return "object".equals(items.path("type").asString("")) ? 3 : 1;
    }

    private static int integer(JsonNode schema, String name) {
        int min = schema.path("minimum").asInt(0);
        int max = schema.path("maximum").asInt(100);
        int wanted = switch (name) {
            case "rating" -> 3;
            case "estimatedMinutes" -> 10;
            default -> min;
        };
        return Math.max(min, Math.min(max, wanted));
    }

    private static String text(String name, int index, Context context) {
        String unique = "c" + context.call();
        if (context.behaviour() == Behaviour.RECITING && List.of("prompt", "summary", "rationale", "correction", "reason").contains(name)) {
            return context.systemPromptStart();
        }
        List<String> bullets = context.resumeBullets();
        if (!bullets.isEmpty() && List.of("text", "evidence", "name", "title").contains(name)) {
            String line = bullets.get(index % bullets.size());
            return name.equals("text") || name.equals("evidence") ? line : line.split("\\s+")[0];
        }
        return switch (name) {
            case "prompt" -> "Question " + unique + " on " + context.topic() + ": describe " + unique + "alpha " + unique + "bravo "
                    + unique + "charlie " + unique + "delta and " + unique + "echo in a real system you built.";
            case "evidenceQuote" -> firstWords(context.candidateAnswer(), 6);
            case "resumeQuote" -> firstWords(context.resumeStatement(), 5);
            case "id" -> "kp" + (index + 1);
            case "text" -> "Idea " + (index + 1) + " of " + unique + " about " + context.topic() + " stated precisely";
            case "summary" -> "The candidate answered the question clearly.";
            case "rationale" -> "Correct and reasoned, with room for more depth.";
            case "input", "output" -> "[" + (index + 1) + "]";
            case "explanation", "starterSignature" -> "none";
            default -> "A short sentence " + (index + 1) + ".";
        };
    }

    private static String firstWords(String text, int count) {
        String[] words = text.strip().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(count, words.length)));
    }

    @Override
    public void close() {
        server.close();
    }
}
