-- Runs once, only on a fresh Postgres volume. Gives Keycloak its own database
-- in the same Postgres instance so we don't run a second container.
CREATE DATABASE keycloak;
