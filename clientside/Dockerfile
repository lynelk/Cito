# Production frontend image for Railway.
# Vite builds the Cito SPA into /app/build; nginx serves it and proxies
# same-origin API requests to the canonical Cito backend over Railway private networking.
FROM node:20.19.0-alpine AS build
WORKDIR /app

ARG RAILWAY_GIT_COMMIT_SHA=
ARG CITO_RELEASE_SHA=unknown

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run typecheck \
    && npm run build \
    && printf '{"release_sha":"%s"}\n' \
      "${RAILWAY_GIT_COMMIT_SHA:-${CITO_RELEASE_SHA}}" > /app/build/releasez

FROM nginx:1.27-alpine

COPY --from=build /app/build /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY start.sh /start.sh
RUN chmod +x /start.sh

ENV PORT=8080
ENV BACKEND_UPSTREAM=cito-backend.railway.internal:8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider "http://127.0.0.1:${PORT}/healthz" || exit 1

CMD ["/start.sh"]
