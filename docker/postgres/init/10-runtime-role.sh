#!/bin/bash
# Creates the least-privilege role the application connects as, when POSTGRES_APP_USER is set (SECURITY.md, DB-1).
# Runs once, when the database directory is first created. For a database that already exists, run the same two steps by hand:
#   docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE ROLE ... LOGIN PASSWORD '...'"
#   docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v app_user=... -f /ops/runtime-grants.sql
set -euo pipefail

if [ -z "${POSTGRES_APP_USER:-}" ]; then
    echo "POSTGRES_APP_USER is not set: the application will connect as ${POSTGRES_USER} (fine for development)."
    exit 0
fi
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set together with POSTGRES_APP_USER}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v app_user="$POSTGRES_APP_USER" -v app_password="$POSTGRES_APP_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user')
\gexec
\ir /ops/runtime-grants.sql
SQL
