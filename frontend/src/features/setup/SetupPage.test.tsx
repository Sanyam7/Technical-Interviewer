import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { CONFIGURATION, INTERVIEW_ID, session } from '../../test/fixtures';
import { API, problem } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

const RESUME = {
  fileName: 'cv.pdf',
  fileType: 'PDF',
  sizeBytes: 1000,
  pageCount: 1,
  uploadedAt: '2026-10-12T09:00:00Z',
  injectionSuspected: false,
  droppedItems: 0,
  profile: {
    yearsOfExperience: null,
    positions: [],
    technologies: [],
    skills: [],
    domains: [],
    experiences: [],
  },
};

/** Captures the configuration the page sends and answers with a validation error, so the page stays put. */
function captureConfiguration(): { body: { configuration?: Record<string, unknown> } } {
  const captured: { body: { configuration?: Record<string, unknown> } } = { body: {} };
  server.use(
    http.post(`${API}/interviews`, async ({ request }) => {
      captured.body = (await request.json()) as typeof captured.body;
      return problem(400, 'VALIDATION_FAILED', { errors: [] });
    }),
  );
  return captured;
}

function backend() {
  server.use(
    http.get(`${API}/interview-configurations`, () =>
      HttpResponse.json({ items: [], page: 0, size: 5, totalItems: 0, totalPages: 0 }),
    ),
    http.get(`${API}/interviews/${INTERVIEW_ID}`, () =>
      HttpResponse.json(
        session({ state: 'INIT', currentQuestion: null, startedAt: null, deadlineAt: null }),
      ),
    ),
  );
}

describe('SetupPage', () => {
  it('creates an interview from the choices and opens its briefing', async () => {
    const user = userEvent.setup();
    backend();
    let body: unknown;
    server.use(
      http.post(`${API}/interviews`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(
          {
            id: INTERVIEW_ID,
            state: 'INIT',
            configuration: CONFIGURATION,
            plan: { sections: [] },
            createdAt: '2026-10-08T12:00:00Z',
            version: 1,
          },
          { status: 201 },
        );
      }),
    );
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('radio', { name: /Full stack/ }));
    await user.click(screen.getByRole('radio', { name: 'Senior' }));
    await user.click(screen.getByRole('checkbox', { name: 'Java' }));
    await user.click(screen.getByRole('checkbox', { name: 'React' }));
    await user.click(screen.getByRole('radio', { name: /Full loop/ }));
    await user.selectOptions(screen.getByLabelText('Coding language'), 'python');
    await user.click(screen.getByRole('radio', { name: 'Hard' }));
    await user.selectOptions(screen.getByLabelText('Duration'), '60');
    await user.click(screen.getByRole('button', { name: 'Create interview' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Technical Q&A interview' }),
    ).toBeInTheDocument();
    expect(body).toEqual({
      configuration: {
        role: 'FULL_STACK',
        experienceLevel: 'SENIOR',
        skills: ['java', 'react'],
        difficulty: 'HARD',
        durationMinutes: 60,
        interviewType: 'FULL_LOOP',
        codingLanguage: 'python',
        useResume: false,
        strategy: 'FUNDAMENTALS_FIRST',
        stageMix: null,
      },
    });
  });

  it('summarises the choices, including the sections of the chosen type', async () => {
    const user = userEvent.setup();
    backend();
    renderRoute('/interviews/new', { signedIn: true });

    const summary = await screen.findByRole('complementary', { name: 'Summary' });
    expect(within(summary).getByText('Choose at least one.')).toBeInTheDocument();
    expect(within(summary).getByText('Introduction')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Spring Boot' }));
    await user.click(screen.getByRole('radio', { name: /System design/ }));

    expect(within(summary).getByText('Spring Boot')).toBeInTheDocument();
    expect(within(summary).getAllByText('System design').length).toBeGreaterThan(0);
    expect(within(summary).queryByText('Language')).not.toBeInTheDocument();
  });

  it('asks for a technology instead of sending an incomplete setup', async () => {
    const user = userEvent.setup();
    backend();
    let sent = false;
    server.use(
      http.post(`${API}/interviews`, () => {
        sent = true;
        return problem(400, 'VALIDATION_FAILED');
      }),
    );
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Create interview' }));

    expect(await screen.findByText('Choose at least one technology.')).toBeInTheDocument();
    expect(sent).toBe(false);
  });

  it('stops offering technologies after six are chosen', async () => {
    const user = userEvent.setup();
    backend();
    server.use(
      http.get(`${API}/catalog/skills`, () =>
        HttpResponse.json(
          Array.from({ length: 8 }, (_, index) => ({
            id: index + 1,
            slug: `skill-${index}`,
            name: `Skill ${index}`,
            category: 'CONCEPT',
            description: null,
          })),
        ),
      ),
    );
    renderRoute('/interviews/new', { signedIn: true });

    for (let index = 0; index < 6; index += 1) {
      await user.click(await screen.findByRole('checkbox', { name: `Skill ${index}` }));
    }

    expect(screen.getByRole('checkbox', { name: 'Skill 6' })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: 'Skill 0' })).toBeEnabled();
    expect(screen.getByText('6 of 6 selected')).toBeInTheDocument();
  });

  it('refills the form from a recent setup', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(`${API}/interview-configurations`, () =>
        HttpResponse.json({
          items: [
            { ...CONFIGURATION, interviewType: 'CODING', codingLanguage: 'go', difficulty: 'HARD' },
          ],
          page: 0,
          size: 5,
          totalItems: 1,
          totalPages: 1,
        }),
      ),
    );
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('button', { name: /Coding · 30 min/ }));

    expect(screen.getByRole('radio', { name: 'Hard' })).toBeChecked();
    expect(screen.getByRole('radio', { name: /Coding/ })).toBeChecked();
    expect(screen.getByLabelText('Coding language')).toHaveValue('go');
    expect(screen.getByRole('checkbox', { name: 'Java' })).toBeChecked();
  });

  it('says which coding languages are run against tests', async () => {
    const user = userEvent.setup();
    backend();
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('radio', { name: /^Coding/ }));

    expect(screen.getByRole('option', { name: 'java · tests run' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'python · AI review only' })).toBeInTheDocument();
    expect(screen.getByText(/runs against hidden tests in a sandbox/)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Coding language'), 'go');
    expect(screen.getByText(/correctness counts as unverified/)).toBeInTheDocument();
  });

  it('explains the daily limit', async () => {
    const user = userEvent.setup();
    backend();
    server.use(http.post(`${API}/interviews`, () => problem(429, 'DAILY_INTERVIEW_LIMIT')));
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('checkbox', { name: 'Java' }));
    await user.click(screen.getByRole('button', { name: 'Create interview' }));

    expect(await screen.findByText('Daily limit reached')).toBeInTheDocument();
  });

  it('shows an error with a retry when the options cannot be loaded', async () => {
    server.use(http.get(`${API}/catalog/options`, () => problem(500, 'INTERNAL_ERROR')));
    renderRoute('/interviews/new', { signedIn: true });

    expect(
      await screen.findByText(/The interview options could not be loaded/),
    ).toBeInTheDocument();
  });

  it('offers résumé personalisation only once a résumé is uploaded', async () => {
    backend();
    renderRoute('/interviews/new', { signedIn: true });

    const option = await screen.findByRole('checkbox', { name: 'Personalise with my résumé' });
    expect(option).toBeDisabled();
    expect(screen.getByRole('link', { name: 'profile' })).toHaveAttribute('href', '/profile');
  });

  it('asks for a personalised interview when the candidate opts in', async () => {
    const user = userEvent.setup();
    backend();
    let body: { configuration?: { useResume?: boolean } } = {};
    server.use(
      http.get(`${API}/profile/resume`, () =>
        HttpResponse.json({
          fileName: 'cv.pdf',
          fileType: 'PDF',
          sizeBytes: 1000,
          pageCount: 1,
          uploadedAt: '2026-10-12T09:00:00Z',
          injectionSuspected: false,
          droppedItems: 0,
          profile: {
            yearsOfExperience: null,
            positions: [],
            technologies: [],
            skills: [],
            domains: [],
            experiences: [],
          },
        }),
      ),
      http.post(`${API}/interviews`, async ({ request }) => {
        body = (await request.json()) as typeof body;
        return problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'configuration.useResume', message: 'Upload a résumé first.' }],
        });
      }),
    );
    renderRoute('/interviews/new', { signedIn: true });

    const option = await screen.findByRole('checkbox', { name: 'Personalise with my résumé' });
    await waitFor(() => {
      expect(option).toBeEnabled();
    });
    await user.click(option);
    await user.click(screen.getByRole('checkbox', { name: 'Java' }));
    expect(
      within(screen.getByRole('complementary', { name: 'Summary' })).getByText('Personalised'),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create interview' }));

    expect(await screen.findByText('Upload a résumé first.')).toBeInTheDocument();
    expect(body.configuration?.useResume).toBe(true);
  });

  it('shows each field error of the server at its field, and the ones without a field in the summary', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API}/interviews`, () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [
            { field: 'configuration.skills[0]', message: "Skill 'java' is not active any more." },
            { field: 'configuration.role', message: 'must not be null' },
          ],
        }),
      ),
    );
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('checkbox', { name: 'Java' }));
    await user.click(screen.getByRole('button', { name: 'Create interview' }));

    const technologies = screen.getByRole('group', { name: /Technologies/ });
    expect(
      await within(technologies).findByText("Skill 'java' is not active any more."),
    ).toBeInTheDocument();
    const summary = screen.getByRole('complementary', { name: 'Summary' });
    expect(within(summary).getByText(/must not be null/)).toBeInTheDocument();
  });

  it('says what the daily limit is in the words of the server', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API}/interviews`, () =>
        problem(429, 'DAILY_INTERVIEW_LIMIT', {
          detail: 'You have started 3 interviews today, the most allowed. Try again tomorrow.',
        }),
      ),
    );
    renderRoute('/interviews/new', { signedIn: true });

    await user.click(await screen.findByRole('checkbox', { name: 'Java' }));
    await user.click(screen.getByRole('button', { name: 'Create interview' }));

    expect(await screen.findByText(/started 3 interviews today/)).toBeInTheDocument();
    expect(screen.queryByText(/10 interviews a day/)).not.toBeInTheDocument();
  });

  describe('interview approach', () => {
    it('defaults to fundamentals first and explains each approach with its shares', async () => {
      backend();
      renderRoute('/interviews/new', { signedIn: true });

      const approach = await screen.findByRole('group', { name: 'Interview approach' });
      expect(within(approach).getByRole('radio', { name: /Fundamentals first/ })).toBeChecked();
      expect(
        within(approach).getByText('60% fundamentals · 25% practical · 15% projects'),
      ).toBeInTheDocument();
      expect(
        within(screen.getByRole('complementary', { name: 'Summary' })).getByText(
          'Fundamentals first',
        ),
      ).toBeInTheDocument();
    });

    it('is not offered for interviews without technical questions', async () => {
      const user = userEvent.setup();
      backend();
      renderRoute('/interviews/new', { signedIn: true });

      await user.click(await screen.findByRole('radio', { name: /^Coding/ }));

      expect(screen.queryByRole('group', { name: 'Interview approach' })).not.toBeInTheDocument();
    });

    it('sends a custom mix only when its shares add up to 100', async () => {
      const user = userEvent.setup();
      backend();
      const captured = captureConfiguration();
      renderRoute('/interviews/new', { signedIn: true });

      await user.click(await screen.findByRole('checkbox', { name: 'Java' }));
      await user.click(screen.getByRole('radio', { name: /Custom/ }));
      const practical = screen.getByLabelText('Practical scenarios (%)');
      await user.clear(practical);
      await user.type(practical, '40');
      await user.click(screen.getByRole('button', { name: 'Create interview' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('The shares add up to 110%');
      expect(captured.body.configuration).toBeUndefined();

      const fundamentals = screen.getByLabelText('Fundamentals (%)');
      await user.clear(fundamentals);
      await user.type(fundamentals, '40');
      await user.click(screen.getByRole('button', { name: 'Create interview' }));

      await waitFor(() => {
        expect(captured.body.configuration).toMatchObject({
          strategy: 'CUSTOM',
          stageMix: { fundamentals: 40, practical: 40, project: 20 },
        });
      });
    });

    it('offers the résumé-focused approach only with a résumé, and then always uses it', async () => {
      const user = userEvent.setup();
      backend();
      const captured = captureConfiguration();
      server.use(http.get(`${API}/profile/resume`, () => HttpResponse.json(RESUME)));
      renderRoute('/interviews/new', { signedIn: true });

      const resumeFocused = await screen.findByRole('radio', { name: /Résumé-focused/ });
      await waitFor(() => {
        expect(resumeFocused).toBeEnabled();
      });
      await user.click(resumeFocused);
      const personalise = screen.getByRole('checkbox', { name: 'Personalise with my résumé' });
      expect(personalise).toBeChecked();
      expect(personalise).toBeDisabled();
      await user.click(screen.getByRole('checkbox', { name: 'Java' }));
      await user.click(screen.getByRole('button', { name: 'Create interview' }));

      await waitFor(() => {
        expect(captured.body.configuration).toMatchObject({
          strategy: 'RESUME_FOCUSED',
          useResume: true,
          stageMix: null,
        });
      });
    });

    it('does not offer the résumé-focused approach without a résumé', async () => {
      backend();
      renderRoute('/interviews/new', { signedIn: true });

      expect(await screen.findByRole('radio', { name: /Résumé-focused/ })).toBeDisabled();
      expect(screen.getByText('Upload a résumé on your profile to choose it.')).toBeInTheDocument();
    });
  });
});
