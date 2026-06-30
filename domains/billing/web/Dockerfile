FROM node:20-alpine AS builder
WORKDIR /app

COPY domains/billing/web/package.json ./
RUN npm install --no-audit --no-fund

COPY domains/billing/web/tsconfig.json domains/billing/web/vite.config.ts domains/billing/web/index.html ./
COPY domains/billing/web/src ./src
COPY domains/billing/web/public ./public
# vite.config.ts imports ../../../scripts/vite-forward-auth-plugin.mjs (dev-only
# plugins; unused in image build but required to load the config).
COPY scripts/vite-forward-auth-plugin.mjs /scripts/vite-forward-auth-plugin.mjs
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY domains/billing/web/nginx.conf /etc/nginx/nginx.conf
# Swaps the compose-default resolver (127.0.0.11) for the host's actual nameserver
# at start so the same image works in K8s (CoreDNS) and compose (Docker DNS).
COPY domains/billing/web/05-resolver.sh /docker-entrypoint.d/05-resolver.sh
RUN chmod +x /docker-entrypoint.d/05-resolver.sh

EXPOSE 5184
# Default nginx CMD already runs `nginx -g "daemon off;"`; no need to override.
