# Podman migration plan

Action plan for moving this demo's local container engine from Docker to Podman,
and (later phase) standing up a local Kubernetes target. Written ahead of time so
the work can start immediately on the word "go".

The repo is **already engine-agnostic**: `start.sh` / `stop.sh` auto-detect the
engine and `CONTAINER_ENGINE=docker|podman` forces either. So "migration" here
means: install Podman, make it the **default**, prove the full SSO flow still
works on it, and only then build on top of it. Nothing in the compose topology
has to change for Phase 1.

## Phase 0 — Install Podman (macOS)

```bash
brew install podman
podman machine init            # creates the Linux VM (default 2 CPU / 2 GB)
podman machine start
podman info                    # sanity: VM up, rootless
# Optional GUI:
brew install --cask podman-desktop
```

Notes:
- Give the VM enough headroom — Keycloak alone has `mem_limit: 1g`. If the
  default machine is tight: `podman machine stop && podman machine set --cpus 4 --memory 6144 && podman machine start`.
- `podman compose` needs a provider. On macOS Podman ships its own; if it
  complains, `brew install podman-compose` gives the Python fallback (start.sh
  already handles both `podman compose` and `podman-compose`).

## Phase 1 — Run the existing stack on Podman

1. **Socket env.** `podman compose` talks through a Docker-style socket. If it
   errors "Cannot connect to the Docker daemon", export the real path:
   ```bash
   export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
   ```
   `.envrc` already carries a version of this — confirm it resolves under Podman.

2. **Bring it up on Podman explicitly:**
   ```bash
   CONTAINER_ENGINE=podman ./start.sh
   ```
   `start.sh` already compensates for the two known Podman quirks:
   - `depends_on: condition: service_healthy` is ignored by podman-compose →
     it brings up `postgres` first, polls health, then `keycloak`, then domains.
   - both `podman compose` and `podman-compose` invocation forms are supported.

3. **Watch for Podman-specific gotchas to verify during the first run:**
   - `extra_hosts: host-gateway` — billing/trading BFFs rely on
     `auth.geowealth.int:host-gateway` and `host.docker.internal:host-gateway`
     to reach Keycloak's public issuer and P1 on `:8888`. Confirm host-gateway
     resolves under Podman's network (it should; verify the BFF can do the
     server-side OIDC code exchange and the P1 authz call).
   - Host port bindings `5180 / 5184 / 5185` — rootless Podman binds high ports
     fine; all three are >1024 so no privilege issue.
   - Named volumes `postgres_data`, `keycloak_data` — Podman keeps them in its
     own VM, **separate from Docker's**. The federated identities / realm edits
     living in Docker's Postgres volume will NOT carry over. Either accept a
     fresh `--reset`-style seed on Podman, or migrate the volume (see below).

4. **Volume carry-over (optional, only if we want to keep Docker's live state).**
   The realm seed re-imports cleanly, but SAML-brokered federated identities and
   any live admin-API realm edits live only in Postgres. To preserve them:
   ```bash
   # with Docker still installed, dump:
   docker compose exec postgres pg_dump -U keycloak keycloak > /tmp/kc.sql
   # after the Podman stack is up:
   CONTAINER_ENGINE=podman docker... # load via podman compose exec -T postgres psql -U keycloak keycloak < /tmp/kc.sql
   ```
   For a demo it's usually simpler to just re-run the P1 login once and let
   first-broker-login rebuild the identity. Decide per need.

5. **Verify the full flow — not just 200s.** Per repo policy, drive the real
   P1 → Keycloak → BFF chain via Playwright/Chrome DevTools MCP and capture
   evidence (login, dashboard render, logout fan-out). The `e2e/` Playwright
   suite is the fastest proof:
   ```bash
   ./e2e/scripts/disable-mfa-for-tim1.sh   # one-time DB tweak
   cd e2e && npm test
   ```

6. **Make Podman the default (the actual "migration").** Once green:
   - Set `CONTAINER_ENGINE=podman` in `.envrc` so plain `./start.sh` uses it.
   - Update README / CLAUDE.md notes if any wording still implies Docker-first.
   - Uninstall Docker Desktop only after a full green run (keep it until then for
     the volume dump path above).

## Phase 2 — Local Kubernetes (later, separate go-ahead)

Not part of the Podman migration itself; queued behind it.

- **Cluster:** `kind --driver=podman` or `minikube --driver=podman`, or Podman
  Desktop's built-in Kubernetes. Pick one when we get here.
- **Manifests:** start from `podman generate kube` against the running compose
  pod as a skeleton, then hand-correct into proper Deployments/Services +
  ConfigMaps/Secrets for: postgres, keycloak, auth-nginx, demo-billing/web,
  bff-billing, demo-trading/web, bff-trading.
- **Things that need real K8s thinking (not 1:1 from compose):**
  - `host-gateway` / `host.docker.internal` → in-cluster there's no host; P1 on
    the Mac host needs an explicit reachable address (host IP, or run P1 in the
    cluster too). The BFF → P1 authz call and KC public-issuer resolution both
    depend on this.
  - TLS certs (`proxy/certs/*`) → Secrets, and the `auth.geowealth.int` hostname
    → Ingress + `/etc/hosts` (or a local DNS) mapping to the ingress.
  - Secrets (`*_OAUTH_CLIENT_SECRET`, KC admin) → real K8s Secrets, not env in a
    committed file.
  - Realm import → init job / ConfigMap mount instead of the compose volume.
- **Goal:** deploy billing/trading + keycloak + postgres as first-class K8s
  workloads and prove the same E2E SSO flow against the ingress host.

## Quick reference

| Want | Command |
|---|---|
| Run stack on Podman | `CONTAINER_ENGINE=podman ./start.sh` |
| Stop (volumes kept) | `CONTAINER_ENGINE=podman ./stop.sh` |
| Fresh realm seed | `CONTAINER_ENGINE=podman ./start.sh --reset` |
| Fix socket error | `export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"` |
| Resize VM | `podman machine set --cpus 4 --memory 6144` (machine stopped) |
| E2E proof | `cd e2e && npm test` (after `disable-mfa-for-tim1.sh`) |
