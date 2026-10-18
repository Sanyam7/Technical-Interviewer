# Project summary

## What it does

An adaptive mock-interview platform for software engineers. The candidate chooses a role, level, difficulty, length and format
(technical questions, coding, system design, or all three), one to six of nine technologies and an interview approach, optionally
with a résumé. An AI interviewer runs a timed interview the way IT-services interviewers do: the fundamentals of every chosen
technology first, then practical scenarios and debugging, then the candidate's own projects. It asks grounded questions, evaluates
each answer against a rubric, and follows up on gaps, on strong answers (one level deeper) or on claimed experience. It sets a coding problem whose hidden tests decide correctness, and probes a system design. At the end it writes a
report with scores (also by assessment area: fundamentals, practical, problem solving, project depth, communication), feedback per
answer, misconceptions, a study plan and a recommendation.

## Architecture

A **modular monolith**: one Spring Boot 4 service (Java 21) and a React 19 single-page app, with PostgreSQL 17 + pgvector as the
only datastore. The backend is organised by feature (interview, evaluation, ai, rag, coding, design, resume, security), with ArchUnit
enforcing the dependency rules.

The centre is a **pure-Java interview engine**: a state machine, a planner, and policies for follow-ups, difficulty, pacing and
design probes. It has no Spring, no persistence and no model calls, so it can be unit-tested and explained. It talks to the AI
through three ports (question generator, answer evaluator, report narrator). The AI layer implements them with bounded agents over a
provider abstraction (Gemini, or Ollama locally) and hybrid retrieval.

A turn is **three short transactions with every model call between them**, so no database connection is held while a model thinks.
Idempotent resubmission and optimistic locking make retries and double submits safe. Diagrams and details:
[ARCHITECTURE.md](ARCHITECTURE.md).

## GenAI components

| Component | What it does | What keeps it honest |
|---|---|---|
| Question generator | Writes a question of the stage, type, topic and difficulty chosen by the engine, from a curated angle of the question bank (209 templates), grounded in retrieved passages, with hidden expected key points | Topic and difficulty pinned by the schema; key points must cite passages; embedding similarity rejects paraphrases of earlier questions; fundamentals questions never see the résumé or the candidate's claims |
| Follow-up generator | Words a follow-up the engine decided on (probe a gap, probe a claim, go deeper, probe a design dimension) | Must not state the key point it probes or repeat the question |
| Answer evaluator | Rates 6 criteria (13 for designs) from 0 to 4, judges key points with quotes, lists factual errors with corrections | Quotes must occur in the answer; rating caps when the ratings contradict the findings; the score is computed in Java; coding correctness comes from tests |
| Report narrator | Writes the narrative of the report | May not state numbers; every evaluation it cites is verified |
| Résumé extractor | Turns a résumé into a structured profile | Every fact must quote the document; contact details are removed first |
| Retrieval | pgvector cosine + PostgreSQL full-text, fused with RRF, topic bonus, similarity floor | Measured on 78 labelled queries (recall@5 0.993) |
| Safety | Untrusted text fenced and redacted; injection flagged; prompt-echo replies rejected; no tools | An adversarial suite runs the real agents against a model that obeys the candidate |

## Technology

React 19, TypeScript, Vite, Tailwind 4, TanStack Query, CodeMirror · Java 21, Spring Boot 4.1, Spring Security (JWT, rotating
refresh cookie), JPA + `JdbcClient`, Flyway · PostgreSQL 17 + pgvector · Gemini / Ollama over REST · Docker sandbox for code ·
JUnit 5, Testcontainers, ArchUnit, Vitest, MSW · GitHub Actions, Docker, Caddy, Cloud Run / Cloudflare Pages / Neon.

## Major engineering decisions

1. **The LLM never controls flow.** An autonomous agent loop would be harder to test, slower and costlier, and impossible to
   explain to a candidate. The engine decides; agents do language tasks.
2. **The model rates; Java scores.** The model gives a rating per criterion, never an overall score. Weights live in configuration
   per skill, rule-based caps catch self-contradiction, and a prompt injection has no score field to target.
3. **Own provider interface instead of Spring AI or LangChain4j.** Two small REST APIs; full control over structured output,
   token usage, retries and per-call recording; nothing framework-specific in domain code.
4. **pgvector in the main database, hybrid retrieval.** One datastore and transactional consistency. Keyword search catches
   identifiers (`@Transactional`, `SKIP LOCKED`) that embeddings blur. Every retrieval change was measured on the labelled set.
5. **The résumé is a verified structured profile, not RAG material.** No private text sits in a shared index, and every
   extracted fact is backed by a quote.
6. **No streaming.** A structured answer must be complete before it can be validated, so the UI reveals a validated question
   progressively instead.
7. **Code runs only where it is safe.** A hardened throw-away Docker container per run locally (no network, read-only, limits).
   It is off in the production image, because containers sharing a kernel are not a boundary for untrusted code.
8. **Fundamentals first, résumé last.** The interview was dominated by résumé questions; the cause was in the planner and the prompt
   (anchoring from question 1, every question tailored to the candidate's claims), not in retrieval. Stages now order the questions,
   only the project stage sees the résumé, and weak fundamentals cap the recommendation whatever the project answers scored.
9. **Low-cost deployment.** One instance on scale-to-zero, Neon free tier, static frontend; no Redis until a second instance exists.

## Challenges

- **Small models and structured output.** A 4B local model needed schema narrowing per request, a repair attempt that lists the
  problems, and `num_ctx` raised to 16k, because Ollama's default 4k context silently truncated design evaluations.
- **Gemini's real wire format.** The contract tests passed, but the live API wanted `APPLICATION_JSON` as an enum, and its 429
  carries the retry delay in the body (`RetryInfo`), not in a `Retry-After` header. Both were found against the real service and
  pinned in tests.
- **Prompt injection.** The first detector caught one of five attack styles. Defence became layered: redaction of every
  untrusted value in one renderer, fencing with a per-request nonce, echo detection, scores out of the model's reach, and an
  adversarial suite.
- **Evaluation quality.** The local evaluator invents mistakes in correct answers. The quote check doesn't catch it, because the
  quoted words really are the candidate's; the wrong part is the correction. Measured (36 %), documented, and not papered over.
- **Failure without losing work.** Evaluations that fail stay pending, are retried with backoff and a lease, and are given up
  after three attempts. The report then says what it could not assess, and becomes inconclusive below 70 % coverage.
- **Cross-site cookies in deployment.** `*.pages.dev` and `*.run.app` are different sites: a `SameSite=Lax` refresh cookie is not
  stored at all, so users were signed out on reload. Found in a real browser; now configurable, with a same-site option.

## Limitations

- Every measured AI number comes from a local 4B model; **Gemini has not been measured** (a smoke test and the résumé set pass on
  `gemini-3.5-flash-lite`, after the first live calls showed that Gemini refused the résumé schema because of its array bounds). One author wrote the labels.
- The local evaluator invents factual errors in about a third of correct answers, and a long system design can exceed its time
  budget.
- No per-user token budget, no admin UI, no account deletion. Rate limits are in memory (one instance).
- Code execution is local only; in production, coding answers are reviewed by the model, with correctness capped as unverified.
- The hosted deployment commands are documented but have not been run.
- The 209 question templates were written by one author and not reviewed by a second; REST APIs and Microservices are one topic
  each because the knowledge base has one document for each.

## Future improvements

1. Measure every suite on Gemini and choose the production models on the numbers.
2. Verify factual errors before they can cap a rating: a cheap second call that checks the correction against the cited passage,
   or "no source, no cap". Measure it on the same labelled set.
3. A per-user daily token budget from `llm_calls`.
4. A separate, gVisor-isolated code runner for production.
5. An admin view of `llm_calls` and of each turn's rationale (the data is already stored).
6. A second rater for the labelled sets; more cases for the unstable questions.

## Interview talking points

- **"Why not let the LLM run the interview?"** Cost, latency and testability. The engine is pure Java with table-driven tests, and
  every decision has a stored rationale (score 36, key point 3 missing → follow-up on it, difficulty 3 → 2).
- **"How do you stop the model from hallucinating a score?"** It never produces one. It rates criteria with quotes, the quotes
  are verified, contradictions are capped, and Java computes the score. Coding correctness comes from tests, not the model.
- **"How do you know RAG helps?"** Retrieval is measured on 78 labelled queries with recall, MRR and abstention on off-topic
  queries. Every change (identifier-only keyword terms, a topic bonus, the similarity floor) was kept only if the numbers held.
- **"What happens when Gemini is down or rate-limited?"** Retries within each task's time budget, honouring Gemini's requested
  delay; then the answer stays pending, the interview continues, and the report catches up. Ten fault scenarios run in CI against
  a stand-in that speaks Gemini's protocol.
- **"How do you handle prompt injection?"** Separate instructions from data, redact and flag, and keep the score out of the
  model's hands. It's measured: 15 of 15 injected answers flagged. One injected answer still scored 8.75 above its clean twin,
  which is documented as open.
- **"What would you change to scale it?"** Move the rate-limit buckets to Redis, add a per-user token budget, and run code in a
  separate isolated service. The engine and the AI layer are already stateless between requests.
- **"How do you stop the résumé from taking over the interview?"** The plan is staged: fundamentals of every technology, then
  scenarios, then projects, with the shares chosen by the candidate. Only the project stage sees the résumé; a claim is probed only
  in a project thread; the report shows how much of the score came from each area and caps the band when fundamentals are weak.
  Found by reading the code (anchoring from question 1, a prompt that tailored every question to the candidate's claims), fixed in
  the engine, verified in a live run.
- **"What is the weakest part?"** The evaluator on a small model, with numbers to show it, and the next experiment to fix it.
