#!/usr/bin/env python3
"""Fake GeoWealth branding API for the Keycloak white-labeling POC.

Stand-in for the Tomcat-side servlet that lives in the geowealth repo on
`team/petarnenov/keycloak-whitelabel-poc`. This lets Keycloak's
`BrandingApiClient` hit a real HTTP endpoint and exercise the full SPI
loop — cache, theme selector, FreeMarker injection — before the real
backend is reachable from this machine.

Contract: contracts/branding-api.openapi.yaml (single source of truth).

Behavior:
  GET /branding-api/keycloak/whitelabel/{code}                 → Brand JSON
  GET /branding-api/keycloak/whitelabel/lookup?host=<host>     → {code}
  GET /branding-api/keycloak/whitelabel/{code}/asset/{kind}    → SVG/PNG bytes

Two firms are served: `changepath` and `cca` (the GeoWealth default).
Colors match `BrandRegistry.java` exactly so the visual output is identical
whether Keycloak is reading from the API or falling back to the hardcoded
registry. Unknown firm codes return 404; unknown hosts on /lookup fall
through to `cca` (matches AuthorizationManager.identifyFirmByUrl()).

Auth: bearer token from `POC_BRANDING_API_TOKEN` env var, constant-time
compared via `hmac.compare_digest`. The token is never logged.

Bind: 127.0.0.1:8080 by default (Keycloak in compose reaches it via
`host.docker.internal:8080` on macOS). Override with `HOST` / `PORT` env
vars. Refuses to start without a token — fail fast beats a fake that
silently lets every unauthenticated request through.
"""

import hmac
import http
import json
import logging
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


# --- Branding data ---------------------------------------------------------
# Keep in sync with geowealth-keycloak/src/main/java/com/geowealth/keycloak/
# branding/BrandRegistry.java. If you change a color here, mirror it there
# (or vice versa) so a fallback never visually drifts from the API path.

DEFAULT_CODE = "cca"

BRANDS = {
    "changepath": {
        "code": "changepath",
        "firmCd": 4,
        "displayName": "ChangePath",
        "supportEmail": "supportemail@changepath.com",
        "phone": "888.798.2360",
        "website": "http://www.changepath.com/",
        "cssVariables": {
            "--theme-link-color":            "#155e8f",
            "--theme-gradient-start":        "#0e4e79",
            "--theme-gradient-end":          "#b5dea4",
            "--theme-body-background":       "#f5f5f5",
            "--theme-button-background":     "#155e8f",
            "--pf-v5-global--primary-color--100":   "#155e8f",
            "--pf-v5-global--primary-color--200":   "#0e4e79",
            "--pf-v5-global--link--Color":          "#155e8f",
            "--pf-v5-global--link--Color--hover":   "#0e4e79",
            "--pf-v5-global--active-color--100":    "#155e8f",
        },
        "version": "2026-05-21T09:14:22Z",
    },
    "cca": {
        "code": "cca",
        "firmCd": 1,
        "displayName": "GeoWealth",
        "supportEmail": "support@geowealth.com",
        "cssVariables": {
            "--theme-link-color":            "#c8482a",
            "--theme-gradient-start":        "#1f4d3f",
            "--theme-gradient-end":          "#e6b85c",
            "--theme-body-background":       "#fafaf6",
            "--theme-button-background":     "#c8482a",
            "--pf-v5-global--primary-color--100":   "#c8482a",
            "--pf-v5-global--primary-color--200":   "#a83a20",
            "--pf-v5-global--link--Color":          "#c8482a",
            "--pf-v5-global--link--Color--hover":   "#a83a20",
            "--pf-v5-global--active-color--100":    "#c8482a",
        },
        "version": "2026-05-21T09:14:22Z",
    },
}


# Trivial inline SVG placeholders so the asset endpoint is exercisable even
# though no real Keycloak phase consumes it yet. Color matches each brand's
# primary so a future caller renders something sensible at a glance.
def _placeholder_svg(label: str, fill: str) -> bytes:
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60">'
        f'<rect width="200" height="60" fill="{fill}"/>'
        '<text x="100" y="36" text-anchor="middle" font-family="Arial,Helvetica,sans-serif" '
        f'font-size="18" fill="#fff">{label}</text>'
        '</svg>'
    ).encode("utf-8")


ASSETS = {
    "changepath": {
        "logo-login":       _placeholder_svg("ChangePath", "#155e8f"),
        "logo-login-small": _placeholder_svg("CP",         "#155e8f"),
        "favicon":          _placeholder_svg("CP",         "#155e8f"),
        "terms":            b"%PDF-1.4\n% fake ChangePath terms\n",
    },
    "cca": {
        "logo-login":       _placeholder_svg("GeoWealth",  "#c8482a"),
        "logo-login-small": _placeholder_svg("GW",         "#c8482a"),
        "favicon":          _placeholder_svg("GW",         "#c8482a"),
        "terms":            b"%PDF-1.4\n% fake GeoWealth terms\n",
    },
}

ASSET_CONTENT_TYPES = {
    "logo-login":       "image/svg+xml",
    "logo-login-small": "image/svg+xml",
    "favicon":          "image/svg+xml",
    "terms":            "application/pdf",
}


# --- Auth ------------------------------------------------------------------

def _expected_token() -> str:
    tok = os.environ.get("POC_BRANDING_API_TOKEN", "").strip()
    if not tok:
        sys.stderr.write(
            "FATAL: POC_BRANDING_API_TOKEN is not set. Source .envrc or "
            "export it before starting this server.\n"
        )
        sys.exit(1)
    return tok


def _token_ok(presented: str, expected: str) -> bool:
    # constant-time compare; both sides must be bytes of equal length
    return hmac.compare_digest(presented.encode("utf-8"), expected.encode("utf-8"))


# --- Hostname → firm code (mirrors AuthorizationManager.identifyFirmByUrl) -

def _firm_code_from_host(host: str) -> str:
    if not host:
        return DEFAULT_CODE
    h = host.lower()
    colon = h.find(":")
    if colon >= 0:
        h = h[:colon]
    if h in ("localhost", "127.0.0.1"):
        return DEFAULT_CODE
    dot = h.find(".")
    if dot <= 0:
        return DEFAULT_CODE
    candidate = h[:dot]
    return candidate if candidate in BRANDS else DEFAULT_CODE


# --- HTTP layer ------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "FakeBrandingAPI/0.1"
    expected_token = ""   # populated at server-construction time

    # Silence the default per-request access log so our INFO logger owns
    # output. The base class writes to stderr in a hard-to-grep format.
    def log_message(self, format, *args):  # noqa: A002 — base class signature
        pass

    def _write_json(self, status: int, payload: dict):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _write_error(self, status: int, tag: str, message: str):
        self._write_json(status, {"error": tag, "message": message})

    def _write_bytes(self, status: int, content_type: str, body: bytes,
                     cache_control: str = "public, max-age=300"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache_control)
        self.end_headers()
        self.wfile.write(body)

    def _check_auth(self) -> bool:
        raw = self.headers.get("Authorization", "")
        if not raw.startswith("Bearer "):
            return False
        presented = raw[len("Bearer "):].strip()
        return _token_ok(presented, self.expected_token)

    def do_GET(self):
        started = time.monotonic()
        parsed = urlsplit(self.path)
        path = parsed.path
        decision = "unhandled"
        status_code = 500
        try:
            if not self._check_auth():
                decision = "unauthorized"
                status_code = 401
                self._write_error(401, "unauthorized", "missing or invalid bearer token")
                return

            if path.startswith("/branding-api/keycloak/whitelabel/lookup"):
                qs = parse_qs(parsed.query)
                host_values = qs.get("host", [])
                if not host_values or not host_values[0].strip():
                    decision = "lookup_missing_host"
                    status_code = 400
                    self._write_error(400, "bad_request", "missing 'host' query parameter")
                    return
                code = _firm_code_from_host(host_values[0])
                decision = f"lookup:{host_values[0]}->{code}"
                status_code = 200
                self._write_json(200, {"code": code})
                return

            parts = path.strip("/").split("/")
            # Expected shapes:
            #   branding-api/keycloak/whitelabel/{code}
            #   branding-api/keycloak/whitelabel/{code}/asset/{kind}
            if (
                len(parts) >= 4
                and parts[0] == "branding-api"
                and parts[1] == "keycloak"
                and parts[2] == "whitelabel"
            ):
                code = parts[3]
                if len(parts) == 4:
                    brand = BRANDS.get(code)
                    if not brand:
                        decision = f"brand_not_found:{code}"
                        status_code = 404
                        self._write_error(404, "not_found", f"no whitelabel for code '{code}'")
                        return
                    decision = f"brand_hit:{code}"
                    status_code = 200
                    self._write_json(200, brand)
                    return

                if len(parts) == 6 and parts[4] == "asset":
                    kind = parts[5]
                    bucket = ASSETS.get(code)
                    if not bucket or kind not in bucket:
                        decision = f"asset_not_found:{code}/{kind}"
                        status_code = 404
                        self._write_error(404, "not_found", f"no asset '{kind}' for code '{code}'")
                        return
                    decision = f"asset_hit:{code}/{kind}"
                    status_code = 200
                    self._write_bytes(200, ASSET_CONTENT_TYPES.get(kind, "application/octet-stream"),
                                      bucket[kind])
                    return

            decision = "unknown_route"
            status_code = 404
            self._write_error(404, "not_found", f"unknown path {path}")
        except Exception as exc:  # pragma: no cover — defense in depth
            decision = f"exception:{type(exc).__name__}"
            status_code = 500
            try:
                self._write_error(500, "internal", "unhandled server error")
            except Exception:
                pass
        finally:
            elapsed_ms = (time.monotonic() - started) * 1000.0
            logging.info(
                "GET %s host=%s status=%d decision=%s latency_ms=%.1f",
                path, self.headers.get("Host", "-"), status_code, decision, elapsed_ms,
            )


def main():
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s fake-branding-api %(message)s",
    )
    Handler.expected_token = _expected_token()
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer((host, port), Handler)
    logging.info(
        "listening on http://%s:%d (firms: %s; default=%s)",
        host, port, ", ".join(sorted(BRANDS.keys())), DEFAULT_CODE,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("shutting down")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
