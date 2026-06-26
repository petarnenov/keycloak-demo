# Build-only image. The output (theme/ tree with built JS bundle in
# theme/login/resources/js/auth-spa.js) is COPIED out by Dockerfile.keycloak's
# multi-stage build. There is no runtime image.
FROM node:20-alpine AS spa-builder
WORKDIR /build
COPY auth-spa/package.json auth-spa/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY auth-spa/ ./
RUN npm run build

# Sanity stage — the resulting tree includes our hand-written FTL/CSS plus the
# Vite-built bundle. Downstream uses it as a directory copy source.
FROM scratch AS theme
COPY --from=spa-builder /build/theme/ /theme/
