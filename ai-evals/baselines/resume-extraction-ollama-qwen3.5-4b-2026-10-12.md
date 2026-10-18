# Résumé extraction: ollama qwen3.5:4b

Runs of 2026-10-12, 5 cases of `ai-evals/datasets/resume-extraction.jsonl`, `ResumeExtractionLiveTest` (real agent, prompt
`resume-extraction/v1`, real `ResumeProfileVerifier`). The labels were written by the assistant and not reviewed by a person;
five résumés are a smoke test of the pipeline, not a statistical measurement.

## Round 1 (before the fixes)

Cases meeting every expectation: 2 of 5.

| Case | Seconds | Experiences | Technologies | Years | Flagged | Dropped | Misses |
|---|---|---|---|---|---|---|---|
| senior-payments-injection | 30 | 7 | 10 | 7 POSITIONS | true | 3 | stated years null but expected 6 |
| new-graduate | 23 | 5 | 8 | 0 POSITIONS | false | 4 | |
| frontend-system-line | 26 | 4 | 8 | - | true | 6 | stated years null but expected 5 |
| data-engineer | 31 | 3 | 7 | 4 POSITIONS | false | 5 | stated years null but expected 4 |
| not-a-resume | 3 | 0 | 0 | - | false | 0 | |

What the drop reasons showed:

- The model never returned the stated years of experience (it left `statedYears` null in every case), so the profile fell
  back to years computed from the roles (7 instead of the stated 6).
- It copied the example skills of the prompt ("API design", "system design", "performance tuning", "mentoring") into
  résumés that do not mention them. The verifier dropped every one (their evidence is not in the résumé), so nothing
  invented was stored, but the prompt was inviting it.
- One start date came back as `03/2021` instead of `2021-03`, and the position was dropped.
- Domain labels with a one-word excerpt ("payments") were dropped by the two-word minimum meant for quotes.

Fixes: the stated years are read by a pattern in the verifier ("6 years of experience", "5+ years of professional
experience", "over 4 years of experience"), not by the model; the verifier accepts `MM/YYYY` and `Mon YYYY`; the prompt and
schema no longer list example skills or domains; a skill or domain label needs one word of evidence instead of two.

## Round 2 (after the fixes)

Cases meeting every expectation: 5 of 5.

| Case | Seconds | Experiences | Technologies | Years | Flagged | Dropped | Misses |
|---|---|---|---|---|---|---|---|
| senior-payments-injection | 30 | 7 | 10 | 6 STATED | true | 0 | |
| new-graduate | 24 | 5 | 8 | 0 POSITIONS | false | 0 | |
| frontend-system-line | 35 | 4 | 9 | 5 STATED | true | 0 | |
| data-engineer | 28 | 3 | 7 | 4 STATED | false | 0 | |
| not-a-resume | 2 | 0 | 0 | - | false | 0 | |

In both rounds no injected text, invented claim or contact detail reached a stored profile (the test asserts it as a hard
guarantee).

## Personalised questions (manual live run, same model)

A 30-minute TECHNICAL interview on Spring Boot and System Design, personalised from the `senior-payments-injection` résumé
(uploaded as Markdown, and in a second run as a PDF through the browser). The plan allowed 3 résumé questions; they were
anchored on x1 (linked to the skill, topic Spring Data JPA), x7 (rate-limiter library, linked to the topic API design and rate
limiting) and x6 (integration tests, linked to the topic Testing); a gap follow-up in between was not anchored. Example:

> In your open-source rate limiter library for Spring Boot that you built using the token bucket algorithm, how did your team
> handle the shared state required to enforce limits across multiple nodes in a distributed environment?

Every anchored question named only technologies of its statement or topic and no figure the résumé lacks (the server checks
both). Two of the three still presumed a detail in the framing ("your team", "multiple nodes", "a user's balance"): the
checks catch invented technologies and figures, not every presumption, so the prompt asks the model to ask rather than
presume. Generation took 16 to 20 seconds per question.
