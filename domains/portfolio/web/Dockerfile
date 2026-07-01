FROM node:20-alpine AS builder
WORKDIR /app

COPY domains/portfolio/web/package.json ./
RUN npm install --no-audit --no-fund

COPY domains/portfolio/web/tsconfig.json domains/portfolio/web/vite.config.ts domains/portfolio/web/index.html ./
COPY domains/portfolio/web/src ./src
COPY domains/portfolio/web/public ./public
# vite.config.ts imports ../../../scripts/vite-forward-auth-plugin.mjs (dev-only
# plugins; unused in image build but required to load the config).
COPY scripts/vite-forward-auth-plugin.mjs /scripts/vite-forward-auth-plugin.mjs
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY domains/portfolio/web/nginx.conf /etc/nginx/nginx.conf
# Swaps the compose-default resolver (127.0.0.11) for the host's actual nameserver
# at start so the same image works in K8s (CoreDNS) and compose (Docker DNS).
COPY domains/portfolio/web/05-resolver.sh /docker-entrypoint.d/05-resolver.sh
RUN chmod +x /docker-entrypoint.d/05-resolver.sh

EXPOSE 5186
# Default nginx CMD already runs `nginx -g "daemon off;"`; no need to override.
