FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM node:20-alpine
WORKDIR /app

# Copy the already-installed node_modules so `vite preview` can resolve
# both `vite` itself and the `@vitejs/plugin-react` referenced by
# vite.config.ts at preview-server startup.
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY package.json vite.config.ts entrypoint.cjs ./

EXPOSE 5185
# entrypoint.cjs runs vite preview on an internal port (6185) and listens
# on 5185 itself, sniffing the first byte: TLS → pass-through, plain HTTP
# → 301 to https://.
CMD ["node", "entrypoint.cjs", "5185"]
