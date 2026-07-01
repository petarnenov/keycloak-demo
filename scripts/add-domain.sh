#!/usr/bin/env bash
# add-domain.sh — scaffold a NEW demo domain (thin web + thin BFF) and wire it
# into every place a domain must be registered, so it is ready to build & run.
#
#   ./scripts/add-domain.sh <slug> [options]
#
# <slug> must be [a-z][a-z0-9]* (used verbatim as a Java package + host label).
# It clones the proven `billing` domain, renames every slug-derived token, and
# patches: urls.dev.env, the realm's demo-shared-client, token-handler tenants,
# k8s base manifests + overlay, the ingress, the mkcert cert, and /etc/hosts.
#
# Options (sensible dev defaults; override only when you need to):
#   --web-port N        SPA / nginx port          (default: next free 518X)
#   --bff-port N        host-side BFF dev port     (default: next free 808X)
#   --object-type N     P1 ObjectType for the tenant gate   (default: 59)
#   --permission  N     P1 Permission code                  (default: 5 = EXECUTE)
#   --type resource|firm  tenant gate type          (default: resource)
#   --firm-cd N         firm code when --type firm            (default: 1)
#   --cookie NAME       BFF session cookie name    (default: <FirstLetterUpper>SESSION)
#   --force             overwrite an existing domains/<slug>
#   --no-hosts          don't touch /etc/hosts (just print the line)
#   --no-deploy         scaffold + wire only; skip the image build / live apply
#
# Deploy behaviour (unless --no-deploy):
#   - minikube cluster UP  → builds keycloak-demo-<slug>-{web,bff} into minikube,
#     applies the new manifests + ingress + tenant ConfigMap, restarts the
#     token-handler, reconciles the realm — the domain comes up live.
#   - cluster DOWN         → just wires the files; the next ./k8s/up.sh (which is
#     domain-generic) builds + deploys the new domain automatically.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "add-domain: $*" >&2; exit 1; }
info() { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }
ok() { printf '   \033[32m✓\033[0m %s\n' "$*"; }

# --- args --------------------------------------------------------------------
SLUG="${1:-}"; shift || true
[ -n "$SLUG" ] || die "usage: ./scripts/add-domain.sh <slug> [options]"
[[ "$SLUG" =~ ^[a-z][a-z0-9]*$ ]] || die "slug must match [a-z][a-z0-9]* (no hyphens — it becomes a Java package)"

WEB_PORT=""; BFF_HOST_PORT=""; OBJECT_TYPE="59"; PERMISSION="5"; TENANT_TYPE="resource"
COOKIE=""; FORCE=0; TOUCH_HOSTS=1; FIRM_CD="1"; DEPLOY=1
while [ $# -gt 0 ]; do
  case "$1" in
    --web-port)    WEB_PORT="$2"; shift 2 ;;
    --bff-port)    BFF_HOST_PORT="$2"; shift 2 ;;
    --object-type) OBJECT_TYPE="$2"; shift 2 ;;
    --permission)  PERMISSION="$2"; shift 2 ;;
    --type)        TENANT_TYPE="$2"; shift 2 ;;
    --firm-cd)     FIRM_CD="$2"; shift 2 ;;
    --cookie)      COOKIE="$2"; shift 2 ;;
    --force)       FORCE=1; shift ;;
    --no-hosts)    TOUCH_HOSTS=0; shift ;;
    --no-deploy)   DEPLOY=0; shift ;;
    *) die "unknown option: $1" ;;
  esac
done
[ "$TENANT_TYPE" = resource ] || [ "$TENANT_TYPE" = firm ] || die "--type must be resource|firm"

# --- derived values ----------------------------------------------------------
cap() { printf '%s%s' "$(printf %s "${1:0:1}" | tr '[:lower:]' '[:upper:]')" "${1:1}"; }
PASCAL="$(cap "$SLUG")"
UPPER="$(printf %s "$SLUG" | tr '[:lower:]' '[:upper:]')"
HOST="$SLUG.geowealth.int"
[ -n "$COOKIE" ] || COOKIE="$(printf %s "${SLUG:0:1}" | tr '[:lower:]' '[:upper:]')SESSION"

# next free ports, scanned from existing domains (billing 5184/8084, trading 5185/8085 → next)
if [ -z "$WEB_PORT" ]; then
  last="$(grep -rhoE '518[0-9]' domains/*/web/vite.config.ts 2>/dev/null | sort -n | tail -1)"
  WEB_PORT="$(( ${last:-5183} + 1 ))"
fi
if [ -z "$BFF_HOST_PORT" ]; then
  last="$(grep -rhoE '127\.0\.0\.1:808[0-9]' domains/*/web/vite.config.ts 2>/dev/null | grep -oE '808[0-9]' | sort -n | tail -1)"
  BFF_HOST_PORT="$(( ${last:-8083} + 1 ))"
fi

TEMPLATE="billing"
[ -d "domains/$TEMPLATE" ] || die "template domain domains/$TEMPLATE not found"
if [ -d "domains/$SLUG" ]; then
  [ "$FORCE" = 1 ] || die "domains/$SLUG already exists (use --force to overwrite)"
  rm -rf "domains/$SLUG"
fi

# tools
command -v rsync >/dev/null || die "rsync required"
command -v jq >/dev/null || die "jq required (realm patch)"
HAVE_MKCERT=1; command -v mkcert >/dev/null || HAVE_MKCERT=0

info "Scaffolding domain '$SLUG'  host=$HOST  web=$WEB_PORT  bff-dev=$BFF_HOST_PORT  cookie=$COOKIE  objType=$OBJECT_TYPE perm=$PERMISSION type=$TENANT_TYPE"

# --- 1. clone the template domain -------------------------------------------
rsync -a --exclude node_modules --exclude dist --exclude build --exclude .gradle \
      "domains/$TEMPLATE/" "domains/$SLUG/"
ok "cloned domains/$TEMPLATE → domains/$SLUG"

# --- 2. move + rename the Java package/class --------------------------------
JB="domains/$SLUG/bff/src/main/java/demo"
mkdir -p "$JB/$SLUG"
mv "$JB/$TEMPLATE/"* "$JB/$SLUG/" 2>/dev/null || true
rmdir "$JB/$TEMPLATE" 2>/dev/null || true
mv "$JB/$SLUG/$(cap "$TEMPLATE")Controller.java" "$JB/$SLUG/${PASCAL}Controller.java"
ok "java package demo.$SLUG + ${PASCAL}Controller"

# --- 3. token rename across text files --------------------------------------
mapfile -t FILES < <(find "domains/$SLUG" -type f \
  \( -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.json' -o -name '*.java' \
     -o -name '*.yml' -o -name '*.yaml' -o -name '*.conf' -o -name '*.html' -o -name '*.gradle' \
     -o -name '*.kts' -o -name '*.properties' -o -name 'Dockerfile' -o -name '*.sh' -o -name '*.css' \) \
  ! -name 'package-lock.json')
for f in "${FILES[@]}"; do
  sed -i \
    -e "s/$(cap "$TEMPLATE")/$PASCAL/g" \
    -e "s/BILLING/$UPPER/g" \
    -e "s/$TEMPLATE/$SLUG/g" \
    -e "s/BSESSION/$COOKIE/g" \
    -e "s/5184/$WEB_PORT/g" \
    -e "s/8084/$BFF_HOST_PORT/g" \
    "$f"
done
# tenant authz code in the bundled (dev) bff config
sed -i -E "s/(APP_REQUIREMENT_OBJECT_TYPE[^0-9]*)59/\1$OBJECT_TYPE/; s/(object-type: *)59/\1$OBJECT_TYPE/" \
  "domains/$SLUG/bff/src/main/resources/application.yml" 2>/dev/null || true
ok "renamed tokens (${#FILES[@]} files)"

# --- 4. urls.dev.env : SPA_HOSTS --------------------------------------------
U="k8s/env/urls.dev.env"
if grep -q "$HOST:$WEB_PORT" "$U" 2>/dev/null; then ok "urls.dev.env already has $HOST"; else
  sed -i "s#^SPA_HOSTS=.*#& https://$HOST:$WEB_PORT http://localhost:$WEB_PORT http://127.0.0.1:$WEB_PORT#" "$U"
  ok "urls.dev.env SPA_HOSTS += $HOST:$WEB_PORT"
fi

# --- 5. realm demo-shared-client (jq) ---------------------------------------
R="keycloak/realm-export.json"
if grep -q "$HOST:$WEB_PORT" "$R" 2>/dev/null; then ok "realm demo-shared-client already lists $HOST"; else
  H="https://$HOST:$WEB_PORT"
  tmp="$(mktemp)"
  jq --arg h "$H" --arg lp "http://localhost:$WEB_PORT" --arg ip "http://127.0.0.1:$WEB_PORT" '
    (.clients[] | select(.clientId=="demo-shared-client")) |= (
      .webOrigins   = ((.webOrigins   + [$h, $lp, $ip]) | unique)
    | .redirectUris = ((.redirectUris + [$h, ($h+"/"), ($h+"/*"), ($lp+"/*"), ($ip+"/*")]) | unique)
    | .attributes["post.logout.redirect.uris"] =
        (.attributes["post.logout.redirect.uris"] + "##" + $h + "/*" + "##" + $lp + "/*")
    )
  ' "$R" > "$tmp" && mv "$tmp" "$R"
  ok "realm demo-shared-client += $HOST (redirect/webOrigin/post-logout)"
fi

# --- 6. token-handler tenants (BOTH the bundled/compose yml AND the K8s ConfigMap) --
# Write the tenant block to a temp file (real newlines + indent) and insert it
# verbatim after the `tenants:` line via `sed r`. resource → P1 object-type/
# permission gate; firm → firm-cd membership gate.
tenant_block_file() { # <name-indent> <field-indent> → prints temp file path
  local ni="$1" fi="$2" tmp; tmp="$(mktemp)"
  {
    printf '%s%s:\n' "$ni" "$SLUG"
    printf '%shost: %s\n' "$fi" "$HOST"
    printf '%stype: %s\n' "$fi" "$TENANT_TYPE"
    if [ "$TENANT_TYPE" = firm ]; then
      printf '%sfirm-cd: %s\n' "$fi" "$FIRM_CD"
    else
      printf '%sobject-type: %s\n' "$fi" "$OBJECT_TYPE"
      printf '%spermission: %s\n' "$fi" "$PERMISSION"
    fi
  } > "$tmp"; printf '%s' "$tmp"
}
insert_tenant() { # <file> <anchor-regex> <name-indent> <field-indent> <guard-regex> <label>
  local file="$1" anchor="$2" ni="$3" fi="$4" guard="$5" label="$6" bf
  if grep -qE "$guard" "$file" 2>/dev/null; then ok "$label tenant '$SLUG' exists"; return; fi
  bf="$(tenant_block_file "$ni" "$fi")"
  sed -i "$anchor""r $bf" "$file"
  rm -f "$bf"
  ok "$label tenant $SLUG"
}
# 6a. compose form (tenants at 2sp → name 4sp, fields 6sp)
insert_tenant "token-handler/src/main/resources/application.yml" \
  "/^  tenants:$/" "    " "      " "^    $SLUG:$" "token-handler application.yml"
# 6b. K8s ConfigMap form (tenants at 6sp → name 8sp, fields 10sp)
insert_tenant "k8s/base/token-handler-config.yaml" \
  "/^      tenants:$/" "        " "          " "^        $SLUG:$" "token-handler-config ConfigMap"

# --- 7. k8s base manifests (web + bff) --------------------------------------
cat > "k8s/base/web-$SLUG.yaml" <<YAML
# $SLUG domain web (SPA) pod — cloned shape of web-billing. nginx serves the SPA
# and forward-auths /api + /(oauth|auth)/ to token-handler / bff-$SLUG by Service
# name. TLS cert from Secret web-$SLUG-tls (k8s/up.sh loads it from proxy/certs).
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-$SLUG
  labels: { app.kubernetes.io/name: web-$SLUG, app.kubernetes.io/component: web }
spec:
  replicas: 1
  selector: { matchLabels: { app.kubernetes.io/name: web-$SLUG } }
  template:
    metadata:
      labels: { app.kubernetes.io/name: web-$SLUG, app.kubernetes.io/component: web }
    spec:
      containers:
        - name: web
          image: keycloak-demo-$SLUG-web:latest
          imagePullPolicy: IfNotPresent
          ports: [{ name: https, containerPort: $WEB_PORT }]
          resources:
            requests: { cpu: 50m, memory: 64Mi }
            limits:   { cpu: 250m, memory: 192Mi }
          readinessProbe:
            tcpSocket: { port: https }
            periodSeconds: 10
          volumeMounts:
            - { name: certs, mountPath: /certs, readOnly: true }
      volumes:
        - name: certs
          secret:
            secretName: web-$SLUG-tls
            items:
              - { key: tls.crt, path: $SLUG.crt }
              - { key: tls.key, path: $SLUG.key }
---
apiVersion: v1
kind: Service
metadata:
  name: web-$SLUG
  labels: { app.kubernetes.io/name: web-$SLUG }
spec:
  selector: { app.kubernetes.io/name: web-$SLUG }
  ports: [{ name: https, port: $WEB_PORT, targetPort: https }]
YAML
ok "k8s/base/web-$SLUG.yaml"

cat > "k8s/base/bff-$SLUG.yaml" <<YAML
# $SLUG DATA BFF — auth-unaware (forward-auth). Identity from X-Auth-* headers;
# no session, no OIDC. OAUTH_* env is inert here (reuses billing's secret key so
# the pod starts); the real gate is APP_REQUIREMENT_* below → token-handler /auth/verify.
apiVersion: apps/v1
kind: Deployment
metadata:
  name: bff-$SLUG
  labels: { app.kubernetes.io/name: bff-$SLUG, app.kubernetes.io/component: data }
spec:
  replicas: 1
  selector: { matchLabels: { app.kubernetes.io/name: bff-$SLUG } }
  template:
    metadata:
      labels: { app.kubernetes.io/name: bff-$SLUG, app.kubernetes.io/component: data }
    spec:
      securityContext: { runAsNonRoot: true, runAsUser: 1000, fsGroup: 1000 }
      containers:
        - name: bff-$SLUG
          image: keycloak-demo-$SLUG-bff:latest
          imagePullPolicy: IfNotPresent
          ports: [{ name: http, containerPort: 8080 }]
          env:
            - name: REDIS_PASSWORD
              valueFrom: { secretKeyRef: { name: redis-auth, key: password } }
            - name: REDIS_URI
              value: "redis://:\$(REDIS_PASSWORD)@redis:6379"
            - name: OAUTH_CLIENT_ID
              value: "demo-$SLUG-client"
            - name: OAUTH_CLIENT_SECRET
              valueFrom: { secretKeyRef: { name: data-bff-oidc, key: billing-client-secret } }
            - name: KEYCLOAK_ISSUER
              value: "https://auth.geowealth.int/realms/demo-realm"
            - name: KEYCLOAK_AUTH_SERVER_URL
              value: "https://auth.geowealth.int/realms/demo-realm"
            - name: P1_AUTHZ_URL
              value: "https://p1.geowealth.int"
            - name: AUTHZ_FINE_ENABLED
              value: "true"
            - name: APP_REQUIREMENT_TYPE
              value: "$TENANT_TYPE"
            - name: APP_REQUIREMENT_OBJECT_TYPE
              value: "$OBJECT_TYPE"
            - name: APP_REQUIREMENT_PERMISSION
              value: "$PERMISSION"
          resources:
            requests: { cpu: 200m, memory: 384Mi }
            limits:   { cpu: "1", memory: 768Mi }
          readinessProbe:
            httpGet: { path: /health, port: http }
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 6
          livenessProbe:
            httpGet: { path: /health, port: http }
            initialDelaySeconds: 30
            periodSeconds: 15
          volumeMounts: [{ name: tmp, mountPath: /tmp }]
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
      volumes: [{ name: tmp, emptyDir: {} }]
---
apiVersion: v1
kind: Service
metadata:
  name: bff-$SLUG
  labels: { app.kubernetes.io/name: bff-$SLUG }
spec:
  selector: { app.kubernetes.io/name: bff-$SLUG }
  ports: [{ name: http, port: 8080, targetPort: http }]
YAML
ok "k8s/base/bff-$SLUG.yaml"

# --- 8. overlay: add the two manifests to full-stack resources --------------
K="k8s/overlays/full-stack/kustomization.yaml"
if grep -q "base/web-$SLUG.yaml" "$K" 2>/dev/null; then ok "overlay already references web-$SLUG"; else
  sed -i "\#- ../../base/web.yaml#a\\  - ../../base/web-$SLUG.yaml\n  - ../../base/bff-$SLUG.yaml" "$K"
  ok "overlay full-stack += web-$SLUG.yaml, bff-$SLUG.yaml"
fi

# --- 9. ingress host rule ----------------------------------------------------
I="k8s/base/ingress-app.yaml"
if grep -q "name: web-$SLUG" "$I" 2>/dev/null; then ok "ingress already has web-$SLUG"; else
  cat >> "$I" <<YAML
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: web-$SLUG
  labels: { app.kubernetes.io/name: web-$SLUG }
  annotations:
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
spec:
  tls: [{ hosts: ["$HOST"], secretName: web-$SLUG-tls }]
  rules:
    - host: $HOST
      http:
        paths:
          - { path: /, pathType: Prefix, backend: { service: { name: web-$SLUG, port: { number: $WEB_PORT } } } }
YAML
  ok "ingress-app.yaml += $HOST"
fi

# --- 10. mkcert cert + live TLS secret --------------------------------------
CRT="proxy/certs/$HOST.crt"; KEY="proxy/certs/$HOST.key"
if [ -f "$CRT" ] && [ -f "$KEY" ]; then ok "cert already exists ($CRT)"; elif [ "$HAVE_MKCERT" = 1 ]; then
  mkdir -p proxy/certs
  mkcert -cert-file "$CRT" -key-file "$KEY" "$HOST" localhost 127.0.0.1 >/dev/null 2>&1 \
    && ok "mkcert cert $CRT" || echo "   (mkcert failed — generate manually)"
else
  echo "   (mkcert not installed — create $CRT / $KEY manually)"
fi
# live TLS secret if a cluster is up
if command -v kubectl >/dev/null && kubectl get ns geowealth-demo >/dev/null 2>&1 && [ -f "$CRT" ]; then
  kubectl -n geowealth-demo create secret tls "web-$SLUG-tls" --cert="$CRT" --key="$KEY" \
    --dry-run=client -o yaml | kubectl -n geowealth-demo apply -f - >/dev/null 2>&1 \
    && ok "live Secret web-$SLUG-tls applied"
fi

# --- 11. /etc/hosts ----------------------------------------------------------
if grep -qE "^[^#]*\b$HOST\b" /etc/hosts 2>/dev/null; then ok "/etc/hosts already has $HOST"; elif [ "$TOUCH_HOSTS" = 1 ]; then
  if printf '127.0.0.1 %s\n' "$HOST" | sudo tee -a /etc/hosts >/dev/null 2>&1; then ok "/etc/hosts += $HOST"
  else echo "   (could not sudo — add manually:  127.0.0.1 $HOST)"; fi
else
  echo "   (skipped /etc/hosts — add:  127.0.0.1 $HOST)"
fi

# --- 12. build images + deploy live (if the minikube cluster is up) ---------
# Cluster UP  → build the two images into minikube's docker, apply the new
#               manifests + ingress + tenant ConfigMap, restart token-handler,
#               and reconcile the realm so login redirects to the new host work.
# Cluster DOWN → nothing to build now; the files are wired, and the next
#               ./k8s/up.sh (now domain-generic) builds + deploys $SLUG.
MK_PROFILE="${MINIKUBE_PROFILE:-geowealth}"
DEPLOYED=0
if [ "$DEPLOY" = 1 ] && command -v minikube >/dev/null 2>&1 && minikube -p "$MK_PROFILE" status >/dev/null 2>&1; then
  info "Cluster '$MK_PROFILE' is up — building images + deploying $SLUG live"
  eval "$(minikube -p "$MK_PROFILE" docker-env)"
  ( docker build -t "keycloak-demo-$SLUG-bff:latest" -f "domains/$SLUG/bff/Dockerfile" . \
    && docker build -t "keycloak-demo-$SLUG-web:latest" -f "domains/$SLUG/web/Dockerfile" . ) \
    && ok "built keycloak-demo-$SLUG-{web,bff}:latest" || echo "   (image build failed — check Dockerfiles)"
  eval "$(minikube -p "$MK_PROFILE" docker-env -u)"

  kubectl -n geowealth-demo apply -f "k8s/base/web-$SLUG.yaml" -f "k8s/base/bff-$SLUG.yaml" >/dev/null && ok "applied web-$SLUG + bff-$SLUG"
  kubectl -n geowealth-demo apply -f k8s/base/ingress-app.yaml >/dev/null 2>&1 && ok "applied ingress"
  # Only the ConfigMap doc (first in the file) — avoid re-applying the Secrets after it.
  awk 'BEGIN{p=1} /^---[[:space:]]*$/{p=0} p' k8s/base/token-handler-config.yaml \
    | kubectl -n geowealth-demo apply -f - >/dev/null 2>&1 && ok "updated token-handler tenants ConfigMap"
  kubectl -n geowealth-demo rollout restart deploy/token-handler >/dev/null 2>&1 && ok "restarted token-handler (new tenant)"
  # Realm redirect/webOrigin/post-logout for the new host (live realm via admin API).
  if [ -x scripts/reconcile-realm.sh ]; then
    kubectl -n geowealth-demo port-forward svc/keycloak 18080:8080 >/dev/null 2>&1 &
    _pf=$!; sleep 3
    KC_ADMIN_BASE="http://localhost:18080" ./scripts/reconcile-realm.sh k8s/env/urls.dev.env >/dev/null 2>&1 \
      && ok "reconciled realm URLs" || echo "   (realm reconcile skipped/failed — run it manually)"
    kill "$_pf" 2>/dev/null || true
  fi
  kubectl -n geowealth-demo rollout status "deploy/bff-$SLUG" --timeout=150s >/dev/null 2>&1 && ok "bff-$SLUG ready" || echo "   (bff-$SLUG not ready yet — kubectl get pods)"
  kubectl -n geowealth-demo rollout status "deploy/web-$SLUG" --timeout=120s >/dev/null 2>&1 && ok "web-$SLUG ready" || echo "   (web-$SLUG not ready yet)"
  DEPLOYED=1
elif [ "$DEPLOY" = 1 ]; then
  info "Cluster '$MK_PROFILE' not running — files wired; the next ./k8s/up.sh will build & deploy $SLUG"
fi

# --- done --------------------------------------------------------------------
if [ "$DEPLOYED" = 1 ]; then
  info "Domain '$SLUG' is LIVE. Open:  https://$HOST"
  echo "   (via ingress on the minikube IP; add '\$(minikube -p $MK_PROFILE ip) $HOST' to /etc/hosts if needed)"
  echo "   Toggle:  ./k8s/toggle.sh $SLUG up|down"
else
  info "Domain '$SLUG' scaffolded. Next:"
  cat <<NEXT
  - K8s:      ./k8s/up.sh            # builds + deploys $SLUG (up.sh is domain-generic)
  - Compose:  docker compose up -d --build demo-$SLUG bff-$SLUG
              docker compose up -d --build --force-recreate token-handler   # new tenant
              ./scripts/reconcile-realm.sh k8s/env/urls.dev.env             # realm URLs
  - Open:     https://$HOST:$WEB_PORT      Toggle: ./k8s/toggle.sh $SLUG up|down
NEXT
fi
echo "Customize authz: tenant object-type=$OBJECT_TYPE / permission=$PERMISSION (type=$TENANT_TYPE), DemoAuthz.java in domains/$SLUG/bff/."
