import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { AppProviders } from './app/providers';
import { createAppRouter } from './app/router';
import '@fontsource-variable/inter';
import '@fontsource-variable/jetbrains-mono';
import './index.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root not found in index.html');
}

createRoot(container).render(
  <StrictMode>
    <AppProviders router={createAppRouter()} />
  </StrictMode>,
);
