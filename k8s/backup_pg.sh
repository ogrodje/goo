#!/usr/bin/env bash
set -ex

K8S_FILE="./goo_backup_$(date +%F_%H-%M-%S).sql"

kubectl exec -it po/pg-one-postgresql-0 -- \
  env PGPASSWORD=${POSTGRES_PASSWORD} pg_dump -U ${POSTGRES_USER} ${POSTGRES_DB} > ${K8S_FILE}

echo "Backup saved to ${K8S_FILE}"