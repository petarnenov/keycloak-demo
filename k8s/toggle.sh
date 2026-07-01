#!/usr/bin/env bash
# toggle.sh — start/stop groups of the geowealth-demo stack in a dev cluster.
#
# Usage:
#   ./k8s/toggle.sh <group> up|down      # scale a named group (e.g. billing, trading)
#   ./k8s/toggle.sh agent:<name> up|down # a single P1 agent (e.g. agent:crm)
#   ./k8s/toggle.sh web:<slug>  up|down  # just a domain SPA   (e.g. web:billing)
#   ./k8s/toggle.sh bff:<slug>  up|down  # just a domain BFF   (e.g. bff:trading)
#   ./k8s/toggle.sh status               # show current replicas per workload
#   ./k8s/toggle.sh groups               # list group names
#
# Profiles — capture the CURRENT cluster shape, replay it later:
#   ./k8s/toggle.sh save <name>          # export current replicas + HPA mins → k8s/profiles/<name>.profile
#   ./k8s/toggle.sh apply <name>         # scale the cluster back to a saved profile
#   ./k8s/toggle.sh profiles             # list saved profiles
# Typical: tune with up/down until happy, `save login-only`, then any day `apply login-only`.
#
# HPA-safe: token-handler / user-service / p1-tomcat have HPAs, so a plain
# `kubectl scale --replicas=0` is reverted within ~15s. For those, `down`
# patches HPA minReplicas=0 first; `up` restores the default minReplicas.
#
# NOTE: a later `./k8s/up.sh` re-applies the full-stack overlay and resurrects
# everything. This is a session-local lever (see k8s/README-dev-selective-bringup.md).
set -euo pipefail

NS="${NS:-geowealth-demo}"
PROFILE_DIR="${PROFILE_DIR:-$(cd "$(dirname "$0")" && pwd)/profiles}"
kc() { kubectl -n "$NS" "$@"; }
log() { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }

# --- workloads ---------------------------------------------------------------
# P1 Akka agents (Deployments; NOT coordinator/tomcat)
AGENTS=(p1-devcommonagents p1-useragents p1-samlmanager p1-crm p1-searchagents
        p1-mostagents p1-cspagents p1-proposalagents p1-emailagent p1-reportengine)

# Deployments that own an HPA. Kubernetes rejects HPA minReplicas=0 unless the
# HPAScaleToZero feature gate is on (it isn't on stock minikube), so to turn an
# HPA-managed workload OFF we DELETE its HPA and scale the Deployment to 0. The
# HPA is re-created by `./k8s/up.sh` (which re-applies the overlay). Turning it
# ON while the HPA is gone falls back to a fixed replica until the next up.sh.
HPA_DEPLOYS=" token-handler user-service p1-tomcat "

# --- domain discovery (dynamic, from the cluster) ----------------------------
# A "domain" = any web-<slug> / bff-<slug> Deployment present in the namespace.
# No script edit is needed when a new domain is added — it is read live.
discover_domain_workloads() { # → deploy/web-*, deploy/bff-*
  kc get deploy -o name 2>/dev/null \
    | sed 's#^deployment\.apps/##' \
    | grep -E '^(web|bff)-' \
    | sed 's#^#deploy/#'
}
discover_domain_slugs() {      # → billing trading …
  discover_domain_workloads | sed -E 's#^deploy/(web|bff)-##' | sort -u
}
domain_slug_members() {        # <slug> → the web/bff of that slug that actually exist
  local slug="$1" w out=()
  for w in "web-$slug" "bff-$slug"; do
    kc get deploy "$w" >/dev/null 2>&1 && out+=("deploy/$w")
  done
  ((${#out[@]})) && printf '%s\n' "${out[@]}"
}

# --- group → workload list ---------------------------------------------------
# Fixed groups first; anything else is tried as a live domain slug.
group_members() {
  case "$1" in
    p1)        printf '%s\n' statefulset/p1-coordinator deploy/p1-tomcat \
                             "${AGENTS[@]/#/deploy/}" ;;
    agents)    printf '%s\n' "${AGENTS[@]/#/deploy/}" ;;
    authz-min) printf '%s\n' statefulset/p1-coordinator deploy/p1-tomcat deploy/p1-devcommonagents ;;
    domains)   discover_domain_workloads ;;                # ALL live domains
    identity)  printf '%s\n' statefulset/keycloak deploy/user-service deploy/token-handler ;;
    data)      printf '%s\n' statefulset/oracle statefulset/elasticsearch statefulset/kc-postgres statefulset/redis ;;
    agent:*)   printf '%s\n' "deploy/p1-${1#agent:}" ;;
    web:*)     printf '%s\n' "deploy/web-${1#web:}" ;;     # just the SPA of a domain
    bff:*)     printf '%s\n' "deploy/bff-${1#bff:}" ;;     # just the data BFF of a domain
    *)         # bare domain slug (e.g. billing, trading, reporting) — resolved live
               local m; m="$(domain_slug_members "$1" || true)"
               if [[ -n "$m" ]]; then printf '%s\n' "$m"
               else echo "unknown group / domain: $1" >&2; return 1; fi ;;
  esac
}

cmd_groups() {
  echo "fixed:   p1 agents authz-min domains identity data"
  echo "params:  agent:<name> web:<slug> bff:<slug>"
  echo "domains (live): $(discover_domain_slugs | paste -sd' ' -)"
}

# --- scale one "kind/name" ---------------------------------------------------
scale_one() { # <kind/name> <replicas>
  local ref="$1" reps="$2" kind="${1%%/*}" name="${1##*/}"
  # OFF an HPA-managed Deployment → delete the HPA first (min=0 is not allowed),
  # then scale to 0 so it stays down.
  if [[ "$HPA_DEPLOYS" == *" $name "* && "$reps" == "0" ]]; then
    kc delete hpa "$name" --ignore-not-found >/dev/null 2>&1 || true
  fi
  kc scale "$kind/$name" --replicas="$reps" 2>/dev/null \
    && printf '   %-28s -> %s%s\n' "$ref" "$reps" \
         "$([[ "$HPA_DEPLOYS" == *" $name "* && "$reps" == "0" ]] && echo ' (hpa removed; up.sh restores)')" \
    || printf '   %-28s (missing, skipped)\n' "$ref"
}

cmd_toggle() { # <group> <up|down>
  local group="$1" dir="$2" reps members
  case "$dir" in up) reps=1 ;; down) reps=0 ;; *) echo "dir must be up|down" >&2; exit 2 ;; esac
  members="$(group_members "$group" || true)"      # group_members prints its own error
  [[ -n "$members" ]] || { echo "no workloads for '$group' — try: $0 groups" >&2; exit 2; }
  log "$group $dir"
  while read -r ref; do scale_one "$ref" "$reps"; done <<< "$members"
}

cmd_status() {
  log "replicas in $NS"
  kc get deploy,statefulset \
    -o custom-columns='KIND:.kind,NAME:.metadata.name,DESIRED:.spec.replicas,READY:.status.readyReplicas' \
    2>/dev/null | sed 's/^/   /'
  echo
  log "HPAs (minReplicas matters for scale-to-0)"
  kc get hpa -o custom-columns='NAME:.metadata.name,MIN:.spec.minReplicas,MAX:.spec.maxReplicas,CUR:.status.currentReplicas' \
    2>/dev/null | sed 's/^/   /'
}

# --- profiles ----------------------------------------------------------------
# A profile is a snapshot of every Deployment/StatefulSet desired-replicas plus
# every HPA minReplicas, so `apply` reproduces the exact run-set (HPA-safe).
cmd_save() { # <name>
  local name="${1:-}"; [[ -n "$name" ]] || { echo "usage: save <name>" >&2; exit 2; }
  mkdir -p "$PROFILE_DIR"
  local f="$PROFILE_DIR/$name.profile"
  {
    echo "# toggle profile '$name' — namespace $NS — saved $(date -u +%FT%TZ)"
    echo "# <kind/name>\t<replicas | hpa minReplicas>"
    kc get deploy      -o jsonpath='{range .items[*]}deploy/{.metadata.name}{"\t"}{.spec.replicas}{"\n"}{end}'
    kc get statefulset -o jsonpath='{range .items[*]}statefulset/{.metadata.name}{"\t"}{.spec.replicas}{"\n"}{end}'
    kc get hpa         -o jsonpath='{range .items[*]}hpa/{.metadata.name}{"\t"}{.spec.minReplicas}{"\n"}{end}'
  } > "$f"
  log "saved $f"; grep -vE '^#' "$f" | sed 's/^/   /'
}

cmd_apply() { # <name>
  local name="${1:-}" f ref val nm
  [[ -n "$name" ]] || { echo "usage: apply <name>" >&2; exit 2; }
  f="$PROFILE_DIR/$name.profile"
  [[ -f "$f" ]] || { echo "no such profile: $f" >&2; exit 2; }
  # Parse profile into maps: REP[kind/name]=replicas, HMIN[name]=saved hpa min.
  local -A REP=() HMIN=()
  while IFS=$'\t' read -r ref val; do
    [[ "$ref" == \#* || -z "$ref" || -z "$val" ]] && continue
    case "$ref" in
      hpa/*)                  HMIN["${ref#hpa/}"]="$val" ;;
      deploy/*|statefulset/*) REP["$ref"]="$val" ;;
    esac
  done < "$f"
  log "apply profile '$name'"
  # HPA-managed workloads: on/off is driven by the Deployment's saved replicas
  # (a profile saved from an inconsistent live state can have hpa-min>0 while the
  # deploy is 0 — the deploy value is the intent). OFF → delete HPA + scale 0
  # (min=0 is not allowed on stock K8s). ON → set minReplicas to the saved value
  # (>=1); if the HPA was previously removed, fall back to a fixed replica.
  for nm in "${!HMIN[@]}"; do
    local reps="${REP[deploy/$nm]:-0}" target
    if [[ "${reps:-0}" == "0" ]]; then
      kc delete hpa "$nm" --ignore-not-found >/dev/null 2>&1 || true
      kc scale "deploy/$nm" --replicas=0 >/dev/null 2>&1 || true
      printf '   %-28s OFF (hpa removed, scaled 0)\n' "deploy/$nm"
    else
      target="${HMIN[$nm]}"; [[ -z "$target" || "$target" == 0 ]] && target=1
      if kc get hpa "$nm" >/dev/null 2>&1; then
        kc patch hpa "$nm" --type merge -p "{\"spec\":{\"minReplicas\":$target}}" >/dev/null 2>&1 || true
      else
        kc scale "deploy/$nm" --replicas="$target" >/dev/null 2>&1 || true
      fi
      printf '   %-28s ON (min/replicas=%s)\n' "deploy/$nm" "$target"
    fi
  done
  # Plain workloads (everything without an HPA): scale to the saved replicas.
  for ref in "${!REP[@]}"; do
    nm="${ref##*/}"
    [[ "$ref" == deploy/* && "$HPA_DEPLOYS" == *" $nm "* ]] && continue
    kc scale "$ref" --replicas="${REP[$ref]}" >/dev/null 2>&1 \
      && printf '   %-28s -> %s\n' "$ref" "${REP[$ref]}" \
      || printf '   %-28s (missing, skipped)\n' "$ref"
  done
}

cmd_profiles() {
  [[ -d "$PROFILE_DIR" ]] || { echo "(no profiles yet — save one with: $0 save <name>)"; return; }
  log "profiles in $PROFILE_DIR"
  local any=0
  for f in "$PROFILE_DIR"/*.profile; do
    [[ -e "$f" ]] || continue; any=1
    printf '   %-20s %s\n' "$(basename "$f" .profile)" "$(sed -n '1s/^# //p' "$f")"
  done
  [[ $any -eq 1 ]] || echo "   (none)"
}

# --- dispatch ----------------------------------------------------------------
case "${1:-}" in
  ""|-h|--help) grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//' ;;
  groups)   cmd_groups ;;
  status)   cmd_status ;;
  save)     cmd_save "${2:-}" ;;
  apply)    cmd_apply "${2:-}" ;;
  profiles) cmd_profiles ;;
  *)        [[ $# -eq 2 ]] || { echo "usage: $0 <group> up|down | status | groups | save <name> | apply <name> | profiles" >&2; exit 2; }
            cmd_toggle "$1" "$2" ;;
esac
