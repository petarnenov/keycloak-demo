import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import type { FirmsResponse, User, DropdownOptions } from '../api';
import { useDropdowns, useUsers } from '../queries';
import { AddEditUserModal } from './AddEditUserModal';
import { BulkUploadModal } from './BulkUploadModal';

interface Props {
  firmCd: number | null;
  firmsQuery: UseQueryResult<FirmsResponse, Error>;
}

// Top-level Users & Access page. Mirrors the P1 FirmAdmin/UsersAndAccess shell:
//   1. Firm picker (only shown when the caller has cross-firm access).
//   2. Header buttons: Create New User, Bulk Create From Upload.
//   3. Users grid with First/Last name, username, email, roles + actions
//      (Refresh policy + Edit).
//
// The grid is plain HTML (no ag-grid in this demo — the GeoWealth page uses
// GwGridPersist, but reproducing that internal module isn't the point).

export function UsersAndAccessPage({ firmCd, firmsQuery }: Props) {
  const navigate = useNavigate();
  const usersQ = useUsers(firmCd);
  const dropdownsQ = useDropdowns(firmCd);

  const [editing, setEditing] = useState<User | 'new' | null>(null);
  const [bulkOpen, setBulkOpen] = useState(false);

  const firmsErr = firmsQuery.error?.message;
  const usersErr = usersQ.error?.message;
  const dropdownsErr = dropdownsQ.error?.message;
  const error = firmsErr || usersErr || dropdownsErr;

  const isSSORequired = usersQ.data?.firmSSO?.enforcementType === 'MANDATORY';
  const users = usersQ.data?.rows ?? [];
  const dropdowns = dropdownsQ.data ?? null;
  const hasCross = firmsQuery.data?.hasCrossFirmAccess ?? false;
  const firms = firmsQuery.data?.options ?? [];
  const firmName = firms.find((f) => f.firmCd === firmCd)?.firmName ?? null;

  return (
    <div>
      <header className="page-head">
        <h1>Firm Admin · Users &amp; Access</h1>
        <p className="muted">
          {firmName
            ? `Manage users for ${firmName} (firmCd ${firmCd})`
            : 'Pick a firm to view its users.'}
        </p>
      </header>

      {error && <p className="error">Failed to load: {error}</p>}

      <section className="panel">
        <div className="toolbar">
          {hasCross && (
            <label className="field">
              <span className="field-label">Firm</span>
              <select
                value={firmCd ?? ''}
                onChange={(e) => {
                  const v = e.target.value;
                  navigate(v === '' ? '/' : `/users/${v}`);
                }}
              >
                <option value="">— Select firm —</option>
                {firms.map((f) => (
                  <option key={f.firmCd} value={f.firmCd}>
                    {f.firmName} ({f.firmCd})
                  </option>
                ))}
              </select>
            </label>
          )}

          {firmCd != null && (
            <div className="toolbar-actions">
              <button type="button" onClick={() => setEditing('new')} disabled={!dropdowns}>
                Create New User
              </button>
              <button
                type="button"
                className="link-button"
                onClick={() => setBulkOpen(true)}
                disabled={!dropdowns}
              >
                Bulk Create From Upload
              </button>
              {isSSORequired && (
                <span className="badge">SSO mandatory — passwords disabled, invites suppressed</span>
              )}
            </div>
          )}
        </div>
      </section>

      {firmCd != null && (
        <section className="panel">
          <h2>Users ({users.length})</h2>
          {usersQ.isPending && <p className="muted">Loading&hellip;</p>}
          {!usersQ.isPending && users.length === 0 && (
            <p className="muted">No users for this firm yet. Use “Create New User” above.</p>
          )}
          {users.length > 0 && dropdowns && (
            <UsersGrid users={users} dropdowns={dropdowns} onEdit={(u) => setEditing(u)} />
          )}
        </section>
      )}

      {editing && dropdowns && firmCd != null && (
        <AddEditUserModal
          firmCd={firmCd}
          dropdowns={dropdowns}
          isSSORequired={isSSORequired}
          user={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {bulkOpen && firmCd != null && (
        <BulkUploadModal firmCd={firmCd} onClose={() => setBulkOpen(false)} />
      )}
    </div>
  );
}

interface GridProps {
  users: User[];
  dropdowns: DropdownOptions;
  onEdit: (u: User) => void;
}

function UsersGrid({ users, dropdowns, onEdit }: GridProps) {
  const roleNameByCd = useMemo(() => {
    const map: Record<string, string> = {};
    for (const [cd, role] of Object.entries(dropdowns.roles)) map[cd] = role.name;
    return map;
  }, [dropdowns.roles]);

  return (
    <div className="grid-wrap">
      <table className="users-grid">
        <thead>
          <tr>
            <th>First Name</th>
            <th>Last Name</th>
            <th>Username</th>
            <th>Email</th>
            <th>Roles</th>
            <th>Default Role</th>
            <th>GW Admin</th>
            <th>MFA</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.userId}>
              <td>{u.firstName}</td>
              <td>{u.lastName ?? '—'}</td>
              <td><strong>{u.username}</strong></td>
              <td className="muted small">{u.emailAddress}</td>
              <td>
                {(u.rolesCds ?? []).map((cd) => roleNameByCd[String(cd)] ?? `#${cd}`).join(', ') || '—'}
              </td>
              <td>{roleNameByCd[String(u.defaultRoleCd)] ?? '—'}</td>
              <td>{u.gwAdminFlag ? <span className="pill pill-on">Yes</span> : <span className="pill pill-off">No</span>}</td>
              <td>{u.mfaEnabledFlag ? <span className="pill pill-on">On</span> : <span className="pill pill-off">Off</span>}</td>
              <td className="row-actions">
                <button type="button" className="link-button" onClick={() => onEdit(u)}>
                  Edit
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
