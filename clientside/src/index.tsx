import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import './index.css';
import './styles/ios.css';
import './styles/ios-system.css';
import './styles/experience-reconstruction.css';
import './styles/experience-reconstruction-overrides.css';
import './styles/admin-insights.css';
import './styles/cito-brand.css';
import './styles/cito-product-system.css';
import App from './App';
import { installCsrfFetch } from './shared/csrfFetch';
import { queryClient } from './shared/queryClient';
import { initTheme } from './shared/theme';
import { initBrandMode } from './shared/brandMode';

installCsrfFetch();
initTheme();
initBrandMode();

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root not found');
}

createRoot(container).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);
