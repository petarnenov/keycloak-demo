# Per-MFE dev — bringing up only what you need

A short manual for the four `dev-mfe*.sh` scripts at the repo root. Use these
when you're iterating on **one** MFE and don't want to pay the RAM, CPU, and
boot time of the full ten-container stack. It assumes you've read the
architecture overview in [`README.md`](./README.md) and the dev-workflows
section in [`CLAUDE.md`](./CLAUDE.md).

## TL;DR

```bash
./dev-mfe-admin.sh    # or dev-mfe-ops.sh / dev-mfe-client.sh
```

Brings up `postgres + user-service + keycloak + shell + bff-client + mfe-<which> + bff-<which>`
in Level 2 (watch) mode. Edits in `apps/mfe-<which>/src/**` rebuild in ~1-2s;
hard-refresh the browser to pick up the new federation chunks. The other two
MFE containers (and their BFFs) stay down.

## When to use this vs. `./start.sh`

| You're doing... | Use |
|---|---|
| Showing the demo, or working across multiple MFEs | `./start.sh` (or `./start.sh --dev`) |
| Iterating on **one** MFE's frontend | `./dev-mfe-<which>.sh` |
| Iterating on **one** BFF's backend | `./dev-bff.sh <which>` (Level 3, requires `./start.sh --dev` first) |
| Iterating on a frontend that needs the **paired** BFF dev loop | `./dev-mfe-<which>.sh` then `./dev-bff.sh <which>` on top |

## The four scripts

| Script | What it does |
|---|---|
| `dev-mfe.sh client\|ops\|admin` | The actual implementation. Takes the MFE name as the first arg. |
| `dev-mfe-client.sh` | One-line wrapper → `dev-mfe.sh client`. |
| `dev-mfe-ops.sh` | One-line wrapper → `dev-mfe.sh ops`. |
| `dev-mfe-admin.sh` | One-line wrapper → `dev-mfe.sh admin`. |

The wrappers exist so `ls dev-mfe-*.sh` is self-documenting and tab-completion
finds them. If you prefer one entry point, `./dev-mfe.sh <which>` is equivalent.

## What gets started

Always (the minimum for login + the shell to mount):

| Service | Why |
|---|---|
| `postgres` | Keycloak's realm + session store |
| `user-service` | Demo user store the Keycloak SPI calls over REST |
| `keycloak` | OIDC IdP on `:8898` |
| `shell` | The React host on `:5173` that loads MFE remotes |
| `bff-client` | Serves `/api/whoami` for the shell (also serves `mfe-client`) |

Plus, per chosen MFE:

| `<which>` | Adds |
|---|---|
| `client` | `mfe-client` (`bff-client` is already in the always-on list) |
| `ops` | `mfe-ops` + `bff-ops` |
| `admin` | `mfe-admin` + `bff-admin` |

Skipped containers: the two MFE pairs you're not working on.

## Login

The role gate in `bff-client`'s `/api/whoami` decides which MFEs the shell
will mount. Log in as the matching demo user:

| `<which>` | Username / password | Roles |
|---|---|---|
| `client` | `democlient` / `123` | `client` |
| `ops` | `demouser` / `123` (or `demoadmin`) | `user` (or `admin` + `user`) |
| `admin` | `demoadmin` / `123` | `admin`, `user` |

The 6-digit email OTP step still runs. Codes are logged at INFO — read them
with:

```bash
docker compose logs keycloak | grep "Email OTP"   # or `podman compose ...`
```

(After the first successful OTP, the realm-scoped trust cookie skips OTP for
the next 60 minutes — same as the full stack.)

## The edit loop

Each MFE container in Level 2 mode runs `vite build --watch` + `vite preview`
concurrently. Save a file under `apps/mfe-<which>/src/**` → ~1-2 seconds later
the federation chunks under `apps/mfe-<which>/dist/assets/` are rewritten →
the next browser hit reloads them. **Hard-refresh** (Cmd/Ctrl-Shift-R) — the
browser caches the old `remoteEntry.js`.

There is no HMR across the federation boundary. Module Federation only sees
fully built remotes, so every edit is a rebuild + page reload.

## Caveats and gotchas

- **Don't click links to skipped MFEs.** The shell's `<Nav>` greys out
  MFE links the logged-in user doesn't have a role for, but it does NOT know
  that you've chosen not to start a container. Clicking `/ops` while only
  `dev-mfe-admin.sh` is running will give you a federation-remote fetch 404.
  Stay on `/<which>`.
- **`bff-client` is always started**, even when developing `mfe-ops` or
  `mfe-admin`. The shell calls `/api/whoami` on it before mounting anything,
  so it has to be up. Don't try to skip it.
- **Images must already be built.** The script invokes `compose build` for
  the custom images it actually needs (`keycloak`, `user-service`,
  `bff-client`, optionally `bff-<which>`) — that's a no-op on subsequent
  runs but does the work on the first run. The first invocation may take a
  minute; subsequent invocations are ~10 seconds.
- **BFF source edits still need a rebuild.** This script gives you the Level 2
  MFE edit loop only. If you also need fast iteration on the BFF, layer
  `./dev-bff.sh <which>` on top — it stops the compose BFF and runs Gradle
  continuous-build mode on the host, redirecting the shell's Vite proxy to
  `host.docker.internal:<port>` (or `host.containers.internal` for podman).
- **Realm + user store are shared with the full stack.** If you've made
  realm/Postgres changes via `./start.sh`, this script picks them up — same
  volumes, no reset. Conversely, if you wipe with `compose down -v` while
  using this script, you're wiping the same data as the full stack.

## Restoring the full stack

The per-MFE scripts don't *stop* the other MFE/BFF containers; they just
don't start them. So the way back is one of:

```bash
./start.sh           # back to Level 1 demo
./start.sh --dev     # back to Level 2 dev across all three MFEs
```

If you only want to add one more MFE to your current subset, you can also do
it surgically:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml \
  up -d mfe-ops bff-ops
```

(Use `podman compose` if that's your engine.)

## Engine and overrides

- Auto-detects `docker` vs `podman`. Override with
  `CONTAINER_ENGINE=docker|podman ./dev-mfe-admin.sh`.
- Sources `.envrc` if present (for `DOCKER_HOST` on podman and
  `RESEND_API_TOKEN`).
- On the podman path, polls `wait_healthy` on `postgres`, `user-service`,
  and `keycloak` before starting the rest, because `podman compose` ignores
  `depends_on: condition: service_healthy`. Same workaround as `start.sh`.
