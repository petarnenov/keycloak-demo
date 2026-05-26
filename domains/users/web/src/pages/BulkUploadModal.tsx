import { useState } from 'react';
import type { BulkUploadRow } from '../api';
import { useBulkCreate, useUploadBulkFile } from '../queries';

interface Props {
  firmCd: number;
  onClose: () => void;
}

// Two-step bulk-create flow (mirrors the GeoWealth FE):
//   step 1: upload a CSV → server validates each row, returns
//           [{ genericUserJTO, errors[] }, …].
//   step 2: review the parsed rows; rows with errors are highlighted and
//           NOT submitted; the rest are committed via /bulkCreateEmployees.
//
// Expected CSV columns:
//   username,firstName,lastName,emailAddress,contactTypeCd,rolesCds,
//   defaultRoleCd,gwAdminFlag,mfaEnabledFlag,sendInviteFlag,customWhitelabelCode
//
// rolesCds is pipe-separated (e.g. "1|3"); booleans are "true"/"false".

export function BulkUploadModal({ firmCd, onClose }: Props) {
  const upload = useUploadBulkFile();
  const create = useBulkCreate(firmCd);

  const [file, setFile] = useState<File | null>(null);
  const [rows, setRows] = useState<BulkUploadRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [createdMessage, setCreatedMessage] = useState<string | null>(null);

  const onUpload = async () => {
    if (!file) return;
    setError(null);
    try {
      const res = await upload.mutateAsync({ firmCd, file });
      setRows(res.results);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const onCommit = async () => {
    if (!rows) return;
    setError(null);
    try {
      const res = await create.mutateAsync(rows);
      const failed = res.failedRecords?.length ?? 0;
      setCreatedMessage(
        failed > 0
          ? `Created ${res.createdCount}, ${failed} rejected by server. See errors below.`
          : `Created ${res.createdCount} users successfully.`
      );
      if (failed > 0) {
        // Merge BFF-reported errors back into the displayed grid so the
        // operator can fix and retry without re-uploading. Match by username.
        const errsByUsername = new Map<string, BulkUploadRow['errors']>();
        for (const r of res.failedRecords) {
          const username = (r.genericUserJTO as { username?: string }).username;
          if (username) errsByUsername.set(username, r.errors);
        }
        setRows((current) =>
          current
            ? current.map((r) => {
                const username = (r.genericUserJTO as { username?: string }).username ?? '';
                const e = errsByUsername.get(username);
                return e ? { ...r, errors: e } : r;
              })
            : current
        );
      } else {
        // All committed — close on the next user action.
      }
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const cleanCount = rows ? rows.filter((r) => (r.errors?.length ?? 0) === 0).length : 0;
  const errorCount = rows ? rows.length - cleanCount : 0;

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal modal-wide">
        <header className="modal-head">
          <h2>Bulk Create From Upload</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">✕</button>
        </header>
        <div className="modal-body">
          {!rows && (
            <>
              <p className="muted">
                Pick a CSV with header columns: <code>username, firstName, lastName, emailAddress,
                contactTypeCd, rolesCds, defaultRoleCd, gwAdminFlag, mfaEnabledFlag,
                sendInviteFlag, customWhitelabelCode</code>.
                Multiple roles go in <code>rolesCds</code> separated by <code>|</code>.
              </p>
              <input
                type="file"
                accept=".csv,text/csv"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <div className="footer-right" style={{ marginTop: 16 }}>
                <button type="button" className="ghost-button" onClick={onClose}>Cancel</button>
                <button type="button" onClick={onUpload} disabled={!file || upload.isPending}>
                  {upload.isPending ? 'Uploading…' : 'Validate'}
                </button>
              </div>
            </>
          )}

          {rows && (
            <>
              <p className="muted">
                Parsed <strong>{rows.length}</strong> row{rows.length === 1 ? '' : 's'} —{' '}
                <span className="pos">{cleanCount} valid</span>,{' '}
                <span className="neg">{errorCount} with errors</span>. Only valid rows will be created.
              </p>
              {createdMessage && <p className={errorCount > 0 ? 'error' : 'success'}>{createdMessage}</p>}
              <div className="grid-wrap">
                <table className="users-grid">
                  <thead>
                    <tr>
                      <th>Username</th>
                      <th>First / Name</th>
                      <th>Last</th>
                      <th>Email</th>
                      <th>Roles</th>
                      <th>Errors</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r, i) => {
                      const u = r.genericUserJTO as {
                        username?: string;
                        firstName?: string;
                        lastName?: string;
                        emailAddress?: string;
                        rolesCds?: number[];
                      };
                      const hasErr = (r.errors?.length ?? 0) > 0;
                      return (
                        <tr key={i} className={hasErr ? 'row-error' : ''}>
                          <td><strong>{u.username ?? '—'}</strong></td>
                          <td>{u.firstName ?? '—'}</td>
                          <td>{u.lastName ?? '—'}</td>
                          <td className="muted small">{u.emailAddress ?? '—'}</td>
                          <td className="muted small">{(u.rolesCds ?? []).join(', ') || '—'}</td>
                          <td className={hasErr ? 'neg small' : 'muted small'}>
                            {hasErr ? r.errors.map((e) => `${e.field}: ${e.error}`).join('; ') : '—'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <div className="footer-right" style={{ marginTop: 16 }}>
                <button type="button" className="ghost-button" onClick={() => { setRows(null); setFile(null); setCreatedMessage(null); }}>
                  Re-upload
                </button>
                <button type="button" className="ghost-button" onClick={onClose}>Close</button>
                <button type="button" onClick={onCommit} disabled={cleanCount === 0 || create.isPending}>
                  {create.isPending ? 'Creating…' : `Create ${cleanCount} user${cleanCount === 1 ? '' : 's'}`}
                </button>
              </div>
            </>
          )}

          {error && <p className="error">{error}</p>}
        </div>
      </div>
    </div>
  );
}
