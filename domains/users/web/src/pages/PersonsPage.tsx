import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { personsApi, type Person } from '../api';
import { PersonModal } from './PersonModal';

const TENANTS = ['billing', 'trading', 'users'] as const;

/**
 * Persons tab — DB-backed editor for the cross-subdomain SSO person
 * registry that PersonRegistry.java consults at SAML emission time. One
 * row per physical person; each person carries:
 *
 *   - personId (stable across logins; surfaces as the {@code personId}
 *     OIDC claim and the SAML NameID)
 *   - usernames (LDAP_UIDs that resolve to this person)
 *   - aliases (per-tenant username; surfaces as {@code tenant_identity})
 *   - roles (per-tenant role list; surfaces in the {@code roles} claim
 *     scoped to that subdomain's OIDC client)
 *
 * Writes go straight through the BFF → P1 → PersonRegistryManager, which
 * commits the new shape to PERSON_REGISTRY_TBL and atomically swaps its
 * in-memory snapshot — the next login sees the new values.
 */
export function PersonsPage() {
  const qc = useQueryClient();
  const personsQ = useQuery({
    queryKey: ['persons'],
    queryFn: () => personsApi.list(),
  });

  const [editing, setEditing] = useState<Person | 'new' | null>(null);

  const deleteMut = useMutation({
    mutationFn: (personId: string) => personsApi.delete(personId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['persons'] }),
  });

  if (personsQ.isLoading) {
    return (
      <div>
        <PageHeader onCreate={() => setEditing('new')} />
        <p className="muted">Loading persons…</p>
      </div>
    );
  }

  if (personsQ.error) {
    return (
      <div>
        <PageHeader onCreate={() => setEditing('new')} />
        <p className="error">Failed to load: {String((personsQ.error as Error).message)}</p>
      </div>
    );
  }

  const persons = personsQ.data?.persons ?? [];

  return (
    <div>
      <PageHeader onCreate={() => setEditing('new')} />

      <h2>PERSONS ({persons.length})</h2>
      {persons.length === 0 ? (
        <p className="muted">No persons registered. Use “Add Person” above.</p>
      ) : (
        <PersonsGrid
          persons={persons}
          onEdit={(p) => setEditing(p)}
          onDelete={(p) => {
            if (window.confirm(`Delete person ${p.personId}? This removes their tenant aliases and roles.`)) {
              deleteMut.mutate(p.personId);
            }
          }}
        />
      )}

      {editing && (
        <PersonModal
          mode={editing === 'new' ? 'new' : 'edit'}
          person={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            qc.invalidateQueries({ queryKey: ['persons'] });
            setEditing(null);
          }}
        />
      )}
    </div>
  );
}

function PageHeader({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="page-head">
      <h1>Persons</h1>
      <p className="muted">Cross-subdomain SSO registry — manage which P1 login(s) represent each physical person, and what alias + roles each tenant sees.</p>
      <div className="actions">
        <button type="button" onClick={onCreate}>Add Person</button>
      </div>
    </div>
  );
}

interface GridProps {
  persons: Person[];
  onEdit: (p: Person) => void;
  onDelete: (p: Person) => void;
}

function PersonsGrid({ persons, onEdit, onDelete }: GridProps) {
  return (
    <table className="users-grid">
      <thead>
        <tr>
          <th>PERSON ID</th>
          <th>DISPLAY NAME</th>
          <th>USERNAMES</th>
          {TENANTS.map((t) => (
            <th key={t}>{t.toUpperCase()} ALIAS</th>
          ))}
          <th></th>
        </tr>
      </thead>
      <tbody>
        {persons.map((p) => (
          <tr key={p.personId}>
            <td><code>{p.personId}</code></td>
            <td>{p.displayName ?? '—'}</td>
            <td>{p.usernames.length ? p.usernames.join(', ') : '—'}</td>
            {TENANTS.map((t) => (
              <td key={t}>{p.aliases[t] ?? '—'}</td>
            ))}
            <td className="row-actions">
              <button type="button" className="link-button" onClick={() => onEdit(p)}>Edit</button>
              <button type="button" className="link-button danger" onClick={() => onDelete(p)}>Delete</button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
