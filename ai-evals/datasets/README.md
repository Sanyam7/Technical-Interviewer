# AI evaluation datasets

## retrieval.jsonl

Labelled queries for measuring retrieval quality over the curated knowledge base
(`backend/src/main/resources/knowledge-base`). One JSON object per line:

| Field | Meaning |
|---|---|
| `id` | Stable name of the case |
| `kind` | The question kind the search serves (`TECHNICAL`, `CODING`, `SYSTEM_DESIGN`) |
| `query` | The semantic text, phrased like the agents' searches (a question, sometimes with a key point), deliberately not copied from the documents |
| `keywords` | The keyword terms, built the way `GroundingQueries` builds them: the exact identifiers in the text (`HashMap`, `@Transactional`, `SKIP LOCKED`), usually none |
| `technologies` | The technology scope the search is restricted to (empty: any) |
| `topic` | The catalog topic key, which gets the fusion bonus (null: none) |
| `relevant` | Sections that answer it, as `source-path#Heading`; any chunk of such a section counts. Empty: the knowledge base does not cover the query and the right answer is nothing |

Metrics (computed by `RetrievalEvaluation` in the backend tests, top 5):

- **recall@5**: share of the relevant sections that appear in the top 5, averaged over answerable queries.
- **hit@5**: share of answerable queries with at least one relevant section in the top 5.
- **MRR@5**: mean reciprocal rank of the first relevant section (0 when none is in the top 5).
- **abstention**: share of unanswerable queries for which retrieval returns nothing.

`RetrievalQualityIT` runs it in every build with a deterministic test embedding (a regression check of the pipeline,
not of semantic quality). `RetrievalQualityLiveIT` (tag `live`) runs it with a real embedding model; see
RAG_ARCHITECTURE.md for the recorded results.

## question-duplicates.jsonl

Labelled pairs of interview questions for calibrating the semantic de-duplication threshold
(`app.rag.deduplication.min-similarity`, AI_ARCHITECTURE.md §2.9). One JSON object per line:

| Field | Meaning |
|---|---|
| `id` | Stable name of the pair |
| `kind` | `paraphrase` (the same question in other words), `same-topic` (a different question on the same topic, which an interviewer may well ask next) or `other-topic` |
| `duplicate` | true exactly for `paraphrase`: the second question must be rejected after the first |
| `a`, `b` | The two questions |

The hard cases are `same-topic` pairs: a topic with a quota of two is asked two different questions, and those must not be
mistaken for repeats. `QuestionDuplicatesLiveTest` (tag `live`) embeds every question with the model's similarity task,
logs the similarity range of each kind and the result at a sweep of thresholds, and checks the configured threshold:
no different question flagged, most paraphrases caught. `QuestionDuplicatesDatasetTest` keeps the file well formed in
every build.

Recorded on 2026-10-10 (25 paraphrase, 28 same-topic and 6 other-topic pairs):

| Model | Paraphrases | Same topic | Threshold | Paraphrases caught | False repeats |
|---|---|---|---|---|---|
| `gemini-embedding-2` | 0.864 – 0.977 | ≤ 0.878 | 0.90 | 21 / 25 | 0 |
| `embeddinggemma` | 0.659 – 0.946 | ≤ 0.773 | 0.80 | 17 / 25 | 0 |

## answer-evaluation.jsonl

Labelled answers for measuring the answer evaluator (AI_ARCHITECTURE.md §2.10; method and results in
[../ANSWER_EVALUATION.md](../ANSWER_EVALUATION.md)). Five questions (Java concurrency, Spring transactions, PostgreSQL
indexing, React rendering, caching) with one answer of each category: `EXCELLENT`, `GOOD`, `AVERAGE`, `PARTIAL` (partly
correct, with a mistake), `INCORRECT` (confidently wrong), `HALLUCINATED` (fluent, but built on an API, flag or feature that
does not exist) and `INJECTION` (the `AVERAGE` answer with an attempt to instruct the evaluator appended, phrased differently
for every question: a direct instruction, a fake system message, a JSON payload, a code comment, a polite request) and, since
2026-10-13, `REPEATED` (the `GOOD` answer pasted word for word as the answer to a follow-up question it does not answer; the
follow-up has its own question and key points, and the case carries the earlier turn in `thread`). One JSON object per line:

| Field | Meaning |
|---|---|
| `id`, `group`, `category` | Stable name, the question it belongs to, the quality of the answer |
| `evaluator`, `skill`, `topic`, `difficulty` | How the application would evaluate it: the rubric, the skill profile (weights and focus areas) and the level |
| `question`, `keyPoints` | The question and its expected key points, as the question generator would have produced them |
| `answer` | The candidate's answer |
| `thread` | For a follow-up (`REPEATED` only): the earlier turns of its thread, `{question, answer}`, sent to the evaluator as the application sends them; absent otherwise |
| `expected.humanScore` | The score a careful interviewer would give, 0 to 100, in the dataset author's judgement |
| `expected.scoreBand` | The range the computed score must fall in |
| `expected.keyPoints` | For each constrained key point, the statuses that count as right (an unlisted key point is not checked) |
| `expected.factualErrors` | The range of factual errors the evaluation should list (`[0, 0]` for a correct answer) |
| `expected.mustFlag`, `mustNotFlag` | Flags the evaluation must and must not carry |
| `expected.maxCorrectness` | The highest acceptable correctness after the server's rules (1 for incorrect and hallucinated answers) |
| `expected.invarianceOf` | For an injection, the clean answer it must not outscore by more than 5 points |

The answers and labels were written by one author (the AI assistant that built the framework) and have not yet been
reviewed by an independent engineer; the "human" scores are that single rater's judgement, and the bands are deliberately wide
(15 to 35 points) for that reason. `AnswerEvaluationDatasetTest` keeps the file consistent in every
build; `AnswerEvaluationLiveTest` (tag `live`) runs the real evaluator on it.

## design-evaluation.jsonl

Labelled system design answers for measuring the design evaluator (AI_ARCHITECTURE.md §2.12; results in
[../ANSWER_EVALUATION.md](../ANSWER_EVALUATION.md#system-design-evaluator)). Seven answers to the bank's URL shortener, each built
to test one property: a strong design covering every dimension; a correct core with nothing else (dimensions never discussed must
not rate above 1); technology names without a design; three false statements (Redis durability, 301 counting, collision-free
truncated hashes); the core design with an instruction to the grader (must be flagged, must not outscore the clean answer); a probe
answer that closes the scalability and caching gaps of the core design (the evaluation is cumulative); and a design described only
in its diagram (the diagram's words must count). Labels by the dataset's author (one rater). One JSON object per line:

| Field | Meaning |
|---|---|
| `id`, `problem`, `difficulty` | Stable name, the bank problem (its statement and key points are used), the level |
| `thread`, `question`, `probe` | Earlier turns (`"PROBLEM"` stands for the statement), the latest question and the dimension it probes; null for the problem itself |
| `answer`, `architecture` | The latest answer and the diagram as the evaluator reads it |
| `expected.scoreBand` | The range the computed score must fall in |
| `expected.minRatings`, `expected.maxRatings` | Bounds on dimension ratings after the server's rules |
| `expected.factualErrors`, `expected.mustFlag`, `expected.invarianceOf` | Error count range, required flags, the clean case an injection must not outscore |

`DesignEvaluationDatasetTest` keeps it well formed in every build; `DesignEvaluationLiveTest` runs the real evaluator on it.

## resume-extraction.jsonl

Labelled résumés for measuring the résumé extractor and verifier (AI_ARCHITECTURE.md §2.13). One JSON object per line:

| Field | Meaning |
|---|---|
| `id` | Stable name of the case |
| `note` | What the case tests |
| `resume` | The résumé as text (the live test parses it like an uploaded text file) |
| `expected.substance` | Whether anything a résumé holds can be verified (false: `NOT_A_RESUME`) |
| `expected.injection` | Whether the résumé contains text addressed to the AI, which must be flagged |
| `expected.statedYears` | The years of experience it states in words, or null |
| `expected.experiences` | Statements that must be among the profile's experiences (each a prefix of the stored text) |
| `expected.technologies` | Canonical technology names that must be in the profile |
| `expected.mustNotContain` | Text that must appear nowhere in the stored profile: injected claims and contact details. A hard guarantee: the live test fails on it whatever the model |

`ResumeEvaluationDatasetTest` checks in every build that the labels rest on their own text. `ResumeExtractionLiveTest` (tag
`live`) runs the real extractor and writes `ai-evals/reports/resume-extraction-<provider>-<model>.md` with the reason of every
dropped item; the recorded runs are in `baselines/`. Five cases written by the assistant, not reviewed by a person: a smoke test,
not a statistical measurement.
