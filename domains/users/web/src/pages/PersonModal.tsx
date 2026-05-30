import { useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { personsApi, type Person } from '../api';

const TENANTS = ['billing', 'trading', 'users'] as const;

interface Props {
  mode: 'new' | 'edit';
  person: Person | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Add/Edit Person modal — drives the (person, tenant) → (alias, roles)
 * registry. Single payload PUT; the server atomically swaps the
 * in-memory snapshot used by PersonRegistry's static lookups.
 *
 * Roles are entered as comma-separated strings per tenant, matching the
 * shape PersonRegistry expects ("client", "advisor", "users-admin", …).
 * Usernames are comma-separated too — each entry is a P1 LDAP_UID that
 * resolves to this person.
 */
export function PersonModal({ mode, person, onClose, onSaved }: Props) {
  const [personId, setPersonId] = useState(person?.personId ?? '');
  const [displayName, setDisplayName] = useState(person?.displayName ?? '');
  const [usernames, setUsernames] = useState((person?.usernames ?? []).join(', '));
  const [aliases, setAliases] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    for (const t of TENANTS) init[t] = person?.aliases[t] ?? '';
    return init;
  });
  const [roles, setRoles] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    for (const t of TENANTS) init[t] = (person?.roles[t] ?? []).join(', ');
    return init;
  });
  const [error, setError] = useState<string | null>(null);

  const upsertMut = useMutation({
    mutationFn: (p: Person) => personsApi.upsert(p),
    onSuccess: () => onSaved(),
    onError: (e: Error) => setError(e.message),
  });

  function submit(ev: FormEvent) {
    ev.preventDefault();
    setError(null);
    if (!personId.trim()) {
      setError('personId is required.');
      return;
    }
    const payload: Person = {
      personId: personId.trim(),
      displayName: displayName.trim() || null,
      usernames: usernames.split(',').map((s) => s.trim()).filter(Boolean),
      aliases: Object.fromEntries(
        TENANTS.map((t) => [t, aliases[t].trim()]).filter(([, v]) => v.length > 0)
      ),
      roles: Object.fromEntries(
        TENANTS.map((t) => [t, roles[t].split(',').map((s) => s.trim()).filter(Boolean)])
      ),
    };
    upsertMut.mutate(payload);
  }

  const isEdit = mode === 'edit';

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>{isEdit ? `Edit person: ${person?.personId}` : 'Add person'}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">✕</button>
        </header>
        <form onSubmit={submit}>
          <div className="modal-body">
            <div className="form-grid">
              <label className="field">
                <span className="field-label">Person ID *</span>
                <input
                  value={personId}
                  onChange={(e) => setPersonId(e.target.value)}
                  disabled={isEdit}
                  required
                  placeholder="P-bob"
                />
              </label>
              <label className="field">
                <span className="field-label">Display name</span>
                <input
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="Bob the Builder"
                />
              </label>
              <label className="field field-full">
                <span className="field-label">P1 usernames (comma-separated LDAP_UIDs)</span>
                <input
                  value={usernames}
                  onChange={(e) => setUsernames(e.target.value)}
                  placeholder="bob1, bob5, bob10"
                />
              </label>

              <fieldset className="field field-full">
                <legend>Per-tenant aliases &amp; roles</legend>
                {TENANTS.map((t) => (
                  <div key={t} className="tenant-row">
                    <span className="tenant-label">{t}</span>
                    <input
                      className="alias-input"
                      placeholder={`${t} alias (e.g. bob1)`}
                      value={aliases[t]}
                      onChange={(e) => setAliases((prev) => ({ ...prev, [t]: e.target.value }))}
                    />
                    <input
                      className="roles-input"
                      placeholder={`${t} roles (comma-separated)`}
                      value={roles[t]}
                      onChange={(e) => setRoles((prev) => ({ ...prev, [t]: e.target.value }))}
                    />
                  </div>
                ))}
              </fieldset>
            </div>
            {error && <p className="error">{error}</p>}
          </div>
          <footer className="modal-foot">
            <button type="button" onClick={onClose}>Cancel</button>
            <button type="submit" disabled={upsertMut.isPending}>
              {upsertMut.isPending ? 'Saving…' : 'Save'}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
