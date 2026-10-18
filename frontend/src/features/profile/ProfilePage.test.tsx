import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { API, problem } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

const PROFILE = {
  headline: 'Backend engineer',
  yearsExperience: 6,
  jobTitle: 'Engineer',
  skills: [{ skill: 'java', name: 'Java', selfRating: 4 }],
  updatedAt: '2026-10-08T12:00:00Z',
};

describe('ProfilePage', () => {
  it('shows the account and the saved profile', async () => {
    server.use(http.get(`${API}/profile`, () => HttpResponse.json(PROFILE)));
    renderRoute('/profile', { signedIn: true });

    expect(await screen.findByLabelText('Headline')).toHaveValue('Backend engineer');
    expect(screen.getByRole('heading', { level: 1, name: 'Ada Lovelace' })).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByText('ada@example.com')).toBeInTheDocument();
    expect(screen.getByLabelText('Years of experience')).toHaveValue(6);
    expect(screen.getByLabelText('Java')).toHaveValue('4');
    expect(screen.getByLabelText('React')).toHaveValue('0');
  });

  it('replaces the profile with what the form holds: cleared text is absent, unrated skills are left out', async () => {
    const user = userEvent.setup();
    let body: unknown;
    server.use(
      http.get(`${API}/profile`, () => HttpResponse.json(PROFILE)),
      http.put(`${API}/profile`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          ...PROFILE,
          headline: null,
          jobTitle: null,
          skills: [{ skill: 'react', name: 'React', selfRating: 5 }],
        });
      }),
    );
    renderRoute('/profile', { signedIn: true });

    await user.clear(await screen.findByLabelText('Headline'));
    await user.clear(screen.getByLabelText('Job title'));
    await user.selectOptions(screen.getByLabelText('Java'), '0');
    await user.selectOptions(screen.getByLabelText('React'), '5');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText('Profile saved')).toBeInTheDocument();
    expect(body).toEqual({
      headline: null,
      jobTitle: null,
      yearsExperience: 6,
      skills: [{ skill: 'react', selfRating: 5 }],
    });
  });

  it('shows the field errors the backend reports', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(`${API}/profile`, () => HttpResponse.json(PROFILE)),
      http.put(`${API}/profile`, () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'yearsExperience', message: 'must be at most 60' }],
        }),
      ),
    );
    renderRoute('/profile', { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText('must be at most 60')).toBeInTheDocument();
  });

  it('shows an error about a skill row, which has no field of its own, instead of failing silently', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(`${API}/profile`, () => HttpResponse.json(PROFILE)),
      http.put(`${API}/profile`, () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'skills[1].skill', message: "Skill 'cobol' is not active." }],
        }),
      ),
    );
    renderRoute('/profile', { signedIn: true });

    await user.click(await screen.findByRole('button', { name: 'Save profile' }));

    expect(await screen.findByText("Skill 'cobol' is not active.")).toBeInTheDocument();
  });
});
