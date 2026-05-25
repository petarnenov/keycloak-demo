import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from './auth/AuthProvider';
import { App } from './App';
import { isForbidden } from './api';
import './styles.css';

// Single QueryClient for the SPA. The BFF data is stub/deterministic, so a
// modest staleTime avoids refetch churn when navigating between the two
// pages; refetch-on-focus is off because there is no live market feed here.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // Don't retry a 403 — the user simply lacks the role; retrying just
      // repeats the denied call. Everything else gets one retry.
      retry: (failureCount, error) => !isForbidden(error) && failureCount < 1
    }
  }
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>
);
