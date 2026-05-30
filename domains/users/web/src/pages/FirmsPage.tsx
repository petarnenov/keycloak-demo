import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { firmsApi, usersApi, type NewFirmBody, type FirmOption } from '../api';

/**
 * Firms tab — add / deactivate firms in {@code FIRM_TBL}.
 *
 * <p>Read uses the existing {@code /api/users/firms} list endpoint;
 * write goes through the new {@code firmsApi}. Each newly created firm
 * automatically gets {@code Admins} and {@code All Employees} mandatory
 * roles seeded on the P1 side, so the Edit User modal has something
 * sensible to show when the picker switches to it.</p>
 */
export function FirmsPage() {
  const qc = useQueryClient();
  const firmsQ = useQuery({
    queryKey: ['firms'],
    queryFn: () => usersApi.firms(),
  });

  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const deactivateMut = useMutation({
    mutationFn: (firmCd: number) => firmsApi.deactivate(firmCd),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['firms'] }),
    onError: (e: Error) => setError(e.message),
  });

  if (firmsQ.isLoading) {
    return (
      <div>
        <Header onAdd={() => setAdding(true)} />
        <p className="muted">Loading firms…</p>
      </div>
    );
  }
  if (firmsQ.error) {
    return (
      <div>
        <Header onAdd={() => setAdding(true)} />
        <p className="error">Failed to load: {(firmsQ.error as Error).message}</p>
      </div>
    );
  }

  const firms = firmsQ.data?.options ?? [];
  const ownFirm = firmsQ.data?.ownFirmCd ?? null;

  return (
    <div>
      <Header onAdd={() => setAdding(true)} />
      {error && <p className="error">{error}</p>}
      <h2>FIRMS ({firms.length})</h2>
      <FirmsGrid
        firms={firms}
        ownFirmCd={ownFirm}
        onDeactivate={(f) => {
          setError(null);
          if (
            window.confirm(
              `Deactivate ${f.firmName} (firmCd ${f.firmCd})? The firm vanishes from the picker but its rows remain in DB.`
            )
          ) {
            deactivateMut.mutate(f.firmCd);
          }
        }}
      />
      {adding && (
        <AddFirmModal
          onClose={() => setAdding(false)}
          onSaved={() => {
            qc.invalidateQueries({ queryKey: ['firms'] });
            setAdding(false);
          }}
        />
      )}
    </div>
  );
}

function Header({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="page-head">
      <h1>Firms</h1>
      <p className="muted">
        Add a firm to the picker. New firms get <code>Admins</code> and{' '}
        <code>All Employees</code> mandatory roles seeded automatically.
      </p>
      <div className="actions">
        <button type="button" onClick={onAdd}>Add Firm</button>
      </div>
    </div>
  );
}

interface GridProps {
  firms: FirmOption[];
  ownFirmCd: number | null;
  onDeactivate: (f: FirmOption) => void;
}

function FirmsGrid({ firms, ownFirmCd, onDeactivate }: GridProps) {
  if (firms.length === 0) {
    return <p className="muted">No firms visible (you may not have cross-firm access).</p>;
  }
  return (
    <table className="users-grid">
      <thead>
        <tr>
          <th>FIRM CD</th>
          <th>NAME</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {firms.map((f) => (
          <tr key={f.firmCd}>
            <td><code>{f.firmCd}</code></td>
            <td>
              {f.firmName}
              {f.firmCd === ownFirmCd && <span className="muted small"> (your firm)</span>}
              {f.firmCd === 1 && <span className="muted small"> (GeoWealth — system)</span>}
            </td>
            <td className="row-actions">
              {f.firmCd === 1 ? (
                <span className="muted small">protected</span>
              ) : (
                <button type="button" className="link-button danger" onClick={() => onDeactivate(f)}>
                  Deactivate
                </button>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

interface ModalProps {
  onClose: () => void;
  onSaved: () => void;
}

function AddFirmModal({ onClose, onSaved }: ModalProps) {
  const [firmCd, setFirmCd] = useState<string>('');
  const [firmName, setFirmName] = useState('');
  const [code, setCode] = useState('');
  const [err, setErr] = useState<string | null>(null);

  const createMut = useMutation({
    mutationFn: (body: NewFirmBody) => firmsApi.create(body),
    onSuccess: () => onSaved(),
    onError: (e: Error) => setErr(e.message),
  });

  function submit(ev: React.FormEvent) {
    ev.preventDefault();
    setErr(null);
    if (!firmName.trim()) {
      setErr('firmName is required.');
      return;
    }
    const body: NewFirmBody = { firmName: firmName.trim() };
    if (firmCd.trim()) {
      const n = Number(firmCd);
      if (!Number.isInteger(n) || n <= 0) {
        setErr('firmCd must be a positive integer (or leave empty to auto-pick).');
        return;
      }
      body.firmCd = n;
    }
    if (code.trim()) body.code = code.trim();
    createMut.mutate(body);
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>Add Firm</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">✕</button>
        </header>
        <form onSubmit={submit}>
          <div className="modal-body">
            <div className="form-grid">
              <label className="field">
                <span className="field-label">Firm CD (leave empty to auto-pick)</span>
                <input
                  inputMode="numeric"
                  value={firmCd}
                  onChange={(e) => setFirmCd(e.target.value)}
                  placeholder="e.g. 12"
                />
              </label>
              <label className="field">
                <span className="field-label">Firm name *</span>
                <input
                  value={firmName}
                  onChange={(e) => setFirmName(e.target.value)}
                  placeholder="Acme Capital"
                  required
                />
              </label>
              <label className="field">
                <span className="field-label">Code (short ID — auto-derived from name if blank)</span>
                <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="ACME" />
              </label>
            </div>
            {err && <p className="error">{err}</p>}
          </div>
          <footer className="modal-foot">
            <button type="button" onClick={onClose}>Cancel</button>
            <button type="submit" disabled={createMut.isPending}>
              {createMut.isPending ? 'Creating…' : 'Create Firm'}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
