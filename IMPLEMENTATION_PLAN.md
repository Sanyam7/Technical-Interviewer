# Implementation Plan

> Ten phases, each independently shippable and testable. Paths are relative to `backend/src/main/java/com/techinterviewer/` (backend) and `frontend/src/` (frontend) unless shown otherwise.
> Every phase ends with green CI, updated docs, and no placeholder/fake production code.
> **Status as of 2026-10-13** is in the table below; the sections after it are the plan as written, with what was delivered recorded per area.

## Overview

| Phase | Name | Outcome | Status |
|---|---|---|---|
| 0 | Foundation | Repo, Docker Postgres+pgvector, Spring Boot + React skeletons, CI | ✅ done |
| 1 | Auth & security baseline | Register/login/refresh/logout, JWT, CORS, rate limits, error model | ✅ done (sign-in and sign-up screens since 2026-10-11) |
| 2 | AI provider layer | `LlmProvider`/`EmbeddingProvider` (Gemini + Ollama), routing, structured outputs, call recording | ✅ done (2026-10-09); embeddings moved to Phase 3; the diagnostics endpoint was not built |
| 3 | Catalog, knowledge base & RAG | Skills/topics, ingestion, chunking, embeddings, hybrid retrieval, admin playground | ✅ done (2026-10-09) except admin upload/APIs, the playground screens and the exemplar fallback |
| 4 | Interview engine (Q&A loop) | State machine, planner, generator, evaluator, follow-ups, adaptive difficulty, interview room UI | ✅ done: adaptive engine, grounded generator and follow-up agents, interview room UI (2026-10-10/11) |
| 5 | Final evaluation & reports | Report aggregation + narrative, history dashboard — **MVP complete** | ✅ done: aggregation, knowledge gaps, narrator, report, history and dashboard screens |
| 6 | AI evaluation harness | Golden datasets, eval runner, baselines, CI workflow | 🟡 five labelled datasets, live runners with scorecards, recorded baselines (`ai-evals/`); not built: the CI workflow for live evaluations; Gemini runs not done (no key) |
| 7 | Coding & system design rounds | Code editor, coding + design evaluators, `FULL_LOOP` | ✅ done: code editor, Docker sandbox with hidden tests (local), design workspace with diagrams and probes; evaluator tuning on Gemini open |
| 8 | Résumé personalisation & observability UI | Secure uploads, private RAG, admin AI trace/metrics | 🟡 résumé built 2026-10-12 (without RAG, see below); the observability UI was not built |
| 9 | Hardening & deployment | Docker image, Cloud Run + Neon + Cloudflare Pages, budgets, E2E smoke | 🟡 security hardening (2026-10-12), Docker images, self-hosted stack, CI image builds, deployment guide (2026-10-13); not built: per-user token budget, E2E smoke test in CI; the hosted deployment has not been run |

Dependency chain: 0 → 1 → 2 → 3 → 4 → 5 → (6, 7, 8 in any order) → 9. Phase 6 can start in parallel with 5; its first dataset is created in Phase 4.

---

## Backend and persistence layer (delivered 2026-10-08, between Phase 0 and Phase 2)

The requested "backend and persistence layer" cut across the phases: it delivers everything that does not need an LLM. What exists, and what each phase still has to add:

| Delivered | Where it counts |
|---|---|
| Authentication: register, login, refresh rotation with reuse detection, logout, JWT (HS256), roles, ownership-scoped access (404 for others), per-IP and per-user rate limits, request size limits, strict JSON, CORS | Phase 1 (complete on the backend) |
| Entities, repositories and migrations-validated mappings for users, refresh tokens, profiles, skills, topics, configurations, interviews, questions, answers, evaluations, reports, skill scores, knowledge documents and chunks | Phases 1, 3, 4, 5 |
| Catalog and profile APIs | Phase 3 (the admin knowledge APIs, ingestion, embeddings and retrieval remain) |
| Pure interview engine: state machine, planner, topic selector, follow-up policy, difficulty adapter, deadline policy, memory, turn decider, introduction prompt; scoring, rubric registry, report aggregation, recommendation policy | Phases 4, 5 |
| Interview services and REST API: configurations, create, start, session/state/questions, answer, skip, next question, finish, abandon, transcript, report, report retry; three-transaction turns; lazy deadlines; idempotency; optimistic locking | Phases 4, 5 |
| The three AI ports (`QuestionGenerator`, `AnswerEvaluator`, `ReportNarrator`) with documented contracts and degraded behaviour, scripted test doubles | seams for Phases 2, 4, 5, 7 |
| OpenAPI 3.1 document that validates and generates type-checking TypeScript | Phases 4, 5 (frontend) |

**Verification:** about 1,984 unit and 1,055 integration tests (Testcontainers); several hundred behavioural mutants applied across the lanes; a seeded simulation of complete interviews of every type and duration with random answer quality, skips and outages; a manual end-to-end run against the real server. **Independently reviewed** in two passes (security and API contract; then state integrity, persistence, test-suite quality and clean-clone/operations), every finding reproduced by a skeptic and the confirmed ones fixed with tests (ARCHITECTURE.md section 10). The evaluation give-up policy (retry limit, `UNASSESSABLE` answers, `PARTIAL` and `INCONCLUSIVE` reports) was added afterwards (2026-10-09, AI_ARCHITECTURE.md section 10.6); the remaining AI-layer decisions are in section 10.7.

**Next:** Phase 2 (provider layer) implements the ports' dependencies; Phase 4/5 then write the agents behind `QuestionGenerator`, `AnswerEvaluator` and `ReportNarrator`, and the frontend screens. The contract is in [AI_ARCHITECTURE.md §10](AI_ARCHITECTURE.md).

## AI layer (delivered 2026-10-09)

The provider layer of Phase 2 and the agents of Phases 4, 5 and 7 (without RAG), described in [AI_ARCHITECTURE.md §11](AI_ARCHITECTURE.md):

- `ai/provider`: `LlmProvider`, `GeminiLlmProvider` (REST `generateContent`, `responseFormat.text` schema, `thinkingLevel`), `OllamaLlmProvider` (`/api/chat`, `format`), normalised `LlmException`s, `ProviderHttp` (hard per-call deadline).
- `ai/routing`: `AiProperties` (`app.ai`), `AiTask`, `ModelRouter` with startup checks (including evaluation budget < attempt lease).
- `ai/prompt`: versioned `prompts/{id}/v{n}.md`, `PromptRegistry`, `PromptRenderer` with nonce-delimited untrusted values.
- `ai/structured`: `JsonSchemaRegistry` (`schemas/*.json`), `OutputParser`, `StructuredOutputClient` (budget, retries, one repair, call records).
- `ai/observability`: `LlmCall`, `LlmCallRecorder` (one `llm_calls` row per call).
- `ai/agents`: `QuestionGeneratorAgent`, `FollowUpGeneratorAgent`, `AnswerEvaluatorAgent`, `ReportNarratorAgent`, `AgentQuestionGenerator`; guards `EvidenceVerifier`, `InjectionHeuristics`, `QuestionChecks`.
- Config: `app.ai.enabled` (`AI_ENABLED`, on in `prod`), `application-local-ollama.yml`.
- Tests: provider contract tests on a JDK HTTP stub, parser fixtures, client orchestration, prompt and schema consistency, agents with `ScriptedLlmProvider`, Spring wiring, `LlmCallRecorderIT`, `AiInterviewFlowIT` (a whole interview through `FakeModelServer`, formerly `FakeOllamaServer`), and `LiveProviderSmokeTest` (tag `live`, real Ollama or Gemini).

Left for later phases: embeddings, RAG grounding and the exemplar fallback (3), the diagnostics endpoint and eval datasets (4/6), payload capture, cost and metrics (8).

## RAG (delivered 2026-10-09)

Phase 3's knowledge base and retrieval, and the grounding of the Phase 4/5 agents, described in [RAG_ARCHITECTURE.md](RAG_ARCHITECTURE.md):

- Corpus: 48 original documents over 16 technologies (`backend/src/main/resources/knowledge-base/`), every catalog topic covered; strict front matter (`CorpusParser`).
- Ingestion: `MarkdownChunker` (heading-aware, code blocks intact, overlap), `IngestionService`, `CuratedCorpusLoader` (startup, idempotent by path and hash, removal, malformed documents skipped, embedding outages filled at the next run), `ChunkEmbeddings`.
- Embeddings: `EmbeddingProvider` with `GeminiEmbeddingProvider` (`batchEmbedContents`, 768 dimensions) and `OllamaEmbeddingProvider` (`/api/embed`); `EmbeddingService` (batches, retries, call records).
- Retrieval: `ChunkSearch` (pgvector HNSW + full text), `HybridRetriever` (RRF, topic bonus, similarity floor, keyword fallback), `ContextAssembler`, `KnowledgeRetriever`; `GroundingQueries` (technology scope, topic, difficulty, interview type).
- Agents: prompt and schema v2 with citations; key points, factual errors and further reading mapped to chunks; deterministic knowledge gaps (`KnowledgeGaps`) in the report; `llm_calls.rag_chunk_ids`; report API `furtherReading`.
- Evaluation: `ai-evals/datasets/retrieval.jsonl` (78 labelled queries); live `embeddinggemma` recall@5 0.993, MRR@5 0.962, abstention 1.0; CI regression guard with a deterministic test embedding.

Not done from the Phase 3 plan: admin upload (MD/TXT/PDF) and the admin knowledge/retrieval APIs, the playground screens, the query-embedding cache (not needed so far), and calibration of the Gemini embedding threshold (quota).

## Adaptive interview engine (delivered 2026-10-10)

The second iteration of the Phase 4 engine, described in [AI_ARCHITECTURE.md §2.9](AI_ARCHITECTURE.md):

- Candidate state: the memory keeps the candidate-wide moving average and level (`overall`) and the misconceptions; `CandidateState` derives strengths, weaknesses, unanswered areas, the current level as EASY/MEDIUM/HARD, the time left and the closing window. New topics and the coding and design sections start at the candidate's level.
- Time: sections never take the time planned for later ones, a main question starts only when it can be answered (2 / 8 minutes), sections that cannot start are skipped, and the interview ends with `TIME_UP` when nothing fits any more; the closing window keeps only gap probes.
- Balance: follow-ups never use the time reserved for skills not asked yet.
- Repeated answers (`AnswerRepetition`, word-trigram containment) are never followed up and are counted.
- Semantic de-duplication of questions (`QuestionDeduplicator`, the embedding model's similarity task, `questions.embedding`), thresholds measured on `ai-evals/datasets/question-duplicates.jsonl` (59 labelled pairs): 0.90 for `gemini-embedding-2` (21/25 paraphrases caught, no false repeat), 0.80 for `embeddinggemma` (17/25, none).
- Every turn records its rationale in the transition's `details`.
- Tests: `AdaptiveInterviewScenarioTest` (strong, weak, mixed, repeated answers, limited time, closing window, topic imbalance, follow-ups, difficulty changes), `CandidateStateTest`, `AnswerRepetitionTest`, `SemanticDeduplicationTest`, `QuestionDeduplicationIT`, new cases in `TurnDeciderTest`, `FollowUpPolicyTest`, `DeadlinePolicyTest`, `MemoryUpdaterTest`, `TurnAdaptiveIT`.

## Structured candidate evaluation (delivered 2026-10-10)

Described in [AI_ARCHITECTURE.md §2.10](AI_ARCHITECTURE.md); method and results in [ai-evals/ANSWER_EVALUATION.md](ai-evals/ANSWER_EVALUATION.md):

- Six technical criteria (correctness, completeness, depth, practical understanding, reasoning, communication; rubric `technical.v2`), misconceptions as factual errors that may name the earlier mistake they repeat, and the evaluator's confidence (not calibrated; never changes a score; LOW marks the answer for review). Prompts v3, schema `answer-evaluation.v3`.
- Configurable weights (`app.evaluation.scoring`): built-in base tables, and skill profiles in `application.yml` for the six catalog skills (weights and focus areas; a coding answer uses its language's profile, a design problem the `system-design` profile), validated at startup.
- Consistency rules (`RatingRules`): correctness capped by the model's own factual errors, completeness by the verified key-point coverage, everything by an off-topic verdict, and everything by the evidence when the answer tried to instruct the evaluator. The scored and the model's rating are both stored.
- Knowledge gaps in the report (`KnowledgeGapAnalyzer`): missed concepts, misconceptions, weak topics, repeated mistakes. Migration V10.
- `ai-evals/datasets/answer-evaluation.jsonl` (35 labelled answers in seven categories) and `AnswerEvaluationLiveTest`, which writes a scorecard per model; four measured rounds on `qwen3.5:4b` (13 of 14 pass criteria met in the last; the open one is misconceptions invented in correct answers), recorded in `ai-evals/baselines/`. Injection defence: detected instructions are removed before the model sees the answer.
- Tests: `RatingRulesTest`, `AnswerScorerTest`, `ScoringPolicyTest`, `KnowledgeGapAnalyzerTest`, `AnswerEvaluationDatasetTest`, new cases in `AnswerEvaluatorAgentTest`, `EvaluationServiceIT`, `PendingEvaluationIT`, `ReportFinalizationIT`, `ReportControllerIT`.

---

## Before Phase 0 — prerequisites (decisions confirmed 2026-10-05)

1. **Install Docker Desktop for Mac** (the only missing required tool; choose the Apple Silicon build) and start it once. Verify with `docker compose version` and `docker run --rm hello-world`.
2. **Gemini API key** from Google AI Studio → `GEMINI_API_KEY` in a git-ignored `.env`.
3. *(Optional, local RAG on Ollama)* `ollama pull nomic-embed-text`.
4. ✅ Naming confirmed: base package `com.techinterviewer`, Maven `groupId=com.techinterviewer`, `artifactId=tech-interviewer`.
5. ✅ Skill scope confirmed: **exactly** Java, Spring Boot, PostgreSQL, System Design, React, TypeScript. These six are the whole v1 catalog, so every selectable skill has grounded RAG material.

---

## Phase 0 — Foundation ✅ COMPLETE (2026-10-05)

**Built**
- Root: `.gitignore`, `.editorconfig`, `.env.example`, `README.md` (exact local setup), `docker-compose.yml` (Compose v2, `pgvector/pgvector:pg17` multi-arch image, loopback-only port, `.env`-driven credentials, `pg_isready` health check, named volume; see [ARCHITECTURE.md §8.2](ARCHITECTURE.md)), `.github/workflows/ci.yml`
- Backend (`backend/`, Spring Initializr, Boot 4.1.1, Java 21, Maven wrapper):
  - `common/api` (`ApiPaths`, `PageResponse`), `common/error` (`ErrorCode`, `ApiException`, `GlobalExceptionHandler` producing RFC 9457 problem+json with `code`, `traceId`, field `errors`), `common/logging` (`CorrelationIdFilter`, `X-Request-Id`)
  - `config` (`AppProperties`, `ClockConfig`, `OpenApiConfig`), `security` (deny-by-default stateless chain, CORS, 401/403 problem responses; **no authentication mechanism yet**), `system` (`GET /api/v1/health`)
  - `application.yml` + `application-local.yml` + `application-prod.yml`; virtual threads, `ddl-auto=validate`, Actuator limited to health/info with probes, springdoc enabled only in `local`, ECS JSON logs in `prod`
  - Flyway migrations V1–V8 + `R__seed_catalog.sql`: **the complete schema (20 tables) and the six-skill catalog** (see the change log in [ARCHITECTURE.md §10](ARCHITECTURE.md))
- Frontend (`frontend/`, Vite + React 19 + TypeScript strict + Tailwind 4 + React Router + TanStack Query): application layout, live system-status card (loading, up, degraded, unreachable), 404 page, typed `apiFetch` with problem+json `ApiError`, API types generated from the backend OpenAPI document (`pnpm gen:api`)

**Dependencies:** backend: web (MVC), validation, data-jpa, actuator, security, PostgreSQL driver, Flyway, springdoc-openapi, Testcontainers, ArchUnit. Frontend: react, react-router, @tanstack/react-query, Tailwind, Vitest, Testing Library, MSW, ESLint, Prettier, openapi-typescript. Deliberately **not** included yet: oauth2-resource-server and Zustand (Phase 1), Redis (not needed), any AI SDK.

**Tests**
- Backend: 85 unit tests (error handling, correlation id, configuration and profile validation, database-password check, bounded health probe, ArchUnit rules) and 92 integration tests on a Testcontainers pgvector database (health contract, security and CORS, container error dispatch over real HTTP, prod profile, OpenAPI, JSON strictness, schema inventory, exact seed catalog, constraints, vector and full-text columns, the user-delete cascade). Tests never read the developer's `.env`.
- Frontend: 107 tests (API client, health data layer, retry policy, status card states, router, layout landmarks, dev-server configuration). A 121-mutant campaign leaves a single equivalent survivor.

**Acceptance criteria (met)**
- `docker compose up -d`, `./mvnw verify` in `backend/` and `pnpm lint && pnpm typecheck && pnpm test && pnpm build` in `frontend/` all pass.
- The running stack was exercised end to end: Flyway applied the schema to the dev database, the web app shows the backend status through the Vite proxy, and the CORS preflight works.
- No secrets are committed; `.env` is ignored; Swagger UI is off outside the `local` profile.

---

## Phase 1 — Authentication & security baseline

**Files**
- `user/User.java`, `user/Role.java`, `user/UserRepository.java`
- `security/SecurityConfig.java`, `JwtTokenService.java`, `RefreshToken.java`, `RefreshTokenRepository.java`, `RefreshTokenService.java`, `AuthController.java`, `AuthService.java`, `CurrentUser.java` (+ argument resolver), `RateLimitFilter.java`, `RateLimitPolicy.java`, `dto/{RegisterRequest, LoginRequest, TokenResponse, UserView}.java`
- `config/SecurityProperties.java` (JWT secret/TTL, CORS origins), `common/error/ErrorCode.java`, `ApiException.java`
- Frontend: `stores/authStore.ts`, `api/auth.ts`, `api/http.ts` (bearer injection, single-flight refresh on 401), `features/auth/{LoginPage, RegisterPage}.tsx`, `app/ProtectedRoute.tsx`

**Functionality:** register, login, refresh (rotation + reuse detection), logout, `/users/me`; BCrypt(12); 15-min access JWT; HttpOnly refresh cookie; CORS allowlist; RFC 9457 errors; IP rate limits on auth endpoints.

**Dependencies:** `spring-boot-starter-oauth2-resource-server` (moved here from Phase 0, see [ARCHITECTURE.md §10](ARCHITECTURE.md)), `bucket4j_jdk17-core`, `caffeine`, `spring-security-test`; frontend: `zustand` (auth store).

**Starting point from Phase 0:** the `users` and `refresh_tokens` tables already exist (V2). Replace the deny-by-default chain's `denyAll` with real authorization rules, keep the problem+json entry point and access-denied handler, and add the JPA entities (`ddl-auto=validate` will check them against the schema; `char(64)` hash columns and `citext` need explicit type mappings).

**Tests**
- `AuthControllerIT`: happy paths; duplicate email; wrong password generic error; refresh rotation; **reused refresh token revokes the family**; expired access token → 401 `TOKEN_EXPIRED`.
- `SecurityConfigIT`: CORS preflight allowed vs disallowed origin; unauthenticated → 401; candidate on `/admin/**` → 403; refresh without `X-Requested-With` → 403.
- `PasswordPolicyTest` (> 72 bytes rejected), `JwtTokenServiceTest`, `RateLimitFilterTest` (429 + `Retry-After`).
- Frontend: MSW test that two concurrent 401s trigger exactly one refresh; form validation tests.

**Acceptance criteria**
- A user can register, log in, reload the page (session restored via refresh), and log out from the UI.
- Access token is never written to `localStorage`/`sessionStorage`.
- All tests above green.

---

## Phase 2 — AI provider layer & structured outputs

**Files**
- `ai/provider/{LlmProvider, LlmRequest, LlmMessage, LlmResponse, GenerationOptions, TokenUsage, FinishReason, ProviderCapabilities, EmbeddingProvider, EmbeddingTask, AiTask, ModelRouter, ProviderRegistry, RetryingExecutor}.java`
- `ai/provider/LlmException.java` + subclasses (`RateLimitedException`, `ProviderUnavailableException`, `InvalidRequestException`, `ContentBlockedException`, `LlmTimeoutException`)
- `ai/provider/gemini/{GeminiLlmProvider, GeminiEmbeddingProvider, GeminiWireModels}.java`
- `ai/provider/ollama/{OllamaLlmProvider, OllamaEmbeddingProvider, OllamaWireModels}.java`
- `ai/structured/{StructuredOutputClient, JsonSchemaRegistry, OutputParser, RepairPolicy, StructuredOutputException}.java`
- `ai/prompt/{PromptTemplate, PromptRegistry, PromptRenderer, UntrustedText}.java`
- `ai/observability/{LlmCall, LlmCallRepository, LlmCallRecorder, AiMetrics}.java`
- `config/AiProperties.java`, `application.yml` routing block, `application-local-ollama.yml`
- `admin/AdminAiDiagnosticsController.java` — `POST /admin/ai/diagnostics` runs a tiny real structured task (e.g. classify a sentence's programming language) on a chosen provider to verify wiring
- `resources/prompts/diagnostics/v1.md`, `resources/schemas/diagnostics-result.json`
- Test fixtures: `src/test/resources/llm-fixtures/gemini/*.json`, `ollama/*.json`, `malformed/*.txt`; `src/test/java/.../ai/ScriptedLlmProvider.java` (test-only double)

**Starting point from Phase 0:** the `llm_calls` table already exists (V8); this phase adds its JPA entity and recorder.

**Functionality:** provider-agnostic generation and embedding; task routing from config; JSON-schema-constrained output with parse → validate → repair (≤ 2) → typed result; normalised errors; retries for 429/5xx/timeouts; every attempt recorded in `llm_calls` with tokens, latency, prompt version; fail-fast startup checks (embedding dimension = 768, enabled providers configured).

**Dependencies:** `wiremock-standalone` (test). No AI SDKs — Spring `RestClient` + Jackson.

**Tests**
- Gemini/Ollama provider tests against WireMock with recorded payloads: success + usage metadata, 429 with `Retry-After`, 500 then success, timeout, `MAX_TOKENS`, safety block, embedding batch.
- `OutputParserTest`: fenced JSON, prose-wrapped JSON, truncated JSON, wrong types, missing required field, unknown enum, out-of-range value, empty text, extra fields.
- `StructuredOutputClientTest` (ScriptedLlmProvider): valid first try; invalid → repaired; invalid twice → exception; `llm_calls` statuses `OK`/`REPAIRED`/`INVALID_OUTPUT`.
- `ModelRouterTest`, `UntrustedTextTest` (nonce delimiting, delimiter spoofing in content neutralised), `LlmCallRecorderIT`.
- `@Tag("live")` `LiveProviderSmokeTest` (runs only when `GEMINI_API_KEY` / Ollama is available).

**Acceptance criteria**
- Diagnostics endpoint returns a validated typed object from Gemini **and** from Ollama by changing configuration only.
- Gemini embeddings return unit-normalised 768-d vectors; Ollama `nomic-embed-text` returns 768-d vectors.
- Every call appears in `llm_calls`; malformed fixtures are all handled deterministically.

---

## Phase 3 — Catalog, knowledge base & RAG

**Files**
- `catalog/{Skill, Topic, SkillRepository, TopicRepository, CatalogService, CatalogController}.java`, `catalog/dto/*`
- `rag/domain/{KnowledgeDocument, KnowledgeChunk, ChunkEmbedding}.java`, repositories
- `rag/ingestion/{IngestionService, TextExtractor, FileTypeDetector, MarkdownChunker, ChunkingPolicy, EmbeddingIndexer, CuratedCorpusLoader}.java`
- `rag/retrieval/{HybridRetriever, VectorSearchDao, KeywordSearchDao, ReciprocalRankFusion, RetrievalQuery, RetrievedChunk, QueryEmbeddingCache}.java`
- `rag/context/{ContextAssembler, TokenEstimator, AssembledContext}.java`
- `admin/{AdminKnowledgeController, AdminRetrievalController}.java`
- Corpus: `knowledge-base/{java,spring-boot,postgresql,system-design,react,typescript}/*.md`, at least one note per seeded topic (original, hand-written notes with front-matter: skill, topic, difficulty, doc_type) and `knowledge-base/question-exemplars/**/*.md` (exemplar questions + key points + rubric anchors)
- `ai-evals/datasets/retrieval.jsonl` (≥ 40 labelled query → relevant chunk/heading pairs)
- Frontend: `api/catalog.ts`, `api/admin.ts`, `features/admin/{KnowledgeDocumentsPage, RetrievalPlaygroundPage}.tsx`

**Starting point from Phase 0:** the catalog tables and the six-skill seed (37 topics), and the `knowledge_documents`, `knowledge_chunks` and `embeddings` tables with their HNSW and GIN indexes already exist (V3, V4, seed). This phase adds the entities, ingestion, retrieval and the corpus.

**Functionality:** skills/topics API; idempotent curated-corpus ingestion at startup; admin upload (MD/TXT/PDF) with magic-byte validation; heading-aware chunking; batch embedding; hybrid retrieval (vector + FTS + RRF; a long semantic query for the embedding and a short keyword term list for full text) with metadata and **visibility/owner filters**; token-budgeted context assembly with citation IDs; retrieval playground UI.

**Dependencies:** `pdfbox`, `tika-core`, `caffeine` (query-embedding cache).

**Tests**
- `MarkdownChunkerTest` (heading paths, code blocks never split, overlap, size bounds), `FileTypeDetectorTest` (PDF disguised as `.txt`, binary disguised as `.pdf`), `ReciprocalRankFusionTest`, `ContextAssemblerTest` (budget, dedup, ordering).
- `HybridRetrieverIT` (Testcontainers) using a test-only embedding double with hand-crafted vectors to assert vector ranking (exactly ordered), keyword ranking (a long list of terms still ranks partial matches, which an AND query would miss), fusion with deterministic tie-breaks, filters including the difficulty window, and `hnsw.iterative_scan` behaviour with selective filters; on a larger synthetic corpus, an `EXPLAIN` of the vector half must show `Index Scan using ix_embeddings_hnsw` for broad filters ([DATABASE_SCHEMA.md §4.1](DATABASE_SCHEMA.md)).
- `RetrievalAuthorizationIT`: user B never receives user A's `PRIVATE` chunks.
- `CuratedCorpusLoaderIT`: second run creates no rows; an edited file (same `source_path`, new content hash) updates and re-chunks only that document and never creates a second document for the path.
- `@Tag("llm-eval")` `RetrievalEvalTest`: recall@5 and MRR with real embeddings.

**Acceptance criteria**
- Corpus loads on startup; playground shows sensible ranked chunks with vector/keyword ranks.
- **recall@5 ≥ 0.8** on the labelled retrieval set (Gemini embeddings); result recorded as the first baseline.
- Re-ingestion is a no-op; private documents are isolated.

---

## Phase 4 — Interview engine: adaptive Q&A loop

**Files**
- `interview/domain/{Interview, InterviewConfiguration, InterviewState, InterviewEvent, Section, Question, QuestionKind, Answer, InterviewPlan, InterviewMemory, StateTransition}.java`
- `interview/engine/{InterviewEngine, InterviewStateMachine, InterviewPlanner, TopicSelector, FollowUpPolicy, DifficultyAdapter, DeadlinePolicy, MemoryUpdater, TurnResult}.java`
- `interview/service/InterviewService.java`, `interview/repository/*`
- `interview/api/InterviewController.java`, `interview/api/dto/{CreateInterviewRequest, InterviewSessionResponse, SubmitAnswerRequest, TurnResponse, QuestionView, ProgressView, PublicPlanView}.java`
- `evaluation/{Evaluation, CriterionScore, KeyPointAssessment, EvaluationFlag, ScoreAggregator, EvidenceVerifier, RubricRegistry, EvaluationRepository}.java`
- `ai/agents/{QuestionGeneratorAgent, AnswerEvaluatorAgent, FollowUpAgent, QuestionValidator}.java`, `ai/agents/output/{GeneratedQuestion, AnswerEvaluation, FollowUpQuestion}.java`
- `ai/guard/InjectionHeuristics.java`
- `resources/prompts/{question-generator,answer-evaluator,follow-up}/v1.md`, `resources/schemas/{generated-question,answer-evaluation,follow-up-question}.json`, `resources/rubrics/{technical,intro}.yml`
- `ai-evals/datasets/answer-evaluation.jsonl` (first ~40 human-labelled cases, used to tune prompts during this phase)
- Frontend: `api/interviews.ts`, `stores/interviewRoomStore.ts`, `features/setup/InterviewSetupWizard.tsx`, `features/interview/{InterviewRoom, QuestionPanel, AnswerEditor, CountdownTimer, ProgressRail, ThinkingIndicator}.tsx`

**Functionality:** create (`INIT` + deterministic plan, recorded as the first state transition) → start (`INTRODUCTION`) → adaptive `QUESTIONING` with `FOLLOW_UP`; RAG-grounded question generation with validators, embedding dedup, and exemplar fallback; rubric-based evaluation with evidence verification and deterministic scoring; follow-up policy; per-topic difficulty adaptation; structured memory; lazy deadline enforcement; idempotent answers; optimistic locking; deferred evaluation on provider failure. Supports interview type `TECHNICAL`.

**Dependencies:** none new.

**Tests**
- `InterviewStateMachineTest`: every legal transition and a table of illegal ones.
- `InterviewPlannerTest`: durations × types × experience levels → sections, slots, bands.
- `FollowUpPolicyTest`, `DifficultyAdapterTest`, `ScoreAggregatorTest`, `EvidenceVerifierTest` (fabricated quotes downgraded + flagged), `InjectionHeuristicsTest`, `MemoryUpdaterTest`, `QuestionValidatorTest` (wrong topic, answer leakage, duplicate).
- `InterviewFlowIT` (Testcontainers + ScriptedLlmProvider): full 15-min interview incl. a follow-up and time-up via a controllable `Clock`.
- `InterviewApiIT`: idempotent resubmission, `QUESTION_NOT_CURRENT`, concurrent submit → 409, other user's interview → 404, evaluator outage → turn succeeds with `PENDING`, generator outage → exemplar fallback.
- `CandidateResponseContractTest`: no candidate-facing JSON contains `expectedKeyPoints`, `rubric`, `difficulty`, or scores before completion.
- Frontend: wizard validation; room renders each state; drafts survive reload; MSW-driven turn flow.

**Acceptance criteria**
- A real 15-minute `TECHNICAL` interview completes end-to-end on Gemini, and on Ollama via the local profile.
- The admin trace (DB/logs at this stage) shows difficulty changing with performance and follow-ups targeting missing key points.
- Evaluator on the 40-case set: ordering excellent > average > partial > incorrect holds; injection cases do not raise scores.

---

## Phase 5 — Final evaluation, reports & history (MVP)

**Files**
- `evaluation/report/{Report, ReportSkillScore, ReportService, ReportAggregator, RecommendationPolicy, ReportGroundingVerifier, ReportController}.java`, `evaluation/report/dto/*`
- `ai/agents/ReportWriterAgent.java`, `ai/agents/output/ReportNarrative.java`
- `resources/prompts/report-writer/v1.md`, `resources/schemas/report-narrative.json`
- Frontend: `api/reports.ts`, `features/report/{ReportPage, SkillBreakdown, EvidenceList}.tsx`, `features/history/DashboardPage.tsx`, `features/interview/TranscriptView.tsx`

**Functionality:** `finish` endpoint runs final evaluation in-request: re-run pending evaluations → deterministic aggregation (difficulty-weighted skill scores, confidence, recommendation band, integrity notes) → narrative with evidence references → grounding verification → `COMPLETED`. Idempotent retry on failure. History dashboard and transcript with feedback.

**Tests:** `ReportAggregatorTest` (difficulty weighting, confidence thresholds, skipped answers read from `answers.skipped` and scored 0, the design section taking the last answered turn), `RecommendationPolicyTest`, `ReportGroundingVerifierTest` (items citing unknown evaluation IDs dropped), `ReportServiceIT` (pending evaluations re-run first; idempotency; failure → `FAILED` + retry), `FinishEndpointIT`.

**Acceptance criteria**
- Every completed interview has a report whose scores come only from deterministic fields and whose every strength/improvement links to concrete answer evidence.
- Simulated provider failure: each answer is tried three times, then marked `UNASSESSABLE` (never scored zero); the interview still completes, with a `PARTIAL` or `INCONCLUSIVE` report that discloses what was left out (AI_ARCHITECTURE.md section 10.6). A `FAILED` report remains only while another request's attempt is still running, and retrying succeeds.
- Candidate can browse past interviews and reports. **This is the MVP.**

---

## Phase 6 — AI evaluation harness

**Files**
- `ai-evals/datasets/{answer-evaluation, injection, hallucination, question-generation, retrieval}.jsonl` (≥ 80 labelled evaluation cases total, with invariance pairs and 4-quality ordering sets)
- `ai-evals/baselines/{gemini,ollama}.json`, `ai-evals/README.md` (labelling guide)
- `backend/src/test/java/com/techinterviewer/evals/{EvalCase, EvalDatasetLoader, EvalMetrics, EvalReportWriter, BaselineComparator}.java`
- Eval suites: `AnswerEvaluationEvalTest`, `InjectionEvalTest`, `HallucinationEvalTest`, `QuestionGenerationEvalTest`, `RetrievalEvalTest`, `RagAblationEvalTest`, `StructuredOutputEvalTest`
- `backend/pom.xml` profile `ai-evals` (includes `@Tag("llm-eval")`, throttled)
- `.github/workflows/ai-evals.yml` (`workflow_dispatch` + PRs touching `prompts/**`, `schemas/**`, `ai/agents/**`)

**Functionality:** reproducible quality measurement against real models; Markdown + JSON reports; regression gate vs committed baseline.

**Tests:** unit tests for `EvalMetrics` (MAE, Spearman, band accuracy, invariance delta) and `BaselineComparator`.

**Acceptance criteria**
- `./mvnw verify -Pai-evals -Dai.provider=gemini` produces a report covering all categories in [AI_ARCHITECTURE.md §9.3](AI_ARCHITECTURE.md).
- Thresholds met on Gemini, or each gap documented with the prompt iteration plan.
- RAG ablation result recorded (evaluator error with vs without retrieval).
- Baseline committed; the workflow fails on regression.

---

## Phase 7 — Coding & system design rounds

**Files**
- `ai/agents/{CodingEvaluatorAgent, SystemDesignEvaluatorAgent}.java`, `ai/agents/output/{CodingEvaluation, DesignEvaluation, CodingProblem}.java`
- `resources/prompts/{coding-question,coding-evaluator,design-question,system-design-evaluator}/v1.md`, matching `schemas/*.json`, `rubrics/{coding,system-design}.yml`
- Engine updates: section transitions for `CODING`/`SYSTEM_DESIGN`, cumulative design-thread evaluation (every design answer is evaluated against the whole thread so far, one `evaluations` row per answer; the section score is the last answered turn's row, [AI_ARCHITECTURE.md §2.7](AI_ARCHITECTURE.md)), `FULL_LOOP` planning
- `knowledge-base/question-exemplars/{coding,system-design}/*.md`, `knowledge-base/system-design/*` expansion
- `ai-evals/datasets/{coding,system-design}.jsonl`
- Frontend: `features/interview/{CodeAnswerEditor, DesignAnswerEditor, SectionTransition}.tsx`

**Functionality:** coding problems with constraints/examples, CodeMirror editor, review-based coding evaluation (no execution) with example traces; system design prompts with RAG reference material, rubric evaluation, targeted follow-ups; full multi-section loop.

**Dependencies:** `@uiw/react-codemirror` + language packages.

**Tests:** state-machine section paths; `FullLoopFlowIT`; coding evaluator validation (language mismatch, empty code, oversized code); eval cases: correct-optimal, correct-suboptimal, off-by-one bug, correct-but-unexplained; design cases: strong, missing NFRs, no failure handling; thread cases: a follow-up answer that closes a gap raises that dimension's rating, a skipped follow-up keeps the previous rating, an all-skipped thread scores 0.

**Acceptance criteria**
- A 60-minute `FULL_LOOP` interview traverses all sections with correct timing.
- Coding eval set: buggy < suboptimal < optimal ordering holds; design evaluator separates strong vs weak designs per rubric dimension.

---

## Phase 8 — Résumé personalisation & AI observability UI

> **Résumé part built 2026-10-12** (AI_ARCHITECTURE.md §2.13, change log in ARCHITECTURE.md §10). Departures from the plan below: the résumé is not ingested through `IngestionService` and has no chunks or embeddings; it becomes a verified structured profile (`candidate_resumes`, V13) and the file and text are discarded. Personalisation is decided by the planner and engine (vocabulary links, quota weights, anchored questions), not by retrieval or claim probes. Package `resume` (not `user`); frontend `features/profile/ResumeCard.tsx`, setup option, résumé quote on questions. Tests as listed in §2.13. Acceptance: with a résumé whose experiences link to the interview's topics, at least one question is anchored (`ResumeInterviewIT`); without personalisation none is; deleting the résumé deletes everything kept of it and ends the personalisation. The observability UI (admin pages, payload capture) is still open.

**Files**
- `user/{CandidateProfile, CandidateProfileSkill, ProfileService, ProfileController}.java`, `user/dto/*`
- Résumé path through `IngestionService` (`RESUME`, `PRIVATE`); claim extraction feeding interview memory at `INIT`
- `admin/{AdminLlmCallController, AdminInterviewTraceController}.java`, `ai/observability/{AiMetricsService, PayloadRetentionJob}.java`
- Frontend: `features/profile/ProfilePage.tsx` (résumé upload/delete), `features/admin/{LlmCallsPage, InterviewTracePage, AiMetricsPage}.tsx`

**Functionality:** secure résumé upload; owner-scoped retrieval; questions that probe résumé claims when `useResume=true`; admin views for LLM calls, per-interview timelines, and aggregate AI metrics; payload retention.

**Tests:** upload security (magic bytes, size, encrypted PDF, page limit, path-traversal filename, control characters); résumé-embedded prompt-injection eval cases; cross-user isolation IT; cascade delete removes chunks and embeddings; retention job; admin endpoints → 403 for candidates.

**Acceptance criteria**
- With a résumé, at least one question per interview references a résumé claim; without one, none do.
- Deleting the résumé removes all derived data.
- An admin can open any interview and see every prompt version, model, token count, latency, and retrieval hit in order.

---

## Phase 9 — Hardening & deployment

**Files**
- `backend/Dockerfile` (multi-stage, layered jar, non-root user, CDS archive), `backend/src/main/resources/application-prod.yml`
- `frontend/public/_headers` (CSP, security headers for Cloudflare Pages)
- `.github/workflows/deploy.yml` (build → push image → deploy Cloud Run via Workload Identity Federation; Pages deploy)
- Per-user daily token budget and interview limits (`security/UsageBudgetService.java`)
- `e2e/` Playwright smoke test (local run against a real provider)
- README: local setup, deployment runbook (Cloud Run flags, Neon setup with `CREATE EXTENSION vector`, secrets), cost notes

**Functionality:** production deployment on Cloudflare Pages + Cloud Run + Neon + Gemini; secrets via Secret Manager; budgets enforced; structured logs.

**Tests:** prod-profile container boots against Testcontainers DB; security headers present; budgets return 429 `TOKEN_BUDGET_EXCEEDED`; Playwright: register → configure → complete short interview → view report.

**Acceptance criteria**
- Public URL works end-to-end; cold-start time measured and documented.
- No secrets in the repo or image; Actuator locked down; Swagger disabled in prod.
- Monthly cost at demo volume within free tiers (Gemini usage aside).
- A reviewer can run the project locally from the README in under 10 minutes.

---

## Global definition of done (every phase)
- Unit + integration tests for new code; CI green.
- No placeholder or fake production implementations; test doubles live only under `src/test`.
- Prompt or schema changes bump the prompt version and are followed by an AI-eval run (from Phase 6 on).
- Docs updated when a decision changes (ADR table in [ARCHITECTURE.md §9](ARCHITECTURE.md)).

---

## Frontend (delivered 2026-10-11, the screens of Phases 4, 5 and 7)

All pages talk to the real backend through the generated types; no response is faked outside `src/test`. Departures from the plan above are in ARCHITECTURE.md section 10 (2026-10-11): no Zustand and no React Hook Form/Zod, hand-written SVG charts, a progressive reveal instead of a stream (the backend has none), lazy CodeMirror.

| Page | Route | Notes |
|---|---|---|
| Landing | `/` | live technology list from the catalog |
| Sign in, Sign up | `/login`, `/register` | access token in memory, single-flight refresh (also across tabs through the Web Locks API) |
| Dashboard | `/dashboard` | scores, trend, technology performance, strengths, weaknesses, recurring weak topics; reads the interview list and up to 12 reports (no aggregate endpoint exists) |
| Interview setup | `/interviews/new` | options from the catalog; reuse of recent setups |
| Live interview | `/interviews/:id/live` | briefing, question, thinking state, retry, skip, end, drafts kept in the browser, server-side clock |
| Coding interview | `/interviews/:id/coding` | CodeMirror editor (lazy), constraints, examples; code is read by the AI, not run |
| System design | `/interviews/:id/design` | long-form answer with an optional outline |
| Results | `/interviews/:id/results` | verdict, section and topic scores, AI feedback, strengths and weaknesses with evidence, knowledge gaps, study plan, integrity notes, timeline |
| History, Profile | `/interviews`, `/profile` | paged and filtered through the backend; profile replaces through `PUT` |

**Verification:** 240 Vitest tests (API session layer, every page, the room's flows against a fake backend, accessibility semantics), typecheck, ESLint (strict type-checked), Prettier and the production build; the pages were also driven in a real browser against the running backend (Ollama profile) and checked in light, dark and phone widths.

---

## Coding interview module (delivered 2026-10-11, Phase 7 with real execution)

Design and limits: [AI_ARCHITECTURE.md §2.11](AI_ARCHITECTURE.md); change log row of 2026-10-11 in ARCHITECTURE.md §10.

| Delivered | Where |
|---|---|
| Curated problem bank: 13 problems, levels 1 to 5, hidden tests by category, key points, reference complexity; validated at startup | `coding/problem`, `resources/coding-problems/` |
| Docker sandbox: one container per run, no network, read-only root, unprivileged, resource, time and output limits; tar over stdin, never a mount | `coding/sandbox` |
| Harnesses for Java, JavaScript and TypeScript; grading on the server (expected values never enter the container) | `coding/testing`, `resources/coding-harness/` |
| "Run examples" endpoint, submission tests before evaluation (`code_test_runs`, V11) | `interview/api/CodeRunController`, `coding/service` |
| Rubric `coding.v2`, tests set correctness and cap edge cases and time complexity, unverified code capped, server-written score notes, complexity analysis | `evaluation`, prompt `answer-evaluation-coding/v4` |
| Frontend: run panel, testable notice, report with tests, rating basis and complexity | `frontend/src/features/interview`, `features/results` |

**Verification:** every bank expected output checked in-process against Java references in every build; `CodeSandboxIT` runs 39 reference solutions (13 problems × 3 languages) and the containment checks against real Docker; `CodingInterviewIT` covers the HTTP flow; the whole flow was driven in a browser against the real backend (Ollama profile, execution on). Not done: gVisor or a separate runner host (documented as required for a public deployment), Python and Go execution, a live measurement of the coding evaluator on a labelled set.


## System design interview mode (delivered 2026-10-11, Phase 7 system design round)

Design and limits: [AI_ARCHITECTURE.md §2.12](AI_ARCHITECTURE.md); change log row of 2026-10-11 in ARCHITECTURE.md §10.

| Delivered | Where |
|---|---|
| Curated design problems: 9 products, levels 1 to 5 (two or more per level), facts given up front, key points, per-dimension probe scenarios; validated at startup | `design/`, `resources/design-problems/` |
| Rubric `system-design.v2`: 13 dimensions (requirements, architecture, API, data model, caching, queues, scalability, availability, reliability, failure handling, security, observability, trade-offs) | `evaluation/RubricRegistry` |
| Ratings rest on verified quotes (`DESIGN_EVIDENCE`), mistakes are the candidate's words and cap their own dimension (`FACTUAL_ERRORS`) | `evaluation/RatingRules`, `AssessmentEvidence`, `ai/agents/AnswerEvaluatorAgent`, prompt `answer-evaluation-system-design/v4` |
| Dimension probes chosen by the engine (largest weighted shortfall not probed yet), worded by the model, static scenario as fallback; up to 4 probes (`app.interview.max-design-probes`) | `interview/engine/DesignProbePolicy`, `TurnDecider`, prompt `design-probe-question/v1` |
| Architecture diagram: structured, validated, stored with the answer (V12), given to the evaluator and the probe writer | `interview/domain/ArchitectureDiagram`, `answers.architecture` |
| Frontend: problem facts, design checklist, write and diagram tabs, SVG diagram, probe badge, design scorecard and diagrams in the report | `frontend/src/features/design`, `features/interview/DesignWorkspace`, `features/results/DesignScorecard` |
| Labelled design set and live test | `ai-evals/datasets/design-evaluation.jsonl`, `DesignEvaluationLiveTest` |

**Verification:** unit tests for the bank, the diagram, the probe policy and the rules; `SystemDesignInterviewIT` drives the whole flow over HTTP (bank problem, diagram, successive probes past depth 2, static fallback, validation, transcript); `TurnSimulationIT` keeps every invariant with design probes; frontend tests for the editor, the workspace and the scorecard; the flow was driven in a browser against the real backend (Ollama profile). The design evaluator was measured on the local model only (4 of 7 cases meet every expectation; see ai-evals/ANSWER_EVALUATION.md). Not done: a Gemini measurement, clarifying questions as a dialogue, free-form diagram layout.
