import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { usersApi, type User, type UsersResponse, type BulkUploadRow } from './api';

// TanStack Query hooks over the BFF. Mirrors the GeoWealth Firm Admin →
// Users & Access query layout, so future refactors can lift this almost
// verbatim into the real codebase.

const QK = {
  firms: () => ['users', 'firms'] as const,
  list: (firmCd: number | null) => ['users', 'list', firmCd] as const,
  dropdowns: (firmCd: number | null) => ['users', 'dropdowns', firmCd] as const
};

export function useFirms() {
  return useQuery({
    queryKey: QK.firms(),
    queryFn: usersApi.firms
  });
}

export function useUsers(firmCd: number | null) {
  return useQuery<UsersResponse>({
    queryKey: QK.list(firmCd),
    queryFn: () => usersApi.list(firmCd!),
    enabled: firmCd != null
  });
}

export function useDropdowns(firmCd: number | null) {
  return useQuery({
    queryKey: QK.dropdowns(firmCd),
    queryFn: () => usersApi.dropdowns(firmCd!).then((r) => r.metaData),
    enabled: firmCd != null
  });
}

export function useUpsertUser(firmCd: number | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (user: Partial<User>) => usersApi.upsert(user),
    onSuccess: () => {
      if (firmCd != null) qc.invalidateQueries({ queryKey: QK.list(firmCd) });
    }
  });
}

export function useRedoPolicy() {
  return useMutation({
    mutationFn: (userId: string) => usersApi.redoPolicy(userId)
  });
}

export function useUploadBulkFile() {
  return useMutation({
    mutationFn: ({ firmCd, file }: { firmCd: number; file: File }) => usersApi.uploadBulk(firmCd, file)
  });
}

export function useBulkCreate(firmCd: number | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (rows: BulkUploadRow[]) => {
      // Only forward rows the FE confirmed have no errors. The BFF
      // re-validates and reports `failedRecords` for anything else.
      const clean = rows.filter((r) => !r.errors || r.errors.length === 0).map((r) => r.genericUserJTO);
      return usersApi.bulkCreate(clean);
    },
    onSuccess: () => {
      if (firmCd != null) qc.invalidateQueries({ queryKey: QK.list(firmCd) });
    }
  });
}
