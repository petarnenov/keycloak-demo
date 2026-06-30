/**
 * Vite dev-server middleware: mirrors nginx auth_request → token-handler /auth/verify
 * before proxying /api/<domain> to the data BFF. Keeps forward-auth semantics while
 * serving the SPA with HMR on the host.
 */
import http from 'node:http';
import https from 'node:https';

function httpRequest(url, { method = 'GET', headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.request(
      {
        hostname: u.hostname,
        port: u.port || (u.protocol === 'https:' ? 443 : 80),
        path: u.pathname + u.search,
        method,
        headers,
        rejectUnauthorized: false
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () =>
          resolve({
            status: res.statusCode ?? 502,
            headers: res.headers,
            body: Buffer.concat(chunks)
          })
        );
      }
    );
    req.on('error', reject);
    req.end();
  });
}

function pickAuthHeaders(verifyHeaders) {
  const out = {};
  for (const [k, v] of Object.entries(verifyHeaders)) {
    const lower = k.toLowerCase();
    if (lower.startsWith('x-auth-') && v != null) {
      out[k] = Array.isArray(v) ? v.join(',') : String(v);
    }
  }
  return out;
}

export function forwardAuthPlugin({
  apiPrefix,
  bffRewritePrefix = '/api',
  verifyBaseUrl = 'http://127.0.0.1:9080',
  bffBaseUrl = 'http://127.0.0.1:8084',
  forwardedHost
}) {
  const verifyUrl = `${verifyBaseUrl.replace(/\/$/, '')}/auth/verify`;

  return {
    name: 'forward-auth-dev',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = req.url ?? '';
        if (!url.startsWith(apiPrefix)) {
          return next();
        }

        try {
          const host = forwardedHost ?? req.headers.host ?? 'localhost';
          const verify = await httpRequest(verifyUrl, {
            method: 'GET',
            headers: {
              Cookie: req.headers.cookie ?? '',
              Host: host,
              'X-Forwarded-Host': host,
              'X-Forwarded-Proto': 'https'
            }
          });

          if (verify.status !== 200) {
            res.statusCode = verify.status === 401 || verify.status === 403 ? verify.status : 502;
            res.end(verify.body.length ? verify.body : 'auth verify failed');
            return;
          }

          const authHeaders = pickAuthHeaders(verify.headers);
          const rewritten = url.replace(new RegExp(`^${apiPrefix}`), bffRewritePrefix);
          const target = new URL(rewritten, bffBaseUrl);

          const proxyReq = http.request(
            {
              hostname: target.hostname,
              port: target.port || 80,
              path: target.pathname + target.search,
              method: req.method,
              headers: {
                ...req.headers,
                host: target.host,
                cookie: '',
                ...authHeaders
              }
            },
            (proxyRes) => {
              res.writeHead(proxyRes.statusCode ?? 502, proxyRes.headers);
              proxyRes.pipe(res);
            }
          );
          proxyReq.on('error', (err) => {
            res.statusCode = 502;
            res.end(String(err));
          });
          req.pipe(proxyReq);
        } catch (err) {
          res.statusCode = 502;
          res.end(String(err));
        }
      });
    }
  };
}

export function tokenHandlerProxyPlugin(baseUrl = 'http://127.0.0.1:9080') {
  const target = baseUrl.replace(/\/$/, '');
  return {
    name: 'token-handler-proxy',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = req.url ?? '';
        if (
          !url.startsWith('/oauth') &&
          !url.startsWith('/auth') &&
          url !== '/logout' &&
          !url.startsWith('/logout?')
        ) {
          return next();
        }
        try {
          const dest = new URL(url, target);
          // Mirror domains/*/web/nginx.conf: token-handler derives redirect_uri from
          // Host + X-Forwarded-*; without Proto=https KC rejects the callback URL.
          const host = req.headers.host ?? 'localhost';
          const proxyReq = http.request(
            {
              hostname: dest.hostname,
              port: dest.port || 80,
              path: dest.pathname + dest.search,
              method: req.method,
              headers: {
                ...req.headers,
                host,
                'x-forwarded-proto': 'https',
                'x-forwarded-host': host
              }
            },
            (proxyRes) => {
              res.writeHead(proxyRes.statusCode ?? 502, proxyRes.headers);
              proxyRes.pipe(res);
            }
          );
          proxyReq.on('error', (err) => {
            res.statusCode = 502;
            res.end(String(err));
          });
          req.pipe(proxyReq);
        } catch (err) {
          res.statusCode = 502;
          res.end(String(err));
        }
      });
    }
  };
}
