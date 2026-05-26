import { keycloak } from './auth/keycloak';

// API client for the Demo Users (Firm Admin → Users & Access) BFF.
// Endpoint names match the legacy P1 Struts paths the GeoWealth FE used to
// hit ({@code .do} suffix stripped), so the shape of `users` flowing through
// here is the same shape the real Firm Admin page produces.

export interface FirmOption {
  firmCd: number;
  firmName: string;
}

export interface FirmsResponse {
  options: FirmOption[];
  hasCrossFirmAccess: boolean;
  ownFirmCd: number | null;
  username: string;
}

export interface RoleOption {
  name: string;
  defaultFlag: boolean;
}

export interface DropdownOptions {
  roles: Record<string, RoleOption>;
  customWhitelabelCodes: Record<string, { name: string }>;
}

export interface User {
  userId: string;
  username: string;
  firstName: string;
  lastName: string | null;
  emailAddress: string;
  firmCd: number;
  firmName?: string;
  contactTypeCd: number;
  rolesCds: number[];
  defaultRoleCd: number;
  gwAdminFlag: boolean;
  mfaEnabledFlag: boolean;
  customWhitelabelCode: string | null;
}

export interface FirmSSO {
  enforcementType: 'MANDATORY' | 'OPTIONAL' | string;
}

export interface UsersResponse {
  rows: User[];
  firmSSO: FirmSSO;
  source: string;
}

export interface BulkUploadRow {
  genericUserJTO: Partial<User> & Record<string, unknown>;
  errors: Array<{ field: string; error: string }>;
}

async function authHeaders(): Promise<HeadersInit> {
  await keycloak.updateToken(30).catch(() => {
    /* fall through and let fetch surface a 401 */
  });
  return { Authorization: `Bearer ${keycloak.token ?? ''}` };
}

async function getJson<T>(path: string, params?: Record<string, string | number | null | undefined>): Promise<T> {
  const url = params
    ? `${path}?${new URLSearchParams(
        Object.entries(params)
          .filter(([, v]) => v !== null && v !== undefined)
          .map(([k, v]) => [k, String(v)])
      ).toString()}`
    : path;
  const res = await fetch(url, { headers: await authHeaders() });
  if (!res.ok) {
    throw new Error(await errorMessage(res, path));
  }
  return res.json() as Promise<T>;
}

async function postForm<T>(path: string, params: Record<string, string | number | null | undefined> | null, fields: Record<string, string | Blob>): Promise<T> {
  const url = params
    ? `${path}?${new URLSearchParams(
        Object.entries(params)
          .filter(([, v]) => v !== null && v !== undefined)
          .map(([k, v]) => [k, String(v)])
      ).toString()}`
    : path;
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.append(k, v);
  const res = await fetch(url, { method: 'POST', headers: await authHeaders(), body: fd });
  if (!res.ok) {
    throw new Error(await errorMessage(res, path));
  }
  return res.json() as Promise<T>;
}

async function errorMessage(res: Response, path: string): Promise<string> {
  let extra = '';
  try {
    const text = await res.text();
    if (text) extra = ` — ${text}`;
  } catch { /* ignore */ }
  return `${path} → ${res.status}${extra}`;
}

export const usersApi = {
  firms:          () => getJson<FirmsResponse>('/api/users/firms'),
  list:           (firmCd: number) => getJson<UsersResponse>('/api/users/getUsers', { firmCd }),
  get:            (userId: string) => getJson<User>('/api/users/getEmployeeById', { userId }),
  dropdowns:      (firmCd: number) => getJson<{ metaData: DropdownOptions }>('/api/users/getManageUsersDropdownsByFirm', { firmCd }),
  upsert:         (user: Partial<User>) => postForm<{ status: string; user: User }>('/api/users/createUpdateUser', null, { q: JSON.stringify(user) }),
  redoPolicy:     (userId: string) => postForm<{ status: string }>('/api/users/redoPolicyRules', { userId }, {}),
  uploadBulk:     (firmCd: number, file: File) => postForm<{ results: BulkUploadRow[] }>('/api/users/uploadBulkEmployeesFile', { firmCd }, { bulkCreateEmployeesFile: file }),
  bulkCreate:     (users: Partial<User>[]) => postForm<{ failedRecords: BulkUploadRow[]; createdCount: number }>('/api/users/bulkCreateEmployees', null, { q: JSON.stringify(users) })
};
