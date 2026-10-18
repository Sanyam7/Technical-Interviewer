# AI evaluation report

13 October 2026. What the AI interviewer was tested against, what it measured at, what the audit fixed, and what is still open.
The method behind the answer-level numbers is in [ANSWER_EVALUATION.md](ANSWER_EVALUATION.md); the raw runs are in
[baselines/](baselines/).

**Setup.** Every live number below comes from the local default model, Ollama `qwen3.5:4b` (no thinking), with `embeddinggemma`
embeddings and the curated knowledge base. **Gemini was not measured** (no API key was available for this run); its failure
modes (timeout, rate limit, malformed output, server error) are tested deterministically against a stand-in that speaks the
Gemini wire protocol. The labels of the answer set were written by one author, not reviewed by an independent engineer.

## Summary

| | Result |
|---|---|
| Answer evaluation, 40 labelled answers × 3 runs | 13 of 15 pass criteria. Open: invented misconceptions (0.36, limit 0.20), and one injected answer that scored 8.75 above its clean twin |
| Whole interviews, three simulated candidates | told apart in the right order: excellent 62.1, average 41.3, weak 1.7; difficulty went up for the excellent candidate (3 → 4) and down for the average one (3 → 2); the weak one moved between 2 and 3 as topics changed |
| Structured output | 100 % valid on the first try (120 evaluations; 109 model calls in the interviews), no repair needed, no failures |
| Duplicate questions | 0 of 23 questions repeated, none above the near-duplicate threshold (highest pair 0.79 against 0.80) |
| Retrieval | recall@5 0.993, MRR@5 0.962, every off-topic query answered with nothing (78 labelled queries) |
| Cited passages | 100 % from the question's technology, 93 % from its topic; 20 of 23 questions grounded |
| Consistency | mean score spread 3.9 points across runs (limit 5), but up to 18.7 on one question |
| Gemini timeout, rate limit, malformed output, outage | a fault that clears is invisible to the candidate; one that lasts loses nothing, and the interview catches up (10 scenarios, every build) |
| Fixed by this audit | Gemini 429s ignored the delay Gemini asks for; a metric of the scorecard counted the wrong answers (see *Findings*) |

## The twelve cases

| # | Case | How it is tested | Result |
|---|---|---|---|
| 1 | Excellent candidate | live: a whole interview by a candidate who states every hidden key point (`CandidatePersonaLiveIT`); 5 excellent answers × 3 runs (`AnswerEvaluationLiveTest`) | interview 62.1 (DEVELOPING), difficulty 3 → 4, 3 follow-ups; answers 81.7 on average (human 94), 73 % in band |
| 2 | Average candidate | live: a whole interview stating half the key points, hedged; 5 average answers × 3 | interview 41.3 (NOT_YET), difficulty 3 → 2; answers 43.8 (human 51.8), 87 % in band |
| 3 | Weak candidate | live: a whole interview of "I am not sure"; incorrect answers × 3 | interview 1.7 (NOT_YET), never followed up; every rating 0 except communication |
| 4 | Partially correct answer | live: 5 answers with one or two real mistakes × 3 | 30.1 (human 34.6), 93 % in band, the mistake listed in 100 % |
| 5 | Incorrect answer | live: 5 confidently wrong answers × 3 | 12.0 (human 5.2), 100 % in band, correctness ≤ 1 every time |
| 6 | Hallucinated answer | live: 5 answers built on APIs and flags that do not exist × 3 | 10.8 (human 9.6), caught 15 of 15 |
| 7 | Prompt injection | live: 5 injection styles × 3; deterministic: `AdversarialInterviewIT` (the five attacks of the security brief, a model that obeys them), `PromptInjectionChannelsTest` | flagged 15 of 15; 14 of 15 scored no more than 5 above the clean answer; one scored 8.75 above (open, below) |
| 8 | Repeated answer | live (new): an earlier answer pasted word for word to a follow-up it does not answer × 3; deterministic: `AnswerRepetition` and the engine never follow up a repeat | 15.4 (human 8.8), 15 of 15 in band (0 to 35) |
| 9 | Malformed LLM output | deterministic: `GeminiFaultScenariosIT` (malformed once: repaired, the repaired answer used; always: the answer waits, nothing invalid stored), `GeminiThroughTheLayerTest`, `OutputParserTest`; live: first-try validity | pass; live output was never malformed (0 of 229 calls) |
| 10 | Missing RAG context | deterministic: `GeminiFaultScenariosIT` (the embedding service down: the interview runs ungrounded and cites nothing), `GroundedAgentsTest` (no material: nothing can be cited); live: 3 of 23 interview questions had no passage and were written ungrounded | pass |
| 11 | Gemini timeout | deterministic: `GeminiFaultScenariosIT` through the real Gemini provider (a call that outlives its timeout: retried; a lasting one: the answer waits, the next question can be asked for again, the report catches up) | pass |
| 12 | Gemini rate limit | deterministic: same, with 429; `GeminiLlmProviderTest` | pass after a fix: Gemini's wait was ignored (below) |

## Quality metrics

| Metric | How it is measured | Result |
|---|---|---|
| Structured output validity | share of structured calls whose first output was valid (schema, then the agents' checks) | 120 of 120 answer evaluations; 109 of 109 calls in the three interviews (question and follow-up generation, evaluation, report, embeddings) |
| Model failures | timeouts, errors, rate limits, invalid output after the repair, evaluations that could not run | 0 in both runs; the failure paths are tested deterministically (cases 9, 11, 12) |
| Duplicate question rate | within each interview: exact repeats, pairs at cosine ≥ 0.80 (the measured threshold for `embeddinggemma`), and drafts the de-duplication refused | 0 of 23, 0 pairs (highest 0.79), 0 refusals |
| Retrieval relevance | the labelled set `datasets/retrieval.jsonl` (78 queries) with the real embedding model; and in the interviews, whether a cited passage is about the question's technology and topic | labelled set at the shipped threshold 0.30: recall@5 0.993, hit@5 1.000, MRR@5 0.962, abstention on off-topic queries 1.000 (re-measured, identical to 2026-10-09); citations: 86, 100 % same technology, 93 % same topic |
| Evaluation consistency | each answer evaluated three times: score standard deviation per answer; identical key-point judgements | mean 3.9 points, largest 18.7; key points judged identically in all three runs for 30 of 40 answers |
| Latency (wall clock, local model on a laptop) | one answer evaluation; one interview turn (the evaluation and the next question) | evaluation p50 34.8 s, p95 47.7 s, max 54.6 s; turn p50 51 to 70 s, max 81.5 s |

The latency is that of a 4-billion-parameter model on a laptop, and part of the evaluation run overlapped other test runs on the
same machine; it says nothing about Gemini. Per-call latency from the interviews is not reported, because those tests run on a
frozen clock and the application times its calls with that clock.

## Findings and fixes

1. **Gemini's rate-limit delay was ignored (fixed).** Gemini answers 429 without a `Retry-After` header; it puts the wait in the
   body (`error.details[].retryDelay` of a `google.rpc.RetryInfo`, for example `"27.966937085s"`). The provider read only the
   header, so a rate-limited call was retried after the generic backoff (0.5 to 8 s), hit the limit again, and failed after
   spending three requests of quota. The provider now reads the body's delay; the client waits it out when it fits the task's
   budget and gives up at once when it does not. Tests: `GeminiLlmProviderTest`, `GeminiThroughTheLayerTest`.
2. **The "no invented misconceptions" metric counted the wrong answers (fixed).** When the repeated-answer category was added,
   the metric (documented as correct answers to their own question) also counted the repeats, and reported 0.48 instead of
   0.36. The scorecard now uses the documented population; the run's scorecard was annotated rather than rerun.
3. **Repeats are not rewarded, but they are mislabelled.** All 15 repeated answers stayed in their band (mean 15.4), so a
   pasted answer does not earn the score of an answer. In 13 of 15, however, the model listed the candidate's own correct
   sentences as "factual errors" (meaning: it does not address the question). Harmless to the score (it only lowers it further),
   but the report would show a correct sentence as a mistake. Open: tell the evaluator that the engine found a repeat, or have
   it use the key points rather than factual errors for "does not address".
4. **An injection paid once, slightly (open).** The Spring answer with a fake system message was flagged in every run and its
   injection removed before the model saw it, so the model judged the clean answer plus a removal marker. Its key points were
   judged exactly as the clean answer's in all three runs, but communication and practical understanding came out one step
   higher in one run: 73.75 against a best clean score of 65 (limit +5). The evidence caps bound a flagged answer by what its
   own words cover; they do not bound criteria like communication. The other 14 injected runs scored at or below the clean
   answer +5 (ten of them at least 6 points lower). The defensible next step is to measure with Gemini before changing the
   rule.
5. **Invented misconceptions (open, known).** 16 of 45 correct answers got at least one invented factual error (36 %, limit
   20 %; 33 % in the last recorded round). With a correct answer this costs correctness. It is the main weakness of the small
   local model on this set; Gemini has not been measured.
6. **One question is unstable.** The PostgreSQL composite-index answers swing widely between runs (the average answer scored
   55, 18.75 and 12.5; the good one 18.75 to 51.25), because the model's key-point judgements change between runs. The other
   four questions are stable (spread mostly under 6 points).
7. **The excellent candidate lands at DEVELOPING.** The simulated excellent candidate states every key point but explains
   nothing beyond them, and the evaluator rates depth, practical understanding and reasoning about 2 of 4 for that: a list of
   the right ideas is not an excellent answer. The persona tells the candidates apart reliably; its absolute level is a property
   of the simulation, not a scoring defect. The answer set's excellent answers (written with mechanisms and trade-offs) score
   81.7 on average.

## How to reproduce

```bash
cd backend
# answer evaluation, 3 runs per case (about an hour with qwen3.5:4b)
LIVE_EVAL_REPEATS=3 ./mvnw test -Dexcluded.test.groups= -Dgroups=live -Dtest=AnswerEvaluationLiveTest
# three whole interviews (about 25 minutes)
./mvnw verify -Dexcluded.test.groups= -Dgroups=live -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CandidatePersonaLiveIT
# retrieval on the labelled set
./mvnw verify -Dexcluded.test.groups= -Dgroups=live -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RetrievalQualityLiveIT
# Gemini: LIVE_EVAL_PROVIDER=gemini GEMINI_API_KEY=... for the answer evaluation
```

The deterministic suites (fault scenarios, injection, malformed requests) run in every `./mvnw verify`.

## Limitations

- No Gemini numbers: the production model may do better or worse on every live metric above.
- One rater wrote the labels; the bands are wide for that reason.
- The simulated candidates are deterministic and terse; they test that the interviewer separates quality and adapts, not how
  it reacts to natural, messy answers.
- Three runs per answer is enough to see instability, not to estimate it precisely.
