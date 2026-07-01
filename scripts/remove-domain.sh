#!/usr/bin/env bash
# remove-domain.sh — the inverse of add-domain.sh. Totally removes a domain and
# every artefact it wired: scaffold, k8s manifests, overlay/ingress/tenant/realm
# entries, mkcert cert, and (if the cluster is up) the live workloads + Secret +
# images.
#
#   ./scripts/remove-domain.sh <slug> [--dry-run] [--no-deploy] [--yes]
#
#   --dry-run    print what would be removed, change nothing
#   --no-deploy  edit files only; leave the running cluster alone
#   --no-hosts   don't touch /etc/hosts
#   --yes        don't prompt for confirmation
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "remove-domain: $*" >&2; exit 1; }
info() { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }
ok() { printf '   \033[32m✓\033[0m %s\n' "$*"; }
skip() { printf '   \033[90m·\033[0m %s\n' "$*"; }

SLUG="${1:-}"; shift || true
[ -n "$SLUG" ] || die "usage: ./scripts/remove-domain.sh <slug> [--dry-run] [--no-deploy] [--yes]"
[[ "$SLUG" =~ ^[a-z][a-z0-9]*$ ]] || die "bad slug"

DRY=0; DEPLOY=1; YES=0; TOUCH_HOSTS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)   DRY=1; shift ;;
    --no-deploy) DEPLOY=0; shift ;;
    --no-hosts)  TOUCH_HOSTS=0; shift ;;
    --yes|-y)    YES=1; shift ;;
    *) die "unknown option: $1" ;;
  esac
done

HOST="$SLUG.geowealth.int"
NS="geowealth-demo"
# recover the web port from the manifest (or vite config) for the URL cleanup
WEB_PORT="$(grep -oE 'containerPort: [0-9]+' "k8s/base/web-$SLUG.yaml" 2>/dev/null | grep -oE '[0-9]+' | head -1)"
[ -n "$WEB_PORT" ] || WEB_PORT="$(grep -oE 'port: 5[0-9]{3}' "domains/$SLUG/web/vite.config.ts" 2>/dev/null | grep -oE '5[0-9]{3}' | head -1)"

# sanity: the domain must exist somewhere
if [ ! -d "domains/$SLUG" ] && [ ! -f "k8s/base/web-$SLUG.yaml" ] && ! grep -qE "^    $SLUG:$" token-handler/src/main/resources/application.yml 2>/dev/null; then
  die "no trace of domain '$SLUG' found — nothing to remove"
fi
case "$SLUG" in billing|trading|users) echo "WARNING: '$SLUG' is a core demo domain."; esac

info "Remove domain '$SLUG'  host=$HOST  web-port=${WEB_PORT:-?}  (dry-run=$DRY deploy=$DEPLOY)"
if [ "$DRY" = 0 ] && [ "$YES" = 0 ]; then
  printf "   Type the slug '%s' to confirm total removal: " "$SLUG"
  read -r ans; [ "$ans" = "$SLUG" ] || die "aborted (got '$ans')"
fi

run() { if [ "$DRY" = 1 ]; then echo "   [dry] $*"; else eval "$@"; fi; }

# ============================ FILE / CONFIG ==================================

# 1. scaffold dir
if [ -d "domains/$SLUG" ]; then run "rm -rf 'domains/$SLUG'"; ok "domains/$SLUG"; else skip "domains/$SLUG (absent)"; fi

# 2. k8s base manifests
for f in "k8s/base/web-$SLUG.yaml" "k8s/base/bff-$SLUG.yaml"; do
  if [ -f "$f" ]; then run "rm -f '$f'"; ok "$f"; else skip "$f (absent)"; fi
done

# 3. overlay resources lines
K="k8s/overlays/full-stack/kustomization.yaml"
if grep -q "base/web-$SLUG.yaml" "$K" 2>/dev/null; then
  run "sed -i '\\#- ../../base/web-$SLUG.yaml#d; \\#- ../../base/bff-$SLUG.yaml#d' '$K'"; ok "overlay resources"
else skip "overlay resources (absent)"; fi

# 4. ingress doc (drop the ---section whose Ingress name is web-<slug>)
I="k8s/base/ingress-app.yaml"
if grep -q "name: web-$SLUG$" "$I" 2>/dev/null; then
  if [ "$DRY" = 1 ]; then echo "   [dry] drop ingress doc web-$SLUG from $I"; else
    awk -v pat="name: web-$SLUG$" '
      /^---[[:space:]]*$/ { if (NR>1 && !drop) printf "%s", buf; buf=$0"\n"; drop=0; next }
      { buf=buf $0 "\n"; if ($0 ~ pat) drop=1 }
      END { if (!drop) printf "%s", buf }
    ' "$I" > "$I.tmp" && mv "$I.tmp" "$I"
  fi
  ok "ingress-app.yaml doc"
else skip "ingress doc (absent)"; fi

# 4b. portforward.sh FWDS lines (matched by the svc/ ref — no quote escaping)
P="k8s/portforward.sh"
if grep -qE "svc/web-$SLUG " "$P" 2>/dev/null; then
  run "sed -i '\\#svc/web-$SLUG #d; \\#svc/bff-$SLUG #d' '$P'"; ok "portforward.sh FWDS"
else skip "portforward.sh FWDS (absent)"; fi

# 4c. docker-compose.yml services (drop the demo-<slug> + bff-<slug> block,
# from its domain comment through the trailing blank before the next section /
# sentinel; leaves other services and the sentinel intact).
C="docker-compose.yml"
if grep -qE "^  demo-$SLUG:$" "$C" 2>/dev/null; then
  if [ "$DRY" = 1 ]; then echo "   [dry] drop demo-$SLUG + bff-$SLUG services from $C"; else
    awk -v slug="$SLUG" '
      function isours(l) { return (l ~ ("^  demo-" slug ":$") || l ~ ("^  bff-" slug ":$")) }
      !skip && ($0 ~ ("^  # ---- domain: " slug " ") || $0 ~ ("^  demo-" slug ":$")) { skip=1; next }
      skip {
        if ($0 ~ /^  # ---- / && $0 !~ ("domain: " slug " ")) skip=0
        else if ($0 ~ /add-domain\.sh inserts new domain services/) skip=0
        else if ($0 ~ /^volumes:/) skip=0
        else if ($0 ~ /^  [a-z][a-z0-9_-]*:[[:space:]]*$/ && !isours($0)) skip=0
        if (skip) next
      }
      { print }
    ' "$C" > "$C.tmp" && mv "$C.tmp" "$C"
  fi
  ok "docker-compose.yml services"
else skip "docker-compose.yml services (absent)"; fi

# 5. token-handler tenant — both files (compose 4sp name, K8s ConfigMap 8sp name)
del_tenant() { # <file> <name-indent>
  local file="$1" ni="$2"
  grep -qE "^${ni}$SLUG:$" "$file" 2>/dev/null || { skip "tenant in $file (absent)"; return; }
  if [ "$DRY" = 1 ]; then echo "   [dry] delete tenant '$SLUG' block in $file"; else
    awk -v nire="^${ni}${SLUG}:$" -v fieldre="^${ni} " '
      $0 ~ nire {skip=1; next}
      skip && $0 ~ fieldre {next}
      skip {skip=0}
      {print}
    ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
  fi
  ok "tenant block in $(basename "$file")"
}
del_tenant "token-handler/src/main/resources/application.yml" "    "
del_tenant "k8s/base/token-handler-config.yaml" "        "

# 6. urls.dev.env SPA_HOSTS
U="k8s/env/urls.dev.env"
if [ -n "$WEB_PORT" ] && grep -q "$HOST:$WEB_PORT" "$U" 2>/dev/null; then
  run "sed -i 's# https://$HOST:$WEB_PORT##g; s# http://localhost:$WEB_PORT##g; s# http://127.0.0.1:$WEB_PORT##g' '$U'"
  ok "urls.dev.env SPA_HOSTS"
else skip "urls.dev.env (absent or no port)"; fi

# 7. realm demo-shared-client (jq — drop entries with the host or :<port>)
R="keycloak/realm-export.json"
if grep -q "$HOST" "$R" 2>/dev/null; then
  if [ "$DRY" = 1 ]; then echo "   [dry] jq-strip $HOST from demo-shared-client"; else
    tmp="$(mktemp)"
    jq --arg host "$HOST" --arg p ":$WEB_PORT" '
      (.clients[] | select(.clientId=="demo-shared-client")) |= (
        .webOrigins   = (.webOrigins   | map(select((contains($host) or ($p!=":" and contains($p))) | not)))
      | .redirectUris = (.redirectUris | map(select((contains($host) or ($p!=":" and contains($p))) | not)))
      | .attributes["post.logout.redirect.uris"] =
          (.attributes["post.logout.redirect.uris"] | split("##")
           | map(select((contains($host) or ($p!=":" and contains($p))) | not)) | join("##"))
      )
    ' "$R" > "$tmp" && mv "$tmp" "$R"
  fi
  ok "realm demo-shared-client"
else skip "realm (absent)"; fi

# 8. mkcert cert
for f in "proxy/certs/$HOST.crt" "proxy/certs/$HOST.key"; do
  if [ -f "$f" ]; then run "rm -f '$f'"; ok "$f"; else skip "$f (absent)"; fi
done

# ============================ LIVE CLUSTER ===================================
MK_PROFILE="${MINIKUBE_PROFILE:-geowealth}"
if [ "$DRY" = 0 ] && [ "$DEPLOY" = 1 ] && command -v kubectl >/dev/null 2>&1 && kubectl get ns "$NS" >/dev/null 2>&1; then
  info "Cluster up — removing live artefacts for $SLUG"
  kubectl -n "$NS" delete deploy,svc "web-$SLUG" "bff-$SLUG" --ignore-not-found >/dev/null 2>&1 && ok "deleted web-$SLUG + bff-$SLUG"
  kubectl -n "$NS" delete ingress "web-$SLUG" --ignore-not-found >/dev/null 2>&1 && ok "deleted ingress web-$SLUG"
  kubectl -n "$NS" delete secret "web-$SLUG-tls" --ignore-not-found >/dev/null 2>&1 && ok "deleted Secret web-$SLUG-tls"
  # re-apply the (now shrunk) tenant ConfigMap + restart token-handler
  awk 'BEGIN{p=1} /^---[[:space:]]*$/{p=0} p' k8s/base/token-handler-config.yaml \
    | kubectl -n "$NS" apply -f - >/dev/null 2>&1 && ok "updated tenants ConfigMap"
  kubectl -n "$NS" rollout restart deploy/token-handler >/dev/null 2>&1 && ok "restarted token-handler"
  # reconcile the realm from the shrunk urls.dev.env (removes the host live)
  if [ -x scripts/reconcile-realm.sh ]; then
    kubectl -n "$NS" port-forward svc/keycloak 18080:8080 >/dev/null 2>&1 &
    _pf=$!; sleep 3
    KC_ADMIN_BASE="http://localhost:18080" ./scripts/reconcile-realm.sh k8s/env/urls.dev.env >/dev/null 2>&1 \
      && ok "reconciled realm" || skip "realm reconcile (run manually)"
    kill "$_pf" 2>/dev/null || true
  fi
  # drop images from the minikube docker
  if command -v minikube >/dev/null 2>&1 && minikube -p "$MK_PROFILE" status >/dev/null 2>&1; then
    minikube -p "$MK_PROFILE" image rm "keycloak-demo-$SLUG-web:latest" "keycloak-demo-$SLUG-bff:latest" >/dev/null 2>&1 \
      && ok "removed minikube images" || skip "minikube images (absent)"
  fi
elif [ "$DRY" = 0 ] && [ "$DEPLOY" = 1 ]; then
  skip "cluster not running — nothing live to remove"
fi

# 9. /etc/hosts line (mirror of add-domain's entry)
if grep -qE "^[^#]*\b$HOST\b" /etc/hosts 2>/dev/null; then
  if [ "$TOUCH_HOSTS" = 0 ]; then skip "/etc/hosts ($HOST — kept, --no-hosts)"
  elif [ "$DRY" = 1 ]; then echo "   [dry] remove '$HOST' line from /etc/hosts"
  elif sudo sed -i "\#\\b$HOST\\b#d" /etc/hosts 2>/dev/null; then ok "/etc/hosts line for $HOST"
  else echo "   (could not sudo — remove '$HOST' from /etc/hosts by hand)"; fi
else skip "/etc/hosts ($HOST absent)"; fi

info "Domain '$SLUG' removed.$([ "$DRY" = 1 ] && echo ' (dry-run — no changes made)')"
