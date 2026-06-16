FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
COPY public ./public
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
# Swaps the compose-default resolver (127.0.0.11) for the host's actual nameserver
# at start so the same image works in K8s (CoreDNS) and compose (Docker DNS).
COPY 05-resolver.sh /docker-entrypoint.d/05-resolver.sh
RUN chmod +x /docker-entrypoint.d/05-resolver.sh

EXPOSE 5184
# Default nginx CMD already runs `nginx -g "daemon off;"`; no need to override.
