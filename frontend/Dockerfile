# =============================================================================
# CodeArena web client
#
# Vite inlines VITE_* variables at build time, so the API base URL is a build
# argument rather than a runtime environment variable. Only non-secret values are
# ever passed here - anything in the bundle is public by definition.
# =============================================================================
FROM node:24-alpine AS build
WORKDIR /build

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./

ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# -----------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
