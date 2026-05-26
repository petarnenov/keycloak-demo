import { useEffect, useMemo, useState, type FormEvent } from 'react';
import type { User, DropdownOptions } from '../api';
import { useRedoPolicy, useUpsertUser } from '../queries';

interface Props {
  firmCd: number;
  dropdowns: DropdownOptions;
  isSSORequired: boolean;
  user: User | null;
  onClose: () => void;
}

// Add/Edit user modal. Faithful to the GeoWealth FirmAdmin/AddEditUserForm:
// contact type, names, username, email, password (only when not SSO and
// only on create — disabled on edit), white-label code, GW Admin, MFA,
// default role + roles checkboxes, send-invite.
//
// Password is only collected when SSO is OPTIONAL and we're creating a new
// user; the BFF strips it on persistence regardless. The same constraint
// would apply in the real P1 code path.

const CONTACT_TYPES: Record<string, string> = {
  '1': 'Individual',
  '2': 'Entity'
};

const PASSWORD_REGEX = /^(?=.*?[A-Z])(?=.*?[a-z])(?=.*?[0-9])(?=.*?[#?!@$%^&*\-]).{8,}$/;

export function AddEditUserModal({ firmCd, dropdowns, isSSORequired, user, onClose }: Props) {
  const isEdit = !!user;
  const upsert = useUpsertUser(firmCd);
  const redoPolicy = useRedoPolicy();

  // Pre-populate the form. On create, default to Individual + the role flagged
  // defaultFlag (mirrors how the real form initialises with mandatory roles
  // pre-checked).
  const initialRolesCds = useMemo(() => {
    if (user) return new Set<number>(user.rolesCds ?? []);
    const initial = new Set<number>();
    for (const [cd, role] of Object.entries(dropdowns.roles)) {
      if (role.defaultFlag) initial.add(Number(cd));
    }
    return initial;
  }, [user, dropdowns.roles]);

  const [contactTypeCd, setContactTypeCd] = useState<number>(user?.contactTypeCd ?? 1);
  const [firstName, setFirstName] = useState(user?.firstName ?? '');
  const [lastName, setLastName] = useState(user?.lastName ?? '');
  const [username, setUsername] = useState(user?.username ?? '');
  const [emailAddress, setEmailAddress] = useState(user?.emailAddress ?? '');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [customWhitelabelCode, setCustomWhitelabelCode] = useState<string>(user?.customWhitelabelCode ?? '');
  const [gwAdminFlag, setGwAdminFlag] = useState(user?.gwAdminFlag ?? false);
  const [mfaEnabledFlag, setMfaEnabledFlag] = useState<boolean>(isEdit ? !!user?.mfaEnabledFlag : true);
  const [defaultRoleCd, setDefaultRoleCd] = useState<number | ''>(user?.defaultRoleCd ?? '');
  const [rolesCds, setRolesCds] = useState<Set<number>>(initialRolesCds);
  const [sendInviteFlag, setSendInviteFlag] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // GW Admin clears the white-label code (matches real form's disabled state).
  useEffect(() => {
    if (gwAdminFlag) setCustomWhitelabelCode('');
  }, [gwAdminFlag]);

  const showLastName = contactTypeCd === 1;
  const passwordsDisabled = isEdit || isSSORequired;

  const roleEntries = Object.entries(dropdowns.roles).map(([cd, r]) => ({
    cd: Number(cd),
    name: r.name,
    defaultFlag: r.defaultFlag
  }));

  const onToggleRole = (cd: number, checked: boolean) => {
    setRolesCds((prev) => {
      const next = new Set(prev);
      if (checked) next.add(cd);
      else next.delete(cd);
      return next;
    });
  };

  const validate = (): string | null => {
    if (!firstName.trim()) return 'First name (or Name) is required.';
    if (!username.trim()) return 'Username is required.';
    if (emailAddress && !/^\S+@\S+\.\S+$/.test(emailAddress)) return 'Email address is not valid.';
    if (!passwordsDisabled && (password || confirmPassword)) {
      if (!PASSWORD_REGEX.test(password)) {
        return 'Password must be ≥ 8 chars and contain upper, lower, digit, and special.';
      }
      if (password !== confirmPassword) return 'Passwords do not match.';
    }
    if (rolesCds.size === 0) return 'At least one role is required.';
    if (!defaultRoleCd) return 'Default role is required.';
    if (defaultRoleCd && !rolesCds.has(Number(defaultRoleCd))) {
      return 'Default role must be one of the selected roles.';
    }
    return null;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const v = validate();
    if (v) {
      setError(v);
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      const payload: Partial<User> & Record<string, unknown> = {
        ...(user ?? {}),
        firmCd,
        contactTypeCd,
        firstName: firstName.trim(),
        lastName: showLastName ? lastName.trim() : null,
        username: username.trim(),
        emailAddress: emailAddress.trim(),
        customWhitelabelCode: customWhitelabelCode || null,
        gwAdminFlag,
        mfaEnabledFlag,
        defaultRoleCd: Number(defaultRoleCd),
        rolesCds: Array.from(rolesCds)
      };
      if (!isEdit && !passwordsDisabled && password) {
        payload.password = password;
      }
      if (!isEdit) {
        payload.sendInviteFlag = isSSORequired ? false : sendInviteFlag;
      } else {
        // Match real FE: don't ship sendInviteFlag on edit.
        delete payload.sendInviteFlag;
      }
      await upsert.mutateAsync(payload as Partial<User>);
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const onRedoPolicy = async () => {
    if (!user) return;
    setError(null);
    try {
      await redoPolicy.mutateAsync(user.userId);
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" data-dirty="true">
        <header className="modal-head">
          <h2>{isEdit ? `Edit user: ${user!.username}` : 'Create new user'}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </header>
        <form onSubmit={submit}>
          <div className="modal-body">
            <div className="form-grid">
              <label className="field">
                <span className="field-label">Contact Type</span>
                <select value={contactTypeCd} onChange={(e) => setContactTypeCd(Number(e.target.value))}>
                  {Object.entries(CONTACT_TYPES).map(([cd, name]) => (
                    <option key={cd} value={cd}>{name}</option>
                  ))}
                </select>
              </label>

              <label className="field">
                <span className="field-label">{showLastName ? 'First Name' : 'Name'} *</span>
                <input value={firstName} onChange={(e) => setFirstName(e.target.value)} required />
              </label>

              {showLastName && (
                <label className="field">
                  <span className="field-label">Last Name</span>
                  <input value={lastName ?? ''} onChange={(e) => setLastName(e.target.value)} />
                </label>
              )}

              <label className="field">
                <span className="field-label">Username *</span>
                <input value={username} onChange={(e) => setUsername(e.target.value)} required />
              </label>

              <label className="field">
                <span className="field-label">Email Address</span>
                <input
                  type="email"
                  value={emailAddress}
                  onChange={(e) => setEmailAddress(e.target.value)}
                  placeholder="user@example.com"
                />
              </label>

              <label className="field">
                <span className="field-label">Password</span>
                <input
                  type="password"
                  value={password}
                  disabled={passwordsDisabled}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={passwordsDisabled ? (isSSORequired ? 'Disabled (SSO mandatory)' : 'Disabled in edit mode') : '••••••••'}
                />
              </label>

              <label className="field">
                <span className="field-label">Confirm Password</span>
                <input
                  type="password"
                  value={confirmPassword}
                  disabled={passwordsDisabled}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                />
              </label>

              <label className="field">
                <span className="field-label">Custom White Label Code</span>
                <select
                  value={customWhitelabelCode}
                  onChange={(e) => setCustomWhitelabelCode(e.target.value)}
                  disabled={gwAdminFlag}
                >
                  <option value="">— Select —</option>
                  {Object.entries(dropdowns.customWhitelabelCodes).map(([cd, wl]) => (
                    <option key={cd} value={cd}>{wl.name}</option>
                  ))}
                </select>
              </label>

              <label className="field field-inline">
                <input
                  type="checkbox"
                  checked={gwAdminFlag}
                  onChange={(e) => setGwAdminFlag(e.target.checked)}
                />
                <span>GW Admin</span>
              </label>

              <label className="field">
                <span className="field-label">Additional Security (MFA) *</span>
                <select
                  value={mfaEnabledFlag ? 'true' : 'false'}
                  onChange={(e) => setMfaEnabledFlag(e.target.value === 'true')}
                >
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </label>

              <label className="field">
                <span className="field-label">Default Role *</span>
                <select
                  value={defaultRoleCd}
                  onChange={(e) => setDefaultRoleCd(e.target.value === '' ? '' : Number(e.target.value))}
                  required
                >
                  <option value="">— Select —</option>
                  {roleEntries.map((r) => (
                    <option key={r.cd} value={r.cd}>{r.name}</option>
                  ))}
                </select>
              </label>

              <div className="field field-full">
                <span className="field-label">Roles *</span>
                <div className="roles-grid">
                  {roleEntries.map((r) => {
                    const checked = rolesCds.has(r.cd) || r.defaultFlag;
                    return (
                      <label key={r.cd} className="field field-inline">
                        <input
                          type="checkbox"
                          checked={checked}
                          disabled={r.defaultFlag}
                          onChange={(e) => onToggleRole(r.cd, e.target.checked)}
                        />
                        <span>{r.name} {r.defaultFlag ? '(Mandatory)' : '(Optional)'}</span>
                      </label>
                    );
                  })}
                </div>
              </div>

              {!isEdit && (
                <label className="field field-inline">
                  <input
                    type="checkbox"
                    checked={isSSORequired ? false : sendInviteFlag}
                    disabled={isSSORequired}
                    onChange={(e) => setSendInviteFlag(e.target.checked)}
                  />
                  <span>Send Advisor Portal Invite</span>
                </label>
              )}
            </div>
            {error && <p className="error">{error}</p>}
          </div>

          <footer className="modal-foot">
            {isEdit && (
              <button type="button" className="link-button" onClick={onRedoPolicy} disabled={redoPolicy.isPending}>
                {redoPolicy.isPending ? 'Refreshing…' : 'Refresh Policy Rules'}
              </button>
            )}
            <div className="footer-right">
              <button type="button" className="ghost-button" onClick={onClose} disabled={submitting}>
                Cancel
              </button>
              <button type="submit" disabled={submitting}>
                {submitting ? 'Saving…' : (isEdit ? 'Save' : 'Create')}
              </button>
            </div>
          </footer>
        </form>
      </div>
    </div>
  );
}
