import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import {
  codingEntry,
  designEntries,
  INTERVIEW_ID,
  report,
  session,
  transcript,
} from '../../test/fixtures';
import { API, problem } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

const BASE = `${API}/interviews/${INTERVIEW_ID}`;
const PATH = `/interviews/${INTERVIEW_ID}/results`;

function backend(reportBody: Parameters<typeof report>[0] = {}, status = 200) {
  server.use(
    http.get(`${BASE}/report`, () => HttpResponse.json(report(reportBody), { status })),
    http.get(BASE, () => HttpResponse.json(session({ state: 'COMPLETED', currentQuestion: null }))),
    http.get(`${BASE}/transcript`, () => HttpResponse.json(transcript())),
  );
}

describe('ResultsPage: a ready report', () => {
  it('breaks the score down by assessment area and says what was not assessed', async () => {
    backend();
    renderRoute(PATH, { signedIn: true });

    const areas = await screen.findByRole('region', { name: 'What the score is made of' });
    expect(within(areas).getByRole('heading', { name: 'Fundamentals' })).toBeInTheDocument();
    expect(within(areas).getByRole('progressbar', { name: 'Fundamentals score' })).toHaveAttribute(
      'aria-valuenow',
      '74',
    );
    expect(
      within(areas).getByText('3 answers · 70% of the overall score · medium confidence'),
    ).toBeInTheDocument();
    expect(within(areas).getByText(/1 evaluation here was unsure of itself/)).toBeInTheDocument();
    expect(
      within(areas).getByText(/Not assessed: the interview did not get to it/),
    ).toBeInTheDocument();
    expect(within(areas).getByText('5 rated answers · high confidence')).toBeInTheDocument();
    expect(
      within(areas).queryByRole('heading', { name: 'Problem solving' }),
    ).not.toBeInTheDocument();
    expect(within(areas).getByText('React / Hooks')).toBeInTheDocument();
    expect(within(areas).queryByText('Recommendation capped')).not.toBeInTheDocument();
  });

  it('says why the recommendation was capped by weak fundamentals', async () => {
    const capped = report().assessmentAreas;
    backend({
      recommendation: 'DEVELOPING',
      assessmentAreas: capped
        ? {
            ...capped,
            recommendationCap:
              'Capped at Developing: the fundamentals scored 38 over 3 answers, below 45.',
          }
        : null,
    });
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByText('Recommendation capped')).toBeInTheDocument();
    expect(screen.getByText(/the fundamentals scored 38 over 3 answers/)).toBeInTheDocument();
  });

  it('shows no area breakdown for a report made before areas existed', async () => {
    backend({ assessmentAreas: null });
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByLabelText('Scores by section')).toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'What the score is made of' }),
    ).not.toBeInTheDocument();
  });

  it('shows the overall score, the recommendation and what the result stands on', async () => {
    backend();
    renderRoute(PATH, { signedIn: true });

    expect(
      await screen.findByRole('img', { name: 'Overall score: 72.5 out of 100' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Proficient' })).toBeInTheDocument();
    expect(screen.getByText(/not a hiring decision/)).toBeInTheDocument();
    const stats = screen.getByText('Answered').closest('dl');
    expect(stats).toHaveTextContent('5');
    expect(stats).toHaveTextContent('28');
  });

  it('shows a score per section, with the technical section named as such', async () => {
    backend();
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByLabelText('Scores by section')).toBeInTheDocument();
    expect(screen.getByText('Technical score')).toBeInTheDocument();
    expect(screen.getByText('Introduction score')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Technical score' })).toHaveAttribute(
      'aria-valuenow',
      '70',
    );
  });

  it('shows topic scores with the confidence of each and how many answers it stands on', async () => {
    backend();
    renderRoute(PATH, { signedIn: true });

    const card = await screen.findByRole('region', { name: 'Topic scores' });
    expect(
      within(card).getByRole('img', { name: /Skill scores: Java 78, Spring Boot 61, React 40/ }),
    ).toBeInTheDocument();
    expect(within(card).getByText('High confidence')).toBeInTheDocument();
    expect(within(card).getByText('Low confidence')).toBeInTheDocument();
    expect(within(card).getByText('4 answers assessed')).toBeInTheDocument();
    expect(within(card).getByText('1 answer assessed')).toBeInTheDocument();
  });

  it('shows the AI feedback as formatted text, strengths and weaknesses with their evidence', async () => {
    backend();
    renderRoute(PATH, { signedIn: true });

    const feedback = await screen.findByRole('region', { name: 'AI feedback' });
    expect(within(feedback).getByText('concurrency').tagName).toBe('STRONG');
    const strengths = screen.getByRole('region', { name: 'Strengths' });
    expect(
      within(strengths).getByText('Clear explanation of the memory model'),
    ).toBeInTheDocument();
    expect(
      within(strengths).getByText(/volatile gives visibility, not atomicity/),
    ).toBeInTheDocument();
    const weaknesses = screen.getByRole('region', { name: 'Weaknesses' });
    expect(within(weaknesses).getByText('Self-invocation and Spring proxies')).toBeInTheDocument();
  });

  it('lists knowledge gaps in four tabs that can be used with the keyboard', async () => {
    const user = userEvent.setup();
    backend();
    renderRoute(PATH, { signedIn: true });

    const gaps = await screen.findByRole('region', { name: 'Knowledge gaps' });
    expect(within(gaps).getByRole('tab', { name: /Missed concepts/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(within(gaps).getByText('count++ is a read-modify-write')).toBeInTheDocument();
    expect(within(gaps).getByText('Missing')).toBeInTheDocument();

    await user.click(within(gaps).getByRole('tab', { name: /Missed concepts/ }));
    await user.keyboard('{ArrowRight}');
    expect(within(gaps).getByRole('tab', { name: /Misconceptions/ })).toHaveFocus();
    expect(within(gaps).getByText('@Transactional works on self-invocation')).toBeInTheDocument();
    expect(within(gaps).getByText(/bypasses the proxy/)).toBeInTheDocument();

    await user.keyboard('{ArrowRight}');
    expect(within(gaps).getByText('Spring / AOP')).toBeInTheDocument();
    expect(within(gaps).getByText('30')).toBeInTheDocument();

    await user.keyboard('{ArrowRight}');
    expect(within(gaps).getByText('Treats proxies as transparent')).toBeInTheDocument();
    expect(within(gaps).getByText('Linked by the evaluator')).toBeInTheDocument();

    await user.keyboard('{End}');
    expect(within(gaps).getByRole('tab', { name: /Repeated mistakes/ })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(within(gaps).getByRole('tab', { name: /Missed concepts/ })).toHaveFocus();
  });

  it('shows empty states instead of empty lists when nothing was found', async () => {
    const user = userEvent.setup();
    backend({
      knowledgeGaps: {
        missedConcepts: [],
        misconceptions: [],
        weakTopics: [],
        repeatedMistakes: [],
      },
      strengths: [],
      improvementAreas: [],
      studyPlan: [],
      furtherReading: [],
    });
    renderRoute(PATH, { signedIn: true });

    const gaps = await screen.findByRole('region', { name: 'Knowledge gaps' });
    expect(within(gaps).getByText('No missed concepts')).toBeInTheDocument();
    await user.click(within(gaps).getByRole('tab', { name: /Misconceptions/ }));
    expect(within(gaps).getByText('No misconceptions found')).toBeInTheDocument();
    expect(screen.getByText('No strengths listed')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Study plan' })).not.toBeInTheDocument();
  });

  it('shows the study plan with its sources', async () => {
    backend();
    renderRoute(PATH, { signedIn: true });

    const plan = await screen.findByRole('region', { name: 'Study plan' });
    expect(within(plan).getByText('Read about Spring AOP proxies')).toBeInTheDocument();
    expect(within(plan).getByText('Java concurrency')).toBeInTheDocument();
  });

  it('shows every question in a timeline with the answer and how it was rated', async () => {
    const user = userEvent.setup();
    backend();
    renderRoute(PATH, { signedIn: true });

    const timeline = await screen.findByRole('region', { name: 'Interview timeline' });
    const first = await within(timeline).findByText(/Question 1 · Java \/ Concurrency/);
    expect(within(timeline).getByText('Skipped')).toBeInTheDocument();

    await user.click(first);

    expect(
      within(timeline).getByText('volatile gives visibility, not atomicity'),
    ).toBeInTheDocument();
    expect(
      within(timeline).getByText('Good on visibility, missed the read-modify-write.'),
    ).toBeInTheDocument();
    expect(
      within(timeline).getByRole('progressbar', { name: 'Correctness rating' }),
    ).toHaveAttribute('aria-valuenow', '75');
    expect(within(timeline).getByText('Missed')).toBeInTheDocument();
    expect(within(timeline).getByText('Covered')).toBeInTheDocument();
  });

  it('explains the score of a coding solution: tests, how each rating was decided, and the complexity', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(`${BASE}/report`, () => HttpResponse.json(report())),
      http.get(BASE, () =>
        HttpResponse.json(session({ state: 'COMPLETED', currentQuestion: null })),
      ),
      http.get(`${BASE}/transcript`, () => HttpResponse.json(transcript([codingEntry()]))),
    );
    renderRoute(PATH, { signedIn: true });

    const timeline = await screen.findByRole('region', { name: 'Interview timeline' });
    await user.click(await within(timeline).findByText(/Question 3/));

    expect(within(timeline).getByText('7 of 9 tests passed')).toBeInTheDocument();
    expect(within(timeline).getByText('Too slow')).toBeInTheDocument();
    expect(within(timeline).getByText('zeros')).toBeInTheDocument();
    expect(within(timeline).getByText('Set by tests')).toBeInTheDocument();
    expect(within(timeline).getByText('Adjusted')).toBeInTheDocument();
    expect(
      within(timeline).getByText(/Set by the tests: 7 of 8 functional tests passed/),
    ).toBeInTheDocument();
    expect(
      within(timeline).getByText(/a performance test on a large input exceeded its time limit/),
    ).toBeInTheDocument();
    expect(within(timeline).getByText('O(n^2) time · O(1) space')).toBeInTheDocument();
    expect(within(timeline).getByText('O(n) time · O(n) space')).toBeInTheDocument();
    expect(
      within(timeline).getByRole('progressbar', { name: 'Correctness rating' }),
    ).toHaveAttribute('aria-valuenow', '75');
  });

  it('shows a system design scorecard: every dimension, the quotes it rests on, the probes and the final diagram', async () => {
    server.use(
      http.get(BASE, () =>
        HttpResponse.json(session({ state: 'COMPLETED', currentQuestion: null })),
      ),
      http.get(`${BASE}/report`, () => HttpResponse.json(report())),
      http.get(`${BASE}/transcript`, () => HttpResponse.json(transcript(designEntries()))),
    );
    renderRoute(`/interviews/${INTERVIEW_ID}/results`, { signedIn: true });

    const card = within(await screen.findByRole('region', { name: 'System design scorecard' }));
    expect(
      card.getByText(/Design a URL shortener: every dimension rated on the whole discussion, 68/),
    ).toBeInTheDocument();
    expect(card.getByText(/probed, in order/).parentElement).toHaveTextContent(
      'The interviewer probed, in order: Caching',
    );
    expect(card.getAllByRole('progressbar')).toHaveLength(13);
    expect(card.getByText('“quote for CACHING”')).toBeInTheDocument();
    expect(card.getByText('Probed')).toBeInTheDocument();
    expect(card.getByText('Adjusted')).toBeInTheDocument();
    expect(card.getAllByText('Not shown in your design.')).toHaveLength(2);
    expect(card.getByRole('img', { name: /2 components/ })).toBeInTheDocument();
  });

  it('opens the timeline entry of the evidence behind a finding', async () => {
    const user = userEvent.setup();
    backend();
    renderRoute(PATH, { signedIn: true });

    const strengths = await screen.findByRole('region', { name: 'Strengths' });
    await user.click(within(strengths).getByRole('link', { name: 'See answer' }));

    const entry = document.getElementById('turn-q-1');
    expect(entry?.querySelector('details')).toHaveAttribute('open');
  });
});

describe('ResultsPage: reports with limits', () => {
  it('shows no overall score for an inconclusive report, and says that it is not a zero', async () => {
    backend({
      outcome: 'INCONCLUSIVE',
      overallScore: null,
      recommendation: null,
      summary: null,
      assessment: {
        assessedShare: 0.4,
        assessedWeight: 8,
        intendedWeight: 20,
        unassessableAnswers: [],
      },
    });
    renderRoute(PATH, { signedIn: true });

    expect(
      await screen.findByText(/Not enough could be assessed for a verdict/),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'No recommendation' })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /Overall score/ })).not.toBeInTheDocument();
    expect(screen.getByText(/says nothing about how you did/)).toBeInTheDocument();
  });

  it('tells a partial report which share of the interview was assessed', async () => {
    backend({
      outcome: 'PARTIAL',
      assessment: {
        assessedShare: 0.85,
        assessedWeight: 17,
        intendedWeight: 20,
        unassessableAnswers: [
          { questionId: 'q-4', sequenceNo: 4, section: 'QUESTIONING', reason: 'PROVIDER_FAILURE' },
        ],
      },
    });
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByText(/Scored on most, not all, of your answers/)).toBeInTheDocument();
    expect(screen.getByText(/1 answer could not be evaluated/)).toBeInTheDocument();
    expect(screen.getByText(/85% of the interview was assessed/)).toBeInTheDocument();
  });

  it('surfaces integrity notes, including a low-confidence evaluator', async () => {
    backend({
      integrityNotes: [
        {
          flag: 'LOW_EVALUATOR_CONFIDENCE',
          questionId: 'q-1',
          note: 'A human review is recommended.',
        },
        {
          flag: 'INJECTION_SUSPECTED',
          questionId: 'q-2',
          note: 'Text aimed at the grader was ignored.',
        },
      ],
    });
    renderRoute(PATH, { signedIn: true });

    const notes = await screen.findByRole('region', { name: 'Integrity notes' });
    expect(within(notes).getByText('The evaluator was not confident')).toBeInTheDocument();
    expect(within(notes).getByText('An answer addressed the grader')).toBeInTheDocument();
    expect(within(notes).getByText('A human review is recommended.')).toBeInTheDocument();
  });

  it('does not show knowledge gaps when the report has none (a report made before they existed)', async () => {
    backend({ knowledgeGaps: null });
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByRole('img', { name: /Overall score/ })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Knowledge gaps' })).not.toBeInTheDocument();
  });
});

describe('ResultsPage: not ready', () => {
  it('waits for a report that is being written and shows it when it is ready', async () => {
    let polls = 0;
    server.use(
      http.get(`${BASE}/report`, () => {
        polls += 1;
        return polls === 1
          ? HttpResponse.json(report({ status: 'GENERATING', overallScore: null }), { status: 202 })
          : HttpResponse.json(report());
      }),
      http.get(BASE, () =>
        HttpResponse.json(session({ state: 'COMPLETED', currentQuestion: null })),
      ),
      http.get(`${BASE}/transcript`, () => HttpResponse.json(transcript())),
    );
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByText('Your report is being written')).toBeInTheDocument();
    expect(
      await screen.findByRole('img', { name: /Overall score/ }, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(polls).toBe(2);
  });

  it('keeps waiting for a report when one poll fails, instead of replacing the page with an error', async () => {
    let polls = 0;
    server.use(
      http.get(`${BASE}/report`, () => {
        polls += 1;
        if (polls === 1) {
          return HttpResponse.json(report({ status: 'GENERATING', overallScore: null }), {
            status: 202,
          });
        }
        return polls === 2 ? problem(502, 'UNEXPECTED_RESPONSE') : HttpResponse.json(report());
      }),
      http.get(BASE, () =>
        HttpResponse.json(session({ state: 'COMPLETED', currentQuestion: null })),
      ),
      http.get(`${BASE}/transcript`, () => HttpResponse.json(transcript())),
    );
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByText('Your report is being written')).toBeInTheDocument();
    await waitFor(
      () => {
        expect(polls).toBeGreaterThanOrEqual(2);
      },
      { timeout: 4000 },
    );
    expect(screen.queryByText('The report could not be loaded')).not.toBeInTheDocument();
    expect(screen.getByText('Your report is being written')).toBeInTheDocument();
    expect(
      await screen.findByRole('img', { name: /Overall score/ }, { timeout: 5000 }),
    ).toBeInTheDocument();
  });

  it('offers to make a failed report again, and shows the result', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(`${BASE}/report`, () =>
        HttpResponse.json(
          report({
            status: 'FAILED',
            overallScore: null,
            retryable: true,
            errorMessage: 'The narrator timed out.',
          }),
        ),
      ),
      http.post(`${BASE}/report/retry`, () => HttpResponse.json(report())),
      http.get(BASE, () =>
        HttpResponse.json(session({ state: 'COMPLETED', currentQuestion: null })),
      ),
      http.get(`${BASE}/transcript`, () => HttpResponse.json(transcript())),
    );
    renderRoute(PATH, { signedIn: true });

    const alert = await screen.findByText('The report could not be made');
    expect(alert.closest('[role="alert"]')).toHaveTextContent('The narrator timed out.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('img', { name: /Overall score/ })).toBeInTheDocument();
  });

  it('says that an unfinished interview has no report and links back to it', async () => {
    server.use(http.get(`${BASE}/report`, () => problem(409, 'INTERVIEW_NOT_FINISHED')));
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByText(/This interview is not finished/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Return to the interview' })).toHaveAttribute(
      'href',
      `/interviews/${INTERVIEW_ID}/live`,
    );
  });

  it('shows an error with a retry when the report cannot be loaded', async () => {
    server.use(http.get(`${BASE}/report`, () => problem(500, 'INTERNAL_ERROR')));
    renderRoute(PATH, { signedIn: true });

    expect(await screen.findByText(/The report could not be loaded/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
