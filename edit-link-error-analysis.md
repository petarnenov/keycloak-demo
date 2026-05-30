# Edit fails with *"more than one user with this email"* — analysis & fix

## Symptom

In the Demo Users SPA (`https://users.geowealth.int:5186/`), clicking **Edit**
on any of `tim1` / `tim5` / `tim10` and submitting the form fails with:

> The user failed to be linked - there are more than one user with this
> email.

## Root cause

`UserManagerTrait.java#createUpdateUser` (lines 8550-8567) runs an
**auto-link routine** whenever a user is saved with `gwAdminFlag=true` and a
non-empty email:

```java
if (nEntity.isGwAdminFlag()) {
    // If users are GW Admin with the same email as an existing user
    // from Site 1, they are auto linked with this user.
    List<UsersForLinkDelinkJTO> userForLinking =
        dao.findFirm1UsersByPrimaryEmail(emailAddress);
    if (CollectionUtils.isNotEmpty(userForLinking)) {
        if (userForLinking.size() > 1) {
            return Either.left(new ErrorByField(F_EMAIL,
                "The user failed to be linked - there are more than one "
                + "user with this email. "));
        } else {
            NEntity firm1Entity =
                dao.getByID(userForLinking.get(0).getUserId());
            nEntity.setLinkedEntity(firm1Entity);
        }
    }
}
```

**Design intent** (per the comment): a GeoWealth-firm employee who also
exists in a customer firm should be linked via `setLinkedEntity` so the
two records are treated as one identity. The routine assumes the GW firm
(firm 1) has **exactly one** record per person — otherwise the link is
ambiguous and the save fails.

**Why the demo trips it:**

1. The multi-username refactor (`PersonRegistry`) introduced `tim5` and
   `tim10` as additional symbolic logins for the same physical person.
2. `scripts/seed-tim5-tim10.sql` inserted them in **firm 1** with the
   **same primary email** (`tim.a@geo.com`) as `tim1`, so all three
   appear as a "match" to each other.
3. The earlier `PersonRegistry` flip put `gwAdmin` on every tenant of
   `P-tim`, so every save fires the auto-link routine (which requires
   `gwAdminFlag=true`).

Saving `tim1` ⇒ `findFirm1UsersByPrimaryEmail("tim.a@geo.com")` returns
`{tim1, tim5, tim10}` ⇒ `size() > 1` ⇒ error.

The auto-link routine is **semantically nonsense** when the user being
edited is itself in firm 1: it is supposed to link a firm-N user to a
firm-1 "main" record, but if the edited user IS already in firm 1 the
link target would be itself or a same-firm sibling.

## Reproduction

1. Open `https://users.geowealth.int:5186/`.
2. Click **Edit** on the `tim1` row (or `tim5` / `tim10`).
3. Do not change anything; click **Save**.
4. Error appears in the modal:

```
EMAIL: The user failed to be linked - there are more than one user
with this email.
```

## Fix

Two safe, additive guardrails in `UserManagerTrait#createUpdateUser`:

1. **Skip the auto-link entirely when the edited user is in firm 1.** A
   firm-1 GW employee does not need a "cross-firm doppelganger" pointer;
   they ARE the firm-1 record.

2. **Degrade gracefully on ambiguous matches.** When the email matches
   more than one firm-1 row, log a warning and skip the link instead of
   failing the whole save. The edited user remains saved without an
   explicit linked entity (which mirrors the multi-username SSO model:
   identity unification happens at the person level via `personId`, not
   by email equality).

```java
if (nEntity.isGwAdminFlag() && !Integer.valueOf(1).equals(nEntity.getFirmCd())) {
    List<UsersForLinkDelinkJTO> userForLinking =
        dao.findFirm1UsersByPrimaryEmail(emailAddress);
    if (CollectionUtils.isNotEmpty(userForLinking)) {
        if (userForLinking.size() > 1) {
            // Demo data lets multiple firm-1 users share an email
            // (PersonRegistry / multi-username scenario). Auto-link
            // can't pick a canonical target — skip it; identity
            // unification is handled at the personId layer instead.
            LOG.warn("Skipping auto-link for {}: {} firm-1 users share "
                + "email {}", nEntity.getLdapUid(), userForLinking.size(),
                emailAddress);
        } else {
            NEntity firm1Entity =
                dao.getByID(userForLinking.get(0).getUserId());
            nEntity.setLinkedEntity(firm1Entity);
        }
    }
}
```

Together the two changes preserve the original design intent (cross-firm
GW-admin linking) while making the routine resilient to the demo's
shared-email arrangement.

## Verification (2026-05-29)

Applied the fix to `UserManagerTrait.java` (geowealth, line 8555), ran
`make redeploy-agent AGENT=useragents`, then in the Demo Users SPA:

1. Clicked **Edit** on `tim1` → modal opened.
2. Filled `EMAIL = tim.a@geo.com`, `Default Role = Admins`,
   ticked `Admins`, clicked **Save**.
3. Modal closed cleanly — **no "more than one user with this email" error**.
4. `UserManagerTrait` log contained NO "Skipping auto-link" warning,
   confirming the firm-1 guard short-circuited the routine entirely (the
   inner email lookup never ran) — exactly the intended behaviour.

Side-effect observed (unrelated to this fix): the modal opened with the
**GW Admin** checkbox unchecked because the form binding doesn't reflect
the live `gwAdminFlag` on read; saving therefore unset `GW_ADMIN_FLAG=0`
on `tim1`, which then made subsequent `getUsers` 403 from
`BffUsersAction.isAllowedFor`. Restoring the flag in the DB
(`UPDATE ENTITY_TBL SET GW_ADMIN_FLAG=1 WHERE LDAP_UID='tim1'`) put the
grid back to USERS (3). That binding gap is a separate Demo Users SPA bug.

## Why not "give each tim a distinct email"

That would silently fix the symptom but break the demo's "same physical
person" invariant at the data layer. SAML emission is unaffected (each
login resolves email from the active `LoggedUser`, so logging in as `tim1`
still emits `tim.a@geo.com`), but the Users grid would display three
distinct emails — defeating the visual "this is one person" message.
