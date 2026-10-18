# The self-hosted web server: the built single-page app behind Caddy, which also proxies /api to the backend
# (docker-compose.prod.yml). For a static host such as Cloudflare Pages, deploy `dist/` instead (README, Deployment).
#   docker build -t tech-interviewer-web frontend/

# ---- build: the app, and the security headers as a Caddy snippet (the same rules as dist/_headers)
FROM node:24-alpine AS build
WORKDIR /build
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# Blank: the app calls the API on its own origin, through Caddy. Set it only when the API is on another origin.
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN pnpm build && node scripts/caddyHeaders.mjs > spa-headers.caddy

# ---- runtime: Caddy with the app
FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /build/spa-headers.caddy /etc/caddy/spa-headers.caddy
COPY --from=build /build/dist /srv
# Not served: the _headers file is for Cloudflare Pages, Caddy has the snippet.
RUN rm -f /srv/_headers
EXPOSE 80 443
