#!/bin/sh
# Generates RideFlow's secrets (docs/deploy.md), one file per variable, as a Spring config tree.
#
#   sudo infra/deploy/generate-secrets.sh [/etc/rideflow/secrets]
#
# Safe to run again: a secret that exists is never replaced. Replacing the signing key would log
# everyone out, and the database password is already set in PostgreSQL.
#
# Readable by root and by group 10001 only: the applications run as uid and gid 10001
# (infra/Dockerfile), and Grafana is given that group in compose.yml. Readable by nobody else.
set -eu

dir=${1:-/etc/rideflow/secrets}
group=10001
umask 077

install -d -m 750 -g "$group" "$dir"
cd "$dir"

if [ ! -s RIDEFLOW_RSA_PRIVATE_KEY ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out key.pem 2>/dev/null
  grep -v -- ----- key.pem | tr -d '\n' > RIDEFLOW_RSA_PRIVATE_KEY
  openssl pkey -in key.pem -pubout | grep -v -- ----- | tr -d '\n' > RIDEFLOW_RSA_PUBLIC_KEY
  rm key.pem
fi

random() {
  [ -s "$1" ] || openssl rand -hex "$2" | tr -d '\n' > "$1"
}
random RIDEFLOW_GATEWAY_WEBHOOK_SECRET 32
random RIDEFLOW_DB_PASSWORD 24
# The first admin's, created on the first start with RIDEFLOW_ADMIN_PHONE.
random RIDEFLOW_ADMIN_PASSWORD 16
random GRAFANA_ADMIN_PASSWORD 16

chown root:"$group" ./*
chmod 440 ./*
echo "Secrets in $dir: $(ls | tr '\n' ' ')"
