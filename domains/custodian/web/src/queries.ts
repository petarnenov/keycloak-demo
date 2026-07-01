import { useQuery } from '@tanstack/react-query';
import { custodianApi } from './api';

// TanStack Query hooks over the BFF endpoints. The fetch helper in api.ts
// refreshes the Keycloak token before each call, so these only run once
// the SPA is authenticated (App gates rendering on auth.authenticated).
//
// Query keys are flat per resource; nothing here is parameterised yet, so
// the cache is keyed purely by resource name. staleTime / retry policy is
// configured globally on the QueryClient in main.tsx.

export function useSummary() {
  return useQuery({
    queryKey: ['custodian', 'summary'],
    queryFn: custodianApi.summary
  });
}

export function useInvoices() {
  return useQuery({
    queryKey: ['custodian', 'invoices'],
    queryFn: custodianApi.invoices
  });
}

export function useUsage() {
  return useQuery({
    queryKey: ['custodian', 'usage'],
    queryFn: custodianApi.usage
  });
}
