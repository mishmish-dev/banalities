#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Re-exec inside nix develop if called directly outside the dev shell
if [ -z "${IN_NIX_SHELL:-}" ]; then
  exec nix develop "$REPO_ROOT" --command bash "$0" "$@"
fi

case "${1:-help}" in
  run)
    docker compose -f "$REPO_ROOT/compose.yaml" up
    ;;
  migrate)
    docker compose -f "$REPO_ROOT/compose.yaml" --profile migrate run --rm migrate
    ;;
  test)
    bash "$REPO_ROOT/tests/e2e/test.sh"
    ;;
  dump-schema)
    PGPASSWORD="${POSTGRES_PASSWORD:-banalities}" pg_dump \
      -h "${POSTGRES_HOST:-localhost}" \
      -p "${POSTGRES_PORT:-5432}" \
      -U "${POSTGRES_USER:-banalities}" \
      "${POSTGRES_DB:-banalities}" \
      --schema-only --no-owner --no-privileges \
      -f "$REPO_ROOT/db/schema.sql"
    echo "schema → db/schema.sql"
    ;;
  new-migration)
    name="${2:?usage: dev new-migration <name>}"
    next=$(( $(ls "$REPO_ROOT/db/migration/V"*.sql 2>/dev/null | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | tail -1) + 1 ))
    file="$REPO_ROOT/db/migration/V${next}__${name}.sql"
    touch "$file"
    echo "created $file"
    ;;
  clear-db)
    docker compose -f "$REPO_ROOT/compose.yaml" down -v --remove-orphans
    echo "pgdata volume removed"
    ;;
  *)
    echo "usage: dev <run | migrate | test | dump-schema | new-migration <name> | clear-db>"
    ;;
esac
