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
# The unprivileged nginx image: the server runs as uid 101 and never as root.
#
# The stock nginx image starts its master process as root in order to bind port 80,
# then drops privileges for the workers. That is the conventional arrangement and it is
# more privilege than this container has any use for -- it serves static files, mounts
# nothing, holds no secret and talks to no other service. Binding 8080 instead costs a
# port number in the compose mapping and removes root from the container entirely.
#
# The config goes to templates/, which is where this image's entrypoint looks.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 8080
