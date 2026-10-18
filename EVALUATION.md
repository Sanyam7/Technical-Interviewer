# How the AI is evaluated

The interviewer's value depends on the model doing three things well: asking good, grounded questions; judging answers fairly;
and failing safely. Each of those is measured, against labelled data where a human judgement exists and with deterministic tests
where the behaviour can be pinned down. This page is the overview; the method and every recorded run are in [ai-evals/](ai-evals/).

> **Read the numbers with this in mind.** The measurements in the table used the **local** model, Ollama `qwen3.5:4b` (4 billion
> parameters, on a laptop). Gemini has so far only been checked, on `gemini-3.5-flash-lite`, for accepting the schemas, the
> smoke test and the résumé set (below); **the answer evaluator, the main measurement, has not been run on Gemini**, and nothing
> on `gemini-3.8-flash`, the production model, because the key's free tier allows 20 requests a day per model. One author wrote the
> labels; no independent rater reviewed them.

## What is measured

| Component | Dataset | How | Latest result (`qwen3.5:4b`) |
|---|---|---|---|
| Answer evaluator | [answer-evaluation.jsonl](ai-evals/datasets/answer-evaluation.jsonl): 40 answers to 5 questions, 8 categories (excellent, good, average, partially correct, incorrect, hallucinated, prompt injection, repeated), each with a human score band and key-point labels | `AnswerEvaluationLiveTest`, 3 runs per answer; 15 pass criteria | **13 of 15 pass.** Open: invented misconceptions on correct answers (0.36, limit 0.20); one injected answer scored 8.75 above its clean twin (limit 5) |
| Whole interviews | three simulated candidates (excellent, average, weak) | `CandidatePersonaLiveIT`: real engine, real agents, real retrieval | ordered correctly: 62.1 > 41.3 > 1.7; difficulty rose for the excellent candidate (3 → 4) and fell for the average one (3 → 2) |
| System design evaluator | [design-evaluation.jsonl](ai-evals/datasets/design-evaluation.jsonl): 7 designs | `DesignEvaluationLiveTest` | 4 of 7 meet every expectation; too harsh on minimal designs |
| Retrieval | [retrieval.jsonl](ai-evals/datasets/retrieval.jsonl): 78 labelled queries, including off-topic ones that must return nothing | `RetrievalQualityLiveIT` (real embeddings), `RetrievalQualityIT` (every build, deterministic embedding) | recall@5 0.993, MRR@5 0.962, abstention 1.000 |
| Question de-duplication | [question-duplicates.jsonl](ai-evals/datasets/question-duplicates.jsonl): 59 labelled pairs | `QuestionDuplicatesLiveTest` calibrates the cosine threshold per embedding model | `embeddinggemma` 0.80, `gemini-embedding-2` 0.90 (measured); 0 repeats in 23 interview questions |
| Résumé extraction | [resume-extraction.jsonl](ai-evals/datasets/resume-extraction.jsonl): 5 résumés | `ResumeExtractionLiveTest` | 5 of 5 meet every expectation |
| Structured output | every live call above | first-try schema and semantic validity | 100 % (229 calls), no repair needed |
| Failure modes | — | deterministic, every build: `GeminiFaultScenariosIT` (timeout, rate limit, malformed output, server error, embeddings down) against a stand-in speaking Gemini's wire protocol; `AdversarialInterviewIT` (a model that obeys injected instructions) | a transient fault is invisible to the candidate; a lasting one loses no answer and the interview catches up; an obeying model still cannot change a score, state or rubric |

The pass criteria, the categories and four rounds of prompt changes measured against them are in
[ai-evals/ANSWER_EVALUATION.md](ai-evals/ANSWER_EVALUATION.md); the twelve required cases (excellent to weak candidate, partial,
incorrect, hallucinated, injected and repeated answers, malformed output, missing RAG context, Gemini timeout and rate limit) with
their evidence are in [ai-evals/AI_EVALUATION_REPORT.md](ai-evals/AI_EVALUATION_REPORT.md).

## Why the measurements look like this

- **The model rates, Java scores.** The evaluator returns a 0–4 rating per criterion, key-point statuses with quotes, and factual
  errors with corrections. The server verifies the quotes, caps ratings that contradict the model's own findings
  (`RatingRules`) and computes the score with weights per skill. So the evaluator can be measured on its judgements (key points,
  errors, flags), not only on a number.
- **Bands, not exact scores.** A human score is a band (for example 35 to 65 for a partially correct answer); the criterion is
  the share of runs inside the band, plus ordering (excellent > average > partial > incorrect).
- **Three runs per answer** expose instability (the mean spread is 3.9 points; one PostgreSQL question swings by up to 18.7).
- **Deterministic first.** Everything that does not need a real model (parsing, validation, repair, retries, scoring, policies,
  fault handling, injection channels) is a unit or integration test in the normal build; the live suites run by hand.

## Demo run of the pre-release audit (2026-10-13)

A 30-minute full-loop interview (Java, Spring Boot, JavaScript coding) was run through the browser on the local model, with a
person writing the answers: a weak answer, strong answers, a self-correction, a claim about real experience, a reference coding
solution and a full URL-shortener design with a diagram.

| What happened | Verdict |
|---|---|
| The first technical question built on the introduction (it referred to the N+1 fix the candidate described) | memory works |
| A partly wrong answer ("ConcurrentHashMap locks the whole map") scored 36.25; the evaluator quoted the wrong sentence, corrected it and cited the knowledge-base passage; the engine asked a follow-up on the missing key point and lowered the difficulty 3 → 2 | correct |
| The corrected answer scored 86.25; the engine then probed the candidate's claimed experience; after two follow-ups it moved to the second skill | correct |
| A strong concurrency answer raised that topic's difficulty 2 → 3 | correct |
| The coding solution passed 9 of 9 hidden tests in the sandbox, scored 100 and got a "go deeper" follow-up | correct |
| Two correct Spring Boot answers scored 57.5 and 11.25: the evaluator listed "factual errors" whose corrections were wrong or agreed with the candidate, against the very passage it cited | **wrong** (the open criterion) |
| One follow-up began "You mentioned that ConcurrentHashMap uses per-bin locks", which the candidate had not said | **wrong** wording, right target |
| An evaluation that marked key points covered without quoting the answer was refused twice; the answer stayed pending and was retried at the end | validation working |
| The design evaluation was refused because the model's quotes exceeded 300 characters, and the repair ran out of time on the local model | **bug, fixed**: over-long quotes are now cut at a word boundary (still verbatim, still verified); covered by a unit test with the model's reply; a live re-run on the local model was cut short by the laptop going to sleep, so the fix is not yet confirmed live |
| The browser abandoned the design turn after 130 s while the server was still evaluating, and showed an error for an answer that had been stored | **bug, fixed**: the client waits up to ten minutes and re-reads the session after a timeout |
| Finishing took 11 minutes on the local model (pending evaluations retried, then the narrative); the wrap-up page reported "could not be finished" while the server completed the report | **bug, fixed**: the wrap-up page polls the session and moves to the results when the report is done (verified on the stuck page itself) |
| The report: 63.0, "Developing", PARTIAL; it said that 2 answers could not be assessed (76 % of the interview was) and did not count them as zero | correct |
| The report's narrative listed the evaluator's invented Spring Boot "mistake" as a weakness to study | **wrong**: an evaluator error propagates into the study plan; the strongest reason for the verification step below |

Every model call in the run was recorded in `llm_calls` with its prompt version, tokens, latency and status, and each turn's
reasons (score, follow-up rule, difficulty change, time left) were stored with its state transition; the table above was
reconstructed from those two tables.

## Live check of the staged interview (2026-10-14)

One 30-minute interview over the API on the local model (full stack, mid level; Java, Spring Boot, React, TypeScript; default
fundamentals-first approach; scripted answers, so the scores are not a measure of the evaluator). It checks the order and the
grounding of the questions (AI_ARCHITECTURE.md §2.15):

| # | Type | Question (as worded by the model) | Template |
|---|---|---|---|
| 2 | INTERNALS | How does a HashMap handle collisions internally, specifically what happens to a bucket when it grows too large, and why does this matter for performance? | `java-hashmap-internals` |
| 4 | CONCEPT | Explain what `useEffect` is for in React. Specifically, describe when an effect runs relative to the render cycle and why a dependency array is necessary. | `react-usestate-useeffect` |
| 6 | CONCEPT | What are the different bean scopes available in Spring Boot, and what happens to a prototype bean that is injected into a singleton bean? | `spring-bean-scopes` |
| 8 | CONCEPT | What is the difference between an interface and a type alias in TypeScript, particularly regarding how they handle unions? | `ts-interface-vs-type` |
| 10 | SCENARIO | Your team needs an `InstrumentedHashSet` ... A teammate suggests extending `HashSet` ... What is one major issue, and how would you design it differently? | `java-composition-over-inheritance` |
| 11 | EXPERIENCE | You mentioned building a React admin dashboard for your order service. How did you handle identifying list items across renders ...? | (the introduction's statements) |

Each fundamentals question was followed by a gap probe on its own subject (3, 5, 7, 9), the experience question by one about its
own work (12). No question before the project stage referred to the candidate's work, and the model reworded every template. The
report gave fundamentals 75 % of the overall weight (8 answers), practical 8 %, project 17 %. Every turn's stored rationale names its
stage, type and topic ("QUESTIONING FUNDAMENTALS question 1 of 4: INTERNALS java/collections at level 3 (MEDIUM) ...").

## First calls to Gemini (2026-10-18)

The first run of the live suites against the real Gemini API failed at once: every résumé extraction was refused with HTTP 400
"Request contains an invalid argument". Until then the Gemini provider had only met a stand-in that speaks its wire format
(`GeminiFaultScenariosIT`), and the stand-in accepts any schema.

| Step | Result |
|---|---|
| Probing the request on `gemini-3.8-flash`, one field at a time | plain request, thinking level, `responseFormat` and `responseJsonSchema` all accepted; the real `resume-extraction.v1` schema refused in both formats |
| Removing keywords from that schema, one kind at a time | refused without descriptions, enums, numeric limits or `additionalProperties`; **accepted without `minItems`/`maxItems`**; small schemas with bounds accepted: a size limit, not an unsupported keyword |
| Fix | `GeminiLlmProvider` sends the schemas without array bounds (keeping `maxItems: 0`); the bounds stay enforced in Java (AI_ARCHITECTURE.md §5) |
| Every current schema, on `gemini-3.5-flash-lite` (the daily quota of `3.8-flash` was used up by the probes) | all accepted, the evaluators' with their ratings filled in for a full rubric |
| `LiveProviderSmokeTest` (question, a strong and an injected answer evaluated, follow-up, report narrative) | 5 of 5 calls valid at the first attempt, no repair; the strong answer rated 4 on every criterion with verified quotes, the injected one flagged and rated 0 |
| `ResumeExtractionLiveTest` | 5 of 5 cases meet every expectation (the local model's baseline: 5 of 5) |

Still open: the answer, design and persona suites on Gemini, and anything on `gemini-3.8-flash`. The free tier allows 5 requests a
minute and 20 a day per model, too few for those runs (the answer set alone is 120 calls) or for a whole interview.

## Known weaknesses

1. **Invented misconceptions.** On correct answers, the local model sometimes lists a "factual error" whose correction is wrong
   or even agrees with the candidate, and the rating cap then lowers correctness. Measured at 36 % of correct answers. The quote
   check cannot catch it (the quoted words are really the candidate's); the error is in the correction.
2. **Run-to-run instability on some questions** (key-point judgements that change between runs).
3. **Injection variance:** a flagged answer's injection is removed before the model sees it, but criteria that key points do not
   bound (communication, practical understanding) can still vary upward by a few points (SECURITY.md R-17).
4. **Follow-up wording:** the follow-up writer occasionally attributes to the candidate something they did not say ("You mentioned
   …"). The engine's decision (what to probe) is correct; only the wording is wrong.

## Next steps, in order

1. Run the answer and design suites on Gemini with a paid key, and the résumé suite on `gemini-3.8-flash`
   (`LIVE_EVAL_PROVIDER=gemini` with `GEMINI_API_KEY`, `LIVE_EVAL_MODEL` for another model; the persona interviews run on the
   `local-ollama` profile and need a Gemini variant first); decide the production model on those numbers.
2. If invented misconceptions persist, add a verification step for factual errors (a second, cheap call that must confirm the
   correction against the cited passage, or a rule that an unsourced correction cannot cap a rating) and measure it on the same set.
3. Have a second engineer review the labels.

## How to run

```bash
cd backend
./mvnw verify                                   # every deterministic AI test (no model needed)
# live suites (need Ollama with qwen3.5:4b + embeddinggemma, or LIVE_EVAL_PROVIDER=gemini and GEMINI_API_KEY)
LIVE_EVAL_REPEATS=3 ./mvnw test -Dexcluded.test.groups= -Dgroups=live -Dtest=AnswerEvaluationLiveTest
./mvnw verify -Dexcluded.test.groups= -Dgroups=live -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='CandidatePersonaLiveIT,RetrievalQualityLiveIT'
```

Reports go to `ai-evals/reports/` (git-ignored); runs worth keeping are copied to `ai-evals/baselines/`.
