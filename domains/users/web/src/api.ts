// API client for the Demo Users (Firm Admin → Users & Access) BFF.
// Endpoint names match the legacy P1 Struts paths the GeoWealth FE used to
// hit ({@code .do} suffix stripped), so the shape of `users` flowing through
// here is the same shape the real Firm Admin page produces.
//
// BFF / Token Handler model (IETF "OAuth 2.0 for Browser-Based Apps"): this
// SPA holds NO tokens. Every call carries the httpOnly `USESSION` session
// cookie (`credentials: 'include'`); the BFF attaches the user's access token
// server-side before forwarding to P1.

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

const LOGIN_URL = '/oauth/login/keycloak';

// Thrown on 403: the BFF session is valid but the user's roles don't satisfy
// the endpoint. This is NOT a "signed out" state — bouncing a forbidden user
// back into the login flow just loops (login → callback → 403 → login …). So we
// surface it and the UI renders an access-denied state instead.
export class ForbiddenError extends Error {
  constructor(public readonly path: string) {
    super(`${path} → 403 (forbidden)`);
    this.name = 'ForbiddenError';
  }
}

export function isForbidden(err: unknown): err is ForbiddenError {
  return err instanceof ForbiddenError;
}

// Loop guard for the reactive-401 → login redirect. A 401 means "no session",
// so we hand the browser to the BFF login route. But if we land back here and
// immediately get another 401, redirecting again creates an infinite
// login → callback → 401 → login storm. So redirect only if we haven't already
// tried within the window; otherwise give up and let the caller show an error.
// AuthProvider clears the marker on a successful /auth/me, so a later genuine
// logout can start login afresh.
const LOGIN_GUARD_KEY = 'bff:lastLoginRedirect';
const LOGIN_RETRY_WINDOW_MS = 10_000;

export function startLogin(): boolean {
  let last = 0;
  try { last = Number(sessionStorage.getItem(LOGIN_GUARD_KEY) ?? '0'); } catch { /* private mode */ }
  if (Date.now() - last < LOGIN_RETRY_WINDOW_MS) {
    return false; // bounced through login too recently → stop the storm
  }
  try { sessionStorage.setItem(LOGIN_GUARD_KEY, String(Date.now())); } catch { /* ignore */ }
  window.location.assign(LOGIN_URL);
  return true;
}

export function clearLoginGuard(): void {
  try { sessionStorage.removeItem(LOGIN_GUARD_KEY); } catch { /* ignore */ }
}

async function handleStatus(res: Response, path: string): Promise<void> {
  if (res.status === 403) {
    throw new ForbiddenError(path);
  }
  if (res.status === 401) {
    if (startLogin()) {
      throw new Error(`${path} → 401 (signed out, redirecting to login)`);
    }
    throw new Error(`${path} → 401 (login loop suppressed; please reload)`);
  }
  if (!res.ok) {
    throw new Error(await errorMessage(res, path));
  }
}

async function getJson<T>(path: string, params?: Record<string, string | number | null | undefined>): Promise<T> {
  const url = params
    ? `${path}?${new URLSearchParams(
        Object.entries(params)
          .filter(([, v]) => v !== null && v !== undefined)
          .map(([k, v]) => [k, String(v)])
      ).toString()}`
    : path;
  const res = await fetch(url, {
    credentials: 'include',
    headers: { Accept: 'application/json' }
  });
  await handleStatus(res, path);
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
  const res = await fetch(url, { method: 'POST', credentials: 'include', body: fd });
  await handleStatus(res, path);
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
