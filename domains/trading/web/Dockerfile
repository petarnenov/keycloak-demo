FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json ./
RUN npm install --no-audit --no-fund

COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM node:20-alpine
WORKDIR /app

COPY --from=builder /app/dist ./dist
COPY package.json vite.config.ts ./
RUN npm install --omit=dev --no-audit --no-fund vite @vitejs/plugin-react

EXPOSE 5185
CMD ["npx", "vite", "preview", "--host", "0.0.0.0", "--port", "5185", "--strictPort"]
