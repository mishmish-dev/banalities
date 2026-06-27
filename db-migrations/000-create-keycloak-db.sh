#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --no-password --dbname "$POSTGRES_DB" \
  -c "CREATE DATABASE keycloak OWNER \"$POSTGRES_USER\""
