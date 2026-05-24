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
COPY package.json vite.config.ts ./

EXPOSE 5184
CMD ["npx", "vite", "preview", "--host", "0.0.0.0", "--port", "5184", "--strictPort"]
