-- Least-privilege grants for the role the running application connects as (SECURITY.md, DB-1).
--
-- The schema is owned by the role Flyway migrates as (POSTGRES_USER). The application itself needs to read and write rows and
-- nothing else: with these grants a compromised application cannot DROP, ALTER or TRUNCATE a table, cannot create objects, and
-- cannot change who owns what. Run it once as the owner, with psql, after creating the role:
--
--   psql -v app_user=interviewer_app -f runtime-grants.sql   (as the owner of the database)
--
-- Safe to run again; tables created by later migrations are covered by the default privileges at the end, because the migrations
-- run as the same owner. The only table it leaves writable that it could protect is flyway_schema_history (Flyway creates it at
-- the first migration, after this script has run on a new database): changing it can only make the next start fail.
GRANT USAGE ON SCHEMA public TO :"app_user";
REVOKE CREATE ON SCHEMA public FROM :"app_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app_user";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
