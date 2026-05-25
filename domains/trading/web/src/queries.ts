import { useQuery } from '@tanstack/react-query';
import { tradingApi } from './api';

// TanStack Query hooks over the BFF endpoints. The fetch helper in api.ts
// refreshes the Keycloak token before each call, so these only run once
// the SPA is authenticated (App gates rendering on auth.authenticated).
//
// Query keys are flat per resource; nothing here is parameterised yet, so
// the cache is keyed purely by resource name. staleTime / retry policy is
// configured globally on the QueryClient in main.tsx.

export function usePortfolio() {
  return useQuery({
    queryKey: ['trading', 'portfolio'],
    queryFn: tradingApi.portfolio
  });
}

export function usePositions() {
  return useQuery({
    queryKey: ['trading', 'positions'],
    queryFn: tradingApi.positions
  });
}

export function useOrders() {
  return useQuery({
    queryKey: ['trading', 'orders'],
    queryFn: tradingApi.orders
  });
}
