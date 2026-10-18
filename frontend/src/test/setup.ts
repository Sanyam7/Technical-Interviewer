import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';

import { resetSession } from '../api/session';
import { clearSavedDrafts } from '../lib/draftStorage';
import { API_ORIGIN } from './handlers';
import { server } from './server';

vi.stubEnv('VITE_API_BASE_URL', API_ORIGIN);

// The default wait of a findBy query (1 s) is too short on a busy machine or CI runner: a page that renders a little late is not a
// failure. The assertions are unchanged; only how long they may wait for the page.
configure({ asyncUtilTimeout: 3000 });

// jsdom has no <dialog> behaviour yet: model the parts the app uses (open state and the close event).
if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
}

// jsdom does not implement scrolling.
Element.prototype.scrollIntoView = vi.fn();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  resetSession();
  clearSavedDrafts();
  localStorage.clear();
});

afterAll(() => {
  server.close();
});
