import { useState, type FormEvent } from 'react';
import {
  useDeleteLinkedIdentity,
  useLinkedIdentities,
  useUpsertLinkedIdentity
} from '../queries';
import { isForbidden, type LinkedIdentityBinding } from '../api';

/**
 * Cross-domain SSO linked-identity admin CRUD. Gated to gw-admin both at the
 * BFF (@Secured("gwAdmin")) and at P1 (gwAdminFlag) — see cross-domain-sso.md
 * §3.1 / §6 for the SOC2 control envelope.
 *
 * <p>Production-shape but deliberately minimal: a list table on the left, a
 * single upsert form on the right that doubles as both "create new" and "edit
 * existing", and per-row soft-delete / re-activate. Hard delete is not surfaced
 * in the UI — the audit-preserving soft path is what admins should use; the
 * hard branch is left to the curl path for GDPR escape hatches.</p>
 */
export function LinkedIdentitiesPage() {
  const listQ = useLinkedIdentities();
  const upsertM = useUpsertLinkedIdentity();
  const deleteM = useDeleteLinkedIdentity();

  // Local form state. Pre-filling by clicking a row populates this; clear
  // resets to a blank create form.
  const [form, setForm] = useState<{
    sourceUserUuid: string;
    targetClient: string;
    targetUserUuid: string;
    mfaRequired: boolean;
    active: boolean;
    isExisting: boolean;
  }>({
    sourceUserUuid: '',
    targetClient: 'demo-trading-client',
    targetUserUuid: '',
    mfaRequired: false,
    active: true,
    isExisting: false
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    upsertM.mutate(
      {
        sourceUserUuid: form.sourceUserUuid.trim(),
        targetClient: form.targetClient.trim(),
        targetUserUuid: form.targetUserUuid.trim(),
        mfaRequired: form.mfaRequired,
        active: form.active
      },
      {
        onSuccess: () => {
          // Reset to a blank create form after a successful write — admins
          // typically provision multiple bindings in sequence.
          setForm((f) => ({
            ...f,
            sourceUserUuid: '',
            targetUserUuid: '',
            mfaRequired: false,
            active: true,
            isExisting: false
          }));
        }
      }
    );
  };

  const loadRow = (row: LinkedIdentityBinding) => {
    setForm({
      sourceUserUuid: row.sourceUserUuid,
      targetClient: row.targetClient,
      targetUserUuid: row.targetUserUuid,
      mfaRequired: row.mfaRequired,
      active: row.active,
      isExisting: true
    });
  };

  const onDelete = (row: LinkedIdentityBinding) => {
    if (!confirm(`Soft-delete binding ${row.sourceUserUuid} → ${row.targetClient}?`)) {
      return;
    }
    deleteM.mutate({
      sourceUserUuid: row.sourceUserUuid,
      targetClient: row.targetClient
    });
  };

  return (
    <div>
      <header className="page-head">
        <h1>Linked identities</h1>
        <p className="muted">
          Cross-domain SSO bindings — administratively provisioned mappings of
          {' '}<code>(source identity, target audience)</code> → target identity.
          See <code>cross-domain-sso.md</code> for the SOC2 control envelope.
        </p>
      </header>

      {isForbidden(listQ.error) ? (
        <p className="error">
          You don&rsquo;t have gw-admin access. This screen is gw-admin only.
        </p>
      ) : listQ.isError && (
        <p className="error">Failed to load: {(listQ.error as Error).message}</p>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 360px', gap: '1.5rem' }}>
        <section className="panel">
          <h2>Provisioned bindings</h2>
          {listQ.isPending && <p className="muted">Loading&hellip;</p>}
          {listQ.data && listQ.data.bindings.length === 0 && (
            <p className="muted">No bindings provisioned yet.</p>
          )}
          {listQ.data && listQ.data.bindings.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Source UUID</th>
                  <th>Target client</th>
                  <th>Target UUID</th>
                  <th>MFA</th>
                  <th>Active</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {listQ.data.bindings.map((row) => (
                  <tr key={`${row.sourceUserUuid}|${row.targetClient}`}
                      style={{ opacity: row.active ? 1 : 0.5 }}>
                    <td><code>{shortenUuid(row.sourceUserUuid)}</code></td>
                    <td>{row.targetClient}</td>
                    <td><code>{shortenUuid(row.targetUserUuid)}</code></td>
                    <td>{row.mfaRequired ? 'required' : '—'}</td>
                    <td>{row.active ? 'active' : 'soft-deleted'}</td>
                    <td>
                      <button type="button" className="link" onClick={() => loadRow(row)}>
                        Edit
                      </button>
                      {' · '}
                      <button type="button" className="link"
                              onClick={() => onDelete(row)}
                              disabled={!row.active}>
                        Soft-delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="panel">
          <h2>{form.isExisting ? 'Edit binding' : 'Provision a binding'}</h2>
          <form onSubmit={onSubmit}>
            <label>
              <span>Source user UUID</span>
              <input
                type="text"
                value={form.sourceUserUuid}
                onChange={(e) => setForm({ ...form, sourceUserUuid: e.target.value })}
                placeholder="32-char P1 user UUID"
                readOnly={form.isExisting}
                required
              />
            </label>
            <label>
              <span>Target OIDC client</span>
              <select
                value={form.targetClient}
                onChange={(e) => setForm({ ...form, targetClient: e.target.value })}
                disabled={form.isExisting}
              >
                <option value="demo-billing-client">demo-billing-client</option>
                <option value="demo-trading-client">demo-trading-client</option>
                <option value="demo-users-client">demo-users-client</option>
              </select>
            </label>
            <label>
              <span>Target user UUID</span>
              <input
                type="text"
                value={form.targetUserUuid}
                onChange={(e) => setForm({ ...form, targetUserUuid: e.target.value })}
                placeholder="32-char P1 user UUID"
                required
              />
            </label>
            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.mfaRequired}
                onChange={(e) => setForm({ ...form, mfaRequired: e.target.checked })}
              />
              <span>MFA required on swap</span>
            </label>
            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(e) => setForm({ ...form, active: e.target.checked })}
              />
              <span>Active (uncheck to soft-disable)</span>
            </label>
            <div className="form-actions">
              <button type="submit" disabled={upsertM.isPending}>
                {form.isExisting ? 'Update' : 'Create'}
              </button>
              {form.isExisting && (
                <button type="button" className="link"
                        onClick={() => setForm({
                          sourceUserUuid: '',
                          targetClient: 'demo-trading-client',
                          targetUserUuid: '',
                          mfaRequired: false,
                          active: true,
                          isExisting: false
                        })}>
                  Cancel
                </button>
              )}
            </div>
            {upsertM.isError && (
              <p className="error small">{(upsertM.error as Error).message}</p>
            )}
            {upsertM.isSuccess && (
              <p className="muted small">Saved.</p>
            )}
          </form>
        </section>
      </div>
    </div>
  );
}

/** Display only the first 8 chars of a UUID to keep the table readable. */
function shortenUuid(uuid: string): string {
  if (!uuid) return '';
  if (uuid.length <= 12) return uuid;
  return uuid.slice(0, 8) + '…' + uuid.slice(-4);
}
