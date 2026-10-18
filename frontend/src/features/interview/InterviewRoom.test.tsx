import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import type { AnswerResult, Question, Session } from '../../api/types';
import {
  designEntries,
  INTERVIEW_ID,
  question,
  report,
  session,
  SHORTENER_DIAGRAM,
  transcript,
  TOKEN,
} from '../../test/fixtures';
import { API, problem } from '../../test/handlers';
import { must } from '../../test/must';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';
import { WRAP_UP_POLL_MS } from './WrapUp';

// CodeMirror needs a real layout engine; the page's own behaviour is what these tests are about.
vi.mock('./CodeEditor', () => ({
  default: ({
    value,
    onChange,
    ariaLabel,
    readOnly,
  }: {
    value: string;
    onChange: (value: string) => void;
    ariaLabel: string;
    readOnly: boolean;
  }) => (
    <textarea
      aria-label={ariaLabel}
      value={value}
      readOnly={readOnly}
      onChange={(event) => {
        onChange(event.target.value);
      }}
    />
  ),
}));

const BASE = `${API}/interviews/${INTERVIEW_ID}`;

/** A stand-in for the server: the room only ever renders what GET /interviews/{id} last said. */
function fakeBackend(initial: Session) {
  const state = { current: initial };
  server.use(
    http.get(BASE, () => HttpResponse.json(state.current)),
    http.get(`${BASE}/transcript`, () =>
      HttpResponse.json({ ...transcript([]), state: state.current.state }),
    ),
  );
  return state;
}

function turn(overrides: Partial<AnswerResult>): AnswerResult {
  return {
    accepted: true,
    state: 'QUESTIONING',
    progress: session().progress,
    nextQuestion: null,
    awaitingNextQuestion: false,
    interviewComplete: false,
    ...overrides,
  };
}

async function write(
  user: ReturnType<typeof userEvent.setup>,
  label: RegExp | string,
  text: string,
) {
  const box = await screen.findByLabelText(label);
  await user.click(box);
  await user.paste(text);
}

describe('InterviewRoom: an interview in progress', () => {
  it('shows the open question with topic, state, progress and the server clock', async () => {
    fakeBackend(session());
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(await screen.findByText(/why is/i)).toBeInTheDocument();
    expect(screen.getByText(/Java \/ Concurrency · about 5 min/)).toBeInTheDocument();
    expect(screen.getByRole('timer', { name: 'Time remaining' })).toHaveTextContent(
      /^(20:0[01]|19:5\d)$/,
    );
    expect(screen.getByText('Interview state:')).toBeInTheDocument();
    expect(screen.getByText('Questions in this section')).toBeInTheDocument();
    expect(
      screen.getByRole('progressbar', { name: 'Questions answered in this section' }),
    ).toHaveAttribute('aria-valuenow', '33');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/Technical Q&A interview/);
    // A question that was already open when the page loaded is restored, not streamed in.
    expect(screen.queryByRole('button', { name: 'Show full question' })).not.toBeInTheDocument();
  });

  it('sends the answer, shows that the AI is working, and then the interviewer follow-up', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let body: unknown;
    server.use(
      http.post(`${BASE}/answers`, async ({ request }) => {
        body = await request.json();
        await gate;
        const followUp = question({
          id: 'q-2',
          sequenceNo: 2,
          isFollowUp: true,
          kind: 'FOLLOW_UP',
          prompt: 'What would make the increment atomic?',
        });
        backend.current = session({ state: 'FOLLOW_UP', currentQuestion: followUp, version: 5 });
        return HttpResponse.json(turn({ state: 'FOLLOW_UP', nextQuestion: followUp }));
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'volatile only gives visibility');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    expect(await screen.findByText('The interviewer is reviewing your answer')).toBeInTheDocument();
    expect(screen.getByLabelText('Your answer')).toHaveAttribute('readonly');
    expect(body).toMatchObject({
      questionId: 'q-1',
      content: 'volatile only gives visibility',
      codeLanguage: null,
    });
    expect((body as { timeTakenSeconds: number }).timeTakenSeconds).toBeGreaterThanOrEqual(0);

    release();

    // The follow-up arrives; it is revealed progressively and can be shown in full at once.
    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('What would make the increment atomic?')).toBeInTheDocument();
    expect(screen.getByText('Interviewer · Question 2')).toBeInTheDocument();
    expect(within(screen.getByRole('article')).getByText('Follow-up')).toBeInTheDocument();
    expect(screen.getByLabelText('Your answer')).toHaveValue('');
    expect(screen.queryByText('The interviewer is reviewing your answer')).not.toBeInTheDocument();
  });

  it('keeps the draft and offers a retry when the answer could not be sent', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    let attempts = 0;
    server.use(
      http.post(`${BASE}/answers`, () => {
        attempts += 1;
        if (attempts === 1) {
          return problem(500, 'INTERNAL_ERROR');
        }
        const next = question({ id: 'q-2', sequenceNo: 2, prompt: 'Second question' });
        backend.current = session({ currentQuestion: next, version: 5 });
        return HttpResponse.json(turn({ nextQuestion: next }));
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'my careful answer');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The server had a problem');
    expect(screen.getByLabelText('Your answer')).toHaveValue('my careful answer');

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('Second question')).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it('moves on when the connection drops after the server processed the answer', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    server.use(
      http.post(`${BASE}/answers`, () => {
        const next = question({ id: 'q-2', sequenceNo: 2, prompt: 'Second question' });
        backend.current = session({ currentQuestion: next, version: 5 });
        return HttpResponse.error();
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'my careful answer');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    expect(await screen.findByText('Interviewer · Question 2')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('refuses a blank answer and one that is too long', async () => {
    const user = userEvent.setup();
    fakeBackend(session());
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    const submit = await screen.findByRole('button', { name: /submit answer/i });
    expect(submit).toBeDisabled();

    await write(user, 'Your answer', '   ');
    expect(submit).toBeDisabled();

    await user.clear(screen.getByLabelText('Your answer'));
    await user.click(screen.getByLabelText('Your answer'));
    await user.paste('x'.repeat(8001));
    expect(submit).toBeDisabled();
    expect(screen.getByText(/8,001 \/ 8,000 characters · too long/)).toBeInTheDocument();
  });

  it('restores a saved draft after a reload, and drops it once the answer is sent', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    localStorage.setItem('ti.draft.q-1', 'half-written thought');
    server.use(
      http.post(`${BASE}/answers`, () => {
        backend.current = session({
          currentQuestion: null,
          awaitingNextQuestion: true,
          version: 5,
        });
        return HttpResponse.json(turn({ awaitingNextQuestion: true }));
      }),
      http.post(`${BASE}/questions/next`, () => new Promise(() => undefined)),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(await screen.findByLabelText('Your answer')).toHaveValue('half-written thought');

    await user.click(screen.getByRole('button', { name: /submit answer/i }));
    await screen.findByText('Preparing your next question');

    expect(localStorage.getItem('ti.draft.q-1')).toBeNull();
  });

  it('asks for the next question by itself when the server says it is awaited, and recovers from a failure', async () => {
    const user = userEvent.setup();
    fakeBackend(session({ currentQuestion: null, awaitingNextQuestion: true }));
    let calls = 0;
    server.use(
      http.post(`${BASE}/questions/next`, () => {
        calls += 1;
        return calls === 1
          ? problem(503, 'AI_PROVIDER_UNAVAILABLE')
          : HttpResponse.json(
              session({
                currentQuestion: question({ id: 'q-5', prompt: 'A fresh question' }),
                version: 6,
              }),
            );
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The next question could not be prepared');
    expect(calls).toBe(1);

    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('A fresh question')).toBeInTheDocument();
  });

  it('confirms before skipping, and sends the skip', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    let skipped: unknown;
    server.use(
      http.post(`${BASE}/questions/q-1/skip`, async ({ request }) => {
        skipped = await request.json();
        backend.current = session({
          currentQuestion: question({ id: 'q-2', prompt: 'After the skip' }),
          version: 5,
        });
        return HttpResponse.json(turn({ nextQuestion: backend.current.currentQuestion }));
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Skip' }));
    const dialog = await screen.findByRole('dialog', { name: 'Skip this question?' });
    expect(dialog).toHaveTextContent(/counts as zero/);

    await user.click(within(dialog).getByRole('button', { name: 'Skip question' }));

    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('After the skip')).toBeInTheDocument();
    expect(skipped).toMatchObject({ timeTakenSeconds: expect.any(Number) as number });
  });

  it('does not let the interview be ended while an answer is being evaluated', async () => {
    const user = userEvent.setup();
    fakeBackend(session());
    server.use(http.post(`${BASE}/answers`, () => new Promise(() => undefined)));
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'an answer');
    expect(await screen.findByRole('button', { name: 'End interview' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    await screen.findByText('The interviewer is reviewing your answer');
    expect(screen.getByRole('button', { name: 'End interview' })).toBeDisabled();
  });

  it('says a skipped follow-up adds nothing', async () => {
    const user = userEvent.setup();
    fakeBackend(
      session({
        state: 'FOLLOW_UP',
        currentQuestion: question({ isFollowUp: true, kind: 'FOLLOW_UP' }),
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Skip' }));

    expect(await screen.findByRole('dialog')).toHaveTextContent(/adds nothing/);
  });

  it('ends the interview on request and goes on to the report', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    let reason: unknown;
    server.use(
      http.post(`${BASE}/finish`, async ({ request }) => {
        reason = await request.json();
        backend.current = session({ state: 'COMPLETED', currentQuestion: null });
        return HttpResponse.json({ state: 'COMPLETED', reportStatus: 'READY', retryable: false });
      }),
      http.get(`${BASE}/report`, () => HttpResponse.json(report())),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'End interview' }));
    await user.click(await screen.findByRole('button', { name: 'End and get my report' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Interview results' }),
    ).toBeInTheDocument();
    expect(reason).toEqual({ reason: 'CANDIDATE_ENDED' });
  });

  it('writes the report and shows it when the plan is complete', async () => {
    fakeBackend(
      session({ state: 'FINAL_EVALUATION', currentQuestion: null, interviewComplete: true }),
    );
    let reason: unknown;
    server.use(
      http.post(`${BASE}/finish`, async ({ request }) => {
        reason = await request.json();
        return HttpResponse.json({ state: 'COMPLETED', reportStatus: 'READY', retryable: false });
      }),
      http.get(`${BASE}/report`, () => HttpResponse.json(report())),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(await screen.findByRole('heading', { name: 'Writing your report' })).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Interview results' }),
    ).toBeInTheDocument();
    expect(reason).toEqual({ reason: 'ACKNOWLEDGE_COMPLETE' });
  });

  it('shows the results when the report is finished although the reply to finishing was lost', async () => {
    const backend = fakeBackend(
      session({ state: 'FINAL_EVALUATION', currentQuestion: null, interviewComplete: true }),
    );
    server.use(
      http.post(`${BASE}/finish`, () => {
        // The reply is lost; the server completes the report a little later.
        setTimeout(() => {
          backend.current = session({
            state: 'COMPLETED',
            currentQuestion: null,
            interviewComplete: true,
          });
        }, 300);
        return HttpResponse.error();
      }),
      http.get(`${BASE}/report`, () => HttpResponse.json(report())),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(
      await screen.findByText(
        /Your report is still being written/,
        {},
        { timeout: WRAP_UP_POLL_MS + 3000 },
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Interview results' },
        { timeout: WRAP_UP_POLL_MS + 3000 },
      ),
    ).toBeInTheDocument();
  });

  it('lets the candidate retry when finishing failed', async () => {
    const user = userEvent.setup();
    fakeBackend(
      session({ state: 'FINAL_EVALUATION', currentQuestion: null, interviewComplete: true }),
    );
    let calls = 0;
    server.use(
      http.post(`${BASE}/finish`, () => {
        calls += 1;
        return calls === 1
          ? problem(503, 'AI_PROVIDER_UNAVAILABLE')
          : HttpResponse.json({ state: 'COMPLETED', reportStatus: 'READY', retryable: false });
      }),
      http.get(`${BASE}/report`, () => HttpResponse.json(report())),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Your report could not be finished');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Interview results' }),
    ).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  it('reloads the interview when the server says the question is no longer the current one', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    server.use(
      http.post(`${BASE}/answers`, () => {
        backend.current = session({
          currentQuestion: question({ id: 'q-9', prompt: 'The interview moved on' }),
          version: 9,
        });
        return problem(409, 'QUESTION_NOT_CURRENT', { currentState: 'QUESTIONING' });
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'late answer');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    expect(await screen.findByText('The interview moved on')).toBeInTheDocument();
  });

  it('shows an error with a retry when the interview cannot be loaded', async () => {
    server.use(http.get(BASE, () => problem(404, 'NOT_FOUND')));
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('Not found');
    expect(screen.getByRole('link', { name: 'Back to your dashboard' })).toBeInTheDocument();
  });
});

describe('InterviewRoom: before and after', () => {
  it('shows a briefing for an interview that has not started, and starts it on request', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(
      session({ state: 'INIT', currentQuestion: null, startedAt: null, deadlineAt: null }),
    );
    server.use(
      http.post(`${BASE}/start`, () => {
        const intro = question({
          id: 'q-intro',
          kind: 'INTRO',
          section: 'INTRODUCTION',
          topic: null,
          prompt: 'Please introduce yourself.',
        });
        backend.current = session({ state: 'INTRODUCTION', currentQuestion: intro });
        return HttpResponse.json(backend.current);
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Technical Q&A interview' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/runs on the server/)).toBeInTheDocument();
    expect(screen.getByText('Java')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Begin interview' }));

    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('Please introduce yourself.')).toBeInTheDocument();
    expect(screen.getByRole('timer', { name: 'Time remaining' })).toHaveTextContent(/\d\d:\d\d/);
  });

  it('sends a completed interview to its results', async () => {
    fakeBackend(session({ state: 'COMPLETED', currentQuestion: null }));
    server.use(http.get(`${BASE}/report`, () => HttpResponse.json(report())));
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Interview results' }),
    ).toBeInTheDocument();
  });

  it('explains an abandoned interview', async () => {
    fakeBackend(session({ state: 'ABANDONED', currentQuestion: null }));
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(
      await screen.findByRole('heading', { name: 'This interview was abandoned' }),
    ).toBeInTheDocument();
  });
});

describe('InterviewRoom: sections have their own pages', () => {
  const codingQuestion: Question = question({
    id: 'q-code',
    kind: 'CODING',
    section: 'CODING',
    topic: { skill: 'java', name: 'Java / Collections' },
    prompt: 'Return the two indices whose values add up to the target.',
    answerFormat: 'CODE',
    details: {
      language: 'java',
      constraints: ['2 <= nums.length <= 10^4'],
      examples: [
        { input: 'nums = [2,7,11,15], target = 9', output: '[0,1]', explanation: '2 + 7 = 9' },
      ],
      starterCode: 'class Solution {\n}',
      title: 'Two Sum',
      testable: true,
    },
  });

  it('moves a coding question from the live page to the coding page', async () => {
    fakeBackend(session({ state: 'CODING', currentQuestion: codingQuestion }));
    const { router } = renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(await screen.findByRole('heading', { name: 'Constraints' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/interviews/${INTERVIEW_ID}/coding`);
    expect(screen.getByText('2 <= nums.length <= 10^4')).toBeInTheDocument();
    expect(screen.getByText('[0,1]')).toBeInTheDocument();
    expect(screen.getByLabelText('Your solution')).toHaveValue('class Solution {\n}');
    expect(screen.getByRole('heading', { name: 'Two Sum' })).toBeInTheDocument();
    expect(screen.getByText(/hidden tests/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /run examples/i })).toBeEnabled();
  });

  it('submits code together with the language, and not as a text answer', async () => {
    const user = userEvent.setup();
    fakeBackend(session({ state: 'CODING', currentQuestion: codingQuestion }));
    let body: unknown;
    server.use(
      http.post(`${BASE}/answers`, async ({ request }) => {
        body = await request.json();
        return new Promise(() => undefined);
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    await user.click(await screen.findByLabelText('Your solution'));
    await user.paste(' // done');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    expect(
      await screen.findByText('Running the tests and reviewing your code'),
    ).toBeInTheDocument();
    expect(body).toMatchObject({
      questionId: 'q-code',
      codeLanguage: 'java',
      content: 'class Solution {\n} // done',
    });
  });

  it('runs the visible examples of a draft and shows what each one returned', async () => {
    const user = userEvent.setup();
    fakeBackend(session({ state: 'CODING', currentQuestion: codingQuestion }));
    let body: unknown;
    server.use(
      http.post(`${BASE}/questions/q-code/runs`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          status: 'COMPLETED',
          passed: 1,
          total: 2,
          durationMs: 1830,
          compileOutput: null,
          output: 'debug line',
          results: [
            {
              name: 'Example 1',
              category: 'EXAMPLE',
              status: 'PASSED',
              input: 'nums = [2,7,11,15], target = 9',
              expected: '[0,1]',
              actual: '[0,1]',
              message: null,
              timeMs: 3,
            },
            {
              name: 'Example 2',
              category: 'EXAMPLE',
              status: 'FAILED',
              input: 'nums = [3,2,4], target = 6',
              expected: '[1,2]',
              actual: '[0,0]',
              message: 'Wrong answer.',
              timeMs: 1,
            },
          ],
        });
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: /run examples/i }));

    const results = await screen.findByRole('region', { name: 'Example results' });
    expect(within(results).getByText('1 of 2 examples passed')).toBeInTheDocument();
    expect(within(results).getByText('Wrong answer', { selector: 'span' })).toBeInTheDocument();
    expect(within(results).getByText('[0,0]')).toHaveClass('text-danger-ink');
    expect(within(results).getByText('What your code printed')).toBeInTheDocument();
    expect(body).toEqual({ code: 'class Solution {\n}', language: 'java' });
    expect(screen.getByLabelText('Your solution')).toHaveValue('class Solution {\n}');
  });

  it('shows a compilation error of a draft', async () => {
    const user = userEvent.setup();
    fakeBackend(session({ state: 'CODING', currentQuestion: codingQuestion }));
    server.use(
      http.post(`${BASE}/questions/q-code/runs`, () =>
        HttpResponse.json({
          status: 'COMPILATION_ERROR',
          passed: 0,
          total: 2,
          durationMs: 900,
          compileOutput: "Solution.java:2: error: ';' expected",
          output: '',
          results: [],
        }),
      ),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: /run examples/i }));

    expect(await screen.findByText('The code did not compile.')).toBeInTheDocument();
    expect(screen.getByText("Solution.java:2: error: ';' expected")).toBeInTheDocument();
  });

  it('explains when examples cannot be run right now', async () => {
    const user = userEvent.setup();
    fakeBackend(session({ state: 'CODING', currentQuestion: codingQuestion }));
    server.use(
      http.post(`${BASE}/questions/q-code/runs`, () => problem(503, 'CODE_EXECUTION_UNAVAILABLE')),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: /run examples/i }));

    expect(await screen.findByText(/The examples could not be run/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /submit answer/i })).toBeEnabled();
  });

  it('says plainly when a solution is not run and offers no Run button', async () => {
    fakeBackend(
      session({
        state: 'CODING',
        currentQuestion: question({
          ...codingQuestion,
          details: { ...must(codingQuestion.details), testable: false, title: null },
        }),
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    expect(await screen.findByText(/This solution is not run here/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /run examples/i })).not.toBeInTheDocument();
  });

  it('shows a system design question on its own page with an optional outline', async () => {
    const user = userEvent.setup();
    fakeBackend(
      session({
        state: 'SYSTEM_DESIGN',
        currentQuestion: question({
          id: 'q-design',
          kind: 'SYSTEM_DESIGN',
          section: 'SYSTEM_DESIGN',
          topic: { skill: 'system-design', name: 'System design' },
          prompt: 'Design a URL shortener.',
        }),
      }),
    );
    const { router } = renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    expect(await screen.findByText('Design a URL shortener.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/interviews/${INTERVIEW_ID}/design`);

    await user.click(screen.getByRole('button', { name: /start from an outline/i }));

    expect(screen.getByLabelText<HTMLTextAreaElement>('Your design').value).toContain(
      '## Architecture and request flow',
    );
    expect(screen.getByRole('button', { name: /start from an outline/i })).toBeDisabled();
  });

  it('shows the facts of a design problem, builds a diagram and sends it with the answer', async () => {
    const user = userEvent.setup();
    fakeBackend(
      session({
        state: 'SYSTEM_DESIGN',
        currentQuestion: question({
          id: 'q-design',
          kind: 'SYSTEM_DESIGN',
          section: 'SYSTEM_DESIGN',
          topic: null,
          prompt: 'Design a service like bit.ly.',
          design: { title: 'Design a URL shortener', facts: ['100 million new links per month'] },
        }),
      }),
    );
    let body: unknown;
    server.use(
      http.post(`${BASE}/answers`, async ({ request }) => {
        body = await request.json();
        return new Promise(() => undefined);
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/design`, { signedIn: true });

    expect(
      await screen.findByRole('heading', { name: 'Design a URL shortener' }),
    ).toBeInTheDocument();
    expect(screen.getByText('100 million new links per month')).toBeInTheDocument();
    expect(screen.getByText('Failure handling')).toBeInTheDocument();
    await write(user, 'Your design', 'A redirect service and a links store.');

    await user.click(screen.getByRole('tab', { name: /architecture diagram/i }));
    await user.click(screen.getByRole('button', { name: /start from client, load balancer/i }));
    await user.selectOptions(screen.getByLabelText('Kind'), 'CACHE');
    await user.type(screen.getByLabelText('Name'), 'Link cache');
    await user.click(screen.getByRole('button', { name: 'Add component' }));
    await user.selectOptions(screen.getByLabelText('From'), 'api');
    await user.selectOptions(screen.getByLabelText('To'), 'link-cache');
    await user.type(screen.getByLabelText('What flows (optional)'), 'read-through');
    await user.click(screen.getByRole('button', { name: 'Add connection' }));

    expect(screen.getByRole('img', { name: /5 components/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove Primary database' }));
    expect(screen.getByRole('img', { name: /4 components/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    await waitFor(() => {
      expect(body).toBeDefined();
    });
    expect(body).toMatchObject({
      questionId: 'q-design',
      content: 'A redirect service and a links store.',
      architecture: {
        components: [
          { id: 'clients', kind: 'CLIENT' },
          { id: 'lb', kind: 'LOAD_BALANCER' },
          { id: 'api', kind: 'SERVICE' },
          { id: 'link-cache', kind: 'CACHE', name: 'Link cache' },
        ],
        connections: [
          { from: 'clients', to: 'lb' },
          { from: 'lb', to: 'api' },
          { from: 'api', to: 'link-cache', label: 'read-through', async: false },
        ],
      },
    });
  });

  it('marks a probe with its dimension and carries the diagram sent earlier into the answer', async () => {
    const user = userEvent.setup();
    const [problem, probe] = designEntries();
    const backend = fakeBackend(
      session({ state: 'FOLLOW_UP', currentQuestion: must(probe).question }),
    );
    server.use(
      http.get(`${BASE}/transcript`, () =>
        HttpResponse.json({ ...transcript([must(problem)]), state: backend.current.state }),
      ),
    );
    let body: unknown;
    server.use(
      http.post(`${BASE}/answers`, async ({ request }) => {
        body = await request.json();
        return new Promise(() => undefined);
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/design`, { signedIn: true });

    expect(await screen.findByText('Probing: Caching')).toBeInTheDocument();
    expect(screen.getByText('asked now')).toBeInTheDocument();
    expect(
      await screen.findByRole('tab', { name: /architecture diagram.*2/i }),
    ).toBeInTheDocument();
    await write(user, 'Your answer', 'Redis in front of the store.');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    await waitFor(() => {
      expect(body).toMatchObject({ architecture: SHORTENER_DIAGRAM });
    });
  });

  it('keeps a text question on the live page and moves back from a section page', async () => {
    fakeBackend(session());
    const { router } = renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    expect(await screen.findByLabelText('Your answer')).toBeInTheDocument();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/interviews/${INTERVIEW_ID}/live`);
    });
  });
});

describe('InterviewRoom: recovering from a failed command', () => {
  it('retries a failed skip as a skip, never as an answer', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    let skips = 0;
    let answers = 0;
    server.use(
      http.post(`${BASE}/questions/q-1/skip`, () => {
        skips += 1;
        if (skips === 1) {
          return problem(500, 'INTERNAL_ERROR');
        }
        backend.current = session({
          currentQuestion: question({ id: 'q-2', prompt: 'After the skip' }),
          version: 5,
        });
        return HttpResponse.json(turn({ nextQuestion: backend.current.currentQuestion }));
      }),
      http.post(`${BASE}/answers`, () => {
        answers += 1;
        return problem(500, 'INTERNAL_ERROR');
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Skip' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Skip question' }),
    );
    const alert = await screen.findByRole('alert');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('After the skip')).toBeInTheDocument();
    expect(skips).toBe(2);
    expect(answers).toBe(0);
  });

  it('does not carry the error of one question over to the next', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    server.use(
      http.post(`${BASE}/answers`, () => problem(500, 'INTERNAL_ERROR')),
      http.post(`${BASE}/questions/q-1/skip`, () => {
        backend.current = session({
          currentQuestion: question({ id: 'q-2', prompt: 'A clean slate' }),
          version: 5,
        });
        return HttpResponse.json(turn({ nextQuestion: backend.current.currentQuestion }));
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'an answer that will not arrive');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem');

    await user.click(screen.getByRole('button', { name: 'Skip' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Skip question' }),
    );
    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('A clean slate')).toBeInTheDocument();

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the clock anchored to the server time while a turn result is merged in', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    server.use(
      http.post(`${BASE}/answers`, () => {
        backend.current = session({
          currentQuestion: question({ id: 'q-2', prompt: 'Next one' }),
          version: 5,
        });
        return HttpResponse.json(turn({ nextQuestion: backend.current.currentQuestion }));
      }),
    );
    const { queryClient } = renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });
    await write(user, 'Your answer', 'an answer');
    const key = ['interviews', INTERVIEW_ID, 'session'];
    const anchoredAt = queryClient.getQueryState(key)?.dataUpdatedAt;
    // Hold back the session reload that follows the turn, so only the merged turn result is in the cache.
    server.use(http.get(BASE, () => new Promise(() => undefined)));

    await user.click(screen.getByRole('button', { name: /submit answer/i }));
    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    await screen.findByText('Next one');

    // The cached session still carries the server time of the response it came from; so must its timestamp.
    expect(queryClient.getQueryState(key)?.dataUpdatedAt).toBe(anchoredAt);
  });
});

describe('InterviewRoom: a coding follow-up', () => {
  const codingProblem: Question = question({
    id: 'q-code',
    kind: 'CODING',
    section: 'CODING',
    prompt: 'Return the two indices whose values add up to the target.',
    answerFormat: 'CODE',
    details: {
      language: 'java',
      constraints: [],
      examples: [],
      starterCode: 'class Solution {\n}',
      title: 'Two Sum',
      testable: false,
    },
  });
  const followUp: Question = question({
    id: 'q-follow',
    kind: 'FOLLOW_UP',
    section: 'CODING',
    isFollowUp: true,
    prompt: 'How would your solution change if the input were sorted?',
    answerFormat: 'CODE',
    details: null,
  });
  const submitted = 'class Solution {\n  int[] twoSum(int[] nums, int target) { return null; }\n}';

  it('starts from the code just submitted, even before the transcript has caught up', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session({ state: 'CODING', currentQuestion: codingProblem }));
    server.use(
      http.post(`${BASE}/answers`, () => {
        backend.current = session({ state: 'FOLLOW_UP', currentQuestion: followUp, version: 5 });
        // The transcript reload that follows is slow: the follow-up opens before it arrives.
        server.use(http.get(`${BASE}/transcript`, () => new Promise(() => undefined)));
        return HttpResponse.json(turn({ state: 'FOLLOW_UP', nextQuestion: followUp }));
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    const editor = await screen.findByLabelText('Your solution');
    await user.clear(editor);
    await user.click(editor);
    await user.paste(submitted);
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    await screen.findByText(/How would your solution change/, undefined, { timeout: 3000 });
    expect(screen.getByLabelText('Your solution')).toHaveValue(submitted);
  });

  it('starts from the last submitted code once the transcript arrives after a reload', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    fakeBackend(session({ state: 'FOLLOW_UP', currentQuestion: followUp }));
    server.use(
      http.get(`${BASE}/transcript`, async () => {
        await gate;
        return HttpResponse.json(
          transcript([
            {
              question: codingProblem,
              answer: { content: submitted, skipped: false, submittedAt: '2026-10-08T12:05:00Z' },
              feedback: null,
            },
          ]),
        );
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/coding`, { signedIn: true });

    expect(await screen.findByLabelText('Your solution')).toHaveValue('');
    release();

    await waitFor(() => {
      expect(screen.getByLabelText('Your solution')).toHaveValue(submitted);
    });
  });
});

describe('InterviewRoom: the session ends during the interview', () => {
  it('renews an expired token without the candidate noticing', async () => {
    const user = userEvent.setup();
    const backend = fakeBackend(session());
    let answers = 0;
    server.use(
      http.post(`${BASE}/answers`, () => {
        answers += 1;
        if (answers === 1) {
          return problem(401, 'TOKEN_EXPIRED');
        }
        const next = question({ id: 'q-2', prompt: 'After the renewal' });
        backend.current = session({ currentQuestion: next, version: 5 });
        return HttpResponse.json(turn({ nextQuestion: next }));
      }),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'an answer');
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    await user.click(await screen.findByRole('button', { name: 'Show full question' }));
    expect(await screen.findByText('After the renewal')).toBeInTheDocument();
    expect(answers).toBe(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('sends the candidate to sign in when the session cannot be renewed, and back to the interview with the draft', async () => {
    const user = userEvent.setup();
    fakeBackend(session());
    server.use(
      http.post(`${BASE}/answers`, () => problem(401, 'TOKEN_EXPIRED')),
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN)),
    );
    const { router } = renderRoute(`/interviews/${INTERVIEW_ID}/live`, { signedIn: true });

    await write(user, 'Your answer', 'words I do not want to lose');
    // The refresh cookie has expired too.
    server.use(http.post(`${API}/auth/refresh`, () => problem(401, 'UNAUTHENTICATED')));
    await user.click(screen.getByRole('button', { name: /submit answer/i }));

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Email'), 'ada@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    // Signing in again works with a fresh refresh cookie.
    server.use(http.post(`${API}/auth/refresh`, () => HttpResponse.json(TOKEN)));
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByLabelText('Your answer')).toHaveValue('words I do not want to lose');
    expect(router.state.location.pathname).toBe(`/interviews/${INTERVIEW_ID}/live`);
  });
});
