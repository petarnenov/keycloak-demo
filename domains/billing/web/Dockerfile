FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM node:20-alpine
WORKDIR /app

# Bring the build output, the lockless package.json, and a slimmed
# install of just what `vite preview` needs at runtime (Vite + its
# React plugin so the preview server can boot).
COPY --from=builder /app/dist ./dist
COPY package.json vite.config.ts ./
RUN npm install --omit=dev --no-audit --no-fund vite @vitejs/plugin-react

EXPOSE 5184
CMD ["npx", "vite", "preview", "--host", "0.0.0.0", "--port", "5184", "--strictPort"]
