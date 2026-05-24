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
# vite.config.ts at preview-server startup. The dist/ is the actual
# served content; package.json + vite.config.ts feed the preview
# server's static-file routing.
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY package.json vite.config.ts entrypoint.cjs ./

EXPOSE 5184
# entrypoint.cjs runs vite preview on an internal port (6184) and listens
# on 5184 itself, sniffing the first byte: TLS → pass-through, plain HTTP
# → 301 to https://. Gives the same UX as putting an nginx sidecar in
# front, without pulling in a second image.
CMD ["node", "entrypoint.cjs", "5184"]
