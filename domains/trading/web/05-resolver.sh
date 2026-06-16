#!/bin/sh
# Pick up the host's actual DNS resolver from /etc/resolv.conf and swap it into
# nginx.conf in place of the docker-compose default (127.0.0.11). nginx's
# `resolver` directive does NOT honor /etc/resolv.conf — it MUST be given an
# explicit IP — and it also ignores the `search` list libc uses, so bare
# Kubernetes Service names (e.g. `token-handler:8080`) fail with SERVFAIL.
# When we detect a K8s-style search domain (*.svc.cluster.local), rewrite the
# upstreams to fully-qualified names so CoreDNS resolves them in one shot. In
# compose, neither rewrite applies and the original `127.0.0.11 + bare name`
# config is preserved.
set -eu
DNS="$(awk '/^nameserver/ {print $2; exit}' /etc/resolv.conf 2>/dev/null || true)"
SEARCH="$(awk '/^search/ {print $2; exit}' /etc/resolv.conf 2>/dev/null || true)"
[ -n "$DNS" ] || { echo "dns-resolver: no nameserver in /etc/resolv.conf, leaving default" >&2; exit 0; }
sed -i "s|resolver 127\\.0\\.0\\.11|resolver $DNS|" /etc/nginx/nginx.conf
case "${SEARCH}" in
  *.svc.cluster.local)
    for svc in token-handler bff-billing bff-trading; do
      sed -i "s|\"${svc}:|\"${svc}.${SEARCH}:|g" /etc/nginx/nginx.conf
    done
    echo "dns-resolver: $DNS, K8s upstreams qualified with .${SEARCH}"
    ;;
  *)
    echo "dns-resolver: $DNS, bare upstreams kept"
    ;;
esac
