import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import type { Resume } from '../../api/types';
import { API, problem } from '../../test/handlers';
import { renderRoute } from '../../test/renderWithProviders';
import { server } from '../../test/server';

const PROFILE = {
  headline: null,
  yearsExperience: null,
  jobTitle: null,
  skills: [],
  updatedAt: '2026-10-08T12:00:00Z',
};

const RESUME: Resume = {
  fileName: 'ada-cv.pdf',
  fileType: 'PDF',
  sizeBytes: 48_000,
  pageCount: 2,
  uploadedAt: '2026-10-12T09:00:00Z',
  injectionSuspected: false,
  droppedItems: 0,
  profile: {
    yearsOfExperience: { years: 8, basis: 'STATED', evidence: '8 years of experience' },
    positions: [
      {
        title: 'Senior Software Engineer',
        organization: 'Acme Payments',
        start: '2021-03',
        end: null,
        evidence: 'Senior Software Engineer, Acme Payments - Mar 2021 to Present',
      },
    ],
    technologies: [
      { name: 'Kafka', evidence: 'Java, Spring Boot, Kafka' },
      { name: 'Spring Boot', evidence: 'Java, Spring Boot, Kafka' },
    ],
    skills: [{ name: 'API design', evidence: 'Designed REST APIs for the order service' }],
    domains: [{ name: 'Payments', evidence: 'building payment systems' }],
    experiences: [
      {
        id: 'x1',
        kind: 'PROJECT',
        text: 'Built a Spring Boot microservice using Kafka to process card payment events.',
        context: 'Acme Payments',
        technologies: ['Spring Boot', 'Kafka'],
      },
      {
        id: 'x2',
        kind: 'ACHIEVEMENT',
        text: 'Reduced p99 latency of the checkout API by 40% with Redis caching.',
        context: null,
        technologies: ['Redis'],
      },
    ],
  },
};

function backend(resume: Resume | null) {
  server.use(
    http.get(`${API}/profile`, () => HttpResponse.json(PROFILE)),
    http.get(`${API}/profile/resume`, () =>
      resume ? HttpResponse.json(resume) : problem(404, 'NOT_FOUND'),
    ),
  );
}

function pdf(name = 'ada-cv.pdf', size = 1024): File {
  return new File([new Uint8Array(size)], name, { type: 'application/pdf' });
}

async function resumeCard() {
  return screen.findByRole('region', { name: 'Résumé' });
}

describe('ResumeCard', () => {
  it('uploads a résumé as a file and shows the profile the server verified', async () => {
    const user = userEvent.setup();
    backend(null);
    // jsdom's FormData cannot be read back by Node's fetch in a test, so the handler checks the header and a spy sees the parts.
    const append = vi.spyOn(FormData.prototype, 'append');
    let contentType = '';
    server.use(
      http.put(`${API}/profile/resume`, ({ request }) => {
        contentType = request.headers.get('content-type') ?? '';
        return HttpResponse.json(RESUME);
      }),
    );
    renderRoute('/profile', { signedIn: true });

    const card = await resumeCard();
    const input = await within(card).findByLabelText('Résumé file');
    const upload = within(card).getByRole('button', { name: 'Upload résumé' });
    expect(upload).toBeDisabled();
    await user.upload(input, pdf());
    await user.click(upload);

    expect(
      await within(card).findByText(
        'Built a Spring Boot microservice using Kafka to process card payment events.',
      ),
    ).toBeInTheDocument();
    expect(contentType).toMatch(/^multipart\/form-data; boundary=/);
    expect(append).toHaveBeenCalledWith('file', expect.objectContaining({ name: 'ada-cv.pdf' }));
    append.mockRestore();
    expect(within(card).getByText('8 years (as your résumé states)')).toBeInTheDocument();
    expect(within(card).getByRole('heading', { name: 'Projects' })).toBeInTheDocument();
    expect(within(card).getByRole('heading', { name: 'Achievements' })).toBeInTheDocument();
    expect(within(card).getByText('Kafka')).toBeInTheDocument();
    expect(within(card).getByText('Payments')).toBeInTheDocument();
    expect(within(card).getByText(/Senior Software Engineer/)).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Upload new résumé' })).toBeInTheDocument();
  });

  it('refuses a file over 2 MB before sending it', async () => {
    const user = userEvent.setup();
    backend(null);
    renderRoute('/profile', { signedIn: true });

    const card = await resumeCard();
    await user.upload(
      await within(card).findByLabelText('Résumé file'),
      pdf('big.pdf', 3 * 1024 * 1024),
    );

    expect(within(card).getByRole('alert')).toHaveTextContent('a résumé may be at most 2 MB');
    expect(within(card).getByRole('button', { name: 'Upload résumé' })).toBeDisabled();
  });

  it.each([
    [415, 'UNSUPPORTED_FILE_TYPE', 'The file is not a supported document.'],
    [422, 'DOCUMENT_EMPTY', 'The PDF has no readable text.'],
    [422, 'DOCUMENT_UNREADABLE', 'The PDF is damaged and cannot be read.'],
  ])('explains why the server refused the file (%s %s)', async (status, code, detail) => {
    const user = userEvent.setup();
    backend(null);
    server.use(http.put(`${API}/profile/resume`, () => problem(status, code, { detail })));
    renderRoute('/profile', { signedIn: true });

    const card = await resumeCard();
    await user.upload(await within(card).findByLabelText('Résumé file'), pdf());
    await user.click(within(card).getByRole('button', { name: 'Upload résumé' }));

    expect(await within(card).findByText(detail)).toBeInTheDocument();
    expect(within(card).queryByRole('heading', { name: 'Projects' })).not.toBeInTheDocument();
  });

  it('says when text addressed to the AI was ignored and items were left out', async () => {
    backend({ ...RESUME, injectionSuspected: true, droppedItems: 2 });
    renderRoute('/profile', { signedIn: true });

    const card = await resumeCard();

    expect(await within(card).findByText('Some text was ignored')).toBeInTheDocument();
    expect(
      within(card).getByText(
        /2 items the AI proposed were left out because your résumé does not say them/,
      ),
    ).toBeInTheDocument();
  });

  it('deletes the résumé after a confirmation', async () => {
    const user = userEvent.setup();
    backend(RESUME);
    let deleted = false;
    server.use(
      http.delete(`${API}/profile/resume`, () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderRoute('/profile', { signedIn: true });

    const card = await resumeCard();
    await user.click(await within(card).findByRole('button', { name: 'Delete résumé' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete your résumé?' });
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await within(card).findByLabelText('Résumé file')).toBeInTheDocument();
    expect(deleted).toBe(true);
    expect(within(card).queryByText(/Built a Spring Boot microservice/)).not.toBeInTheDocument();
  });
});
