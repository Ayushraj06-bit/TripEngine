#!/usr/bin/env bash
# The web app's journeys in a real browser, against the running one-command demo (ADR 0027).
# Needs the demo up and the app built (npm run build). The simulator is paused while they run, so
# each journey's own driver takes its rider's trip, and started again afterwards.
#
#   Frontend/e2e/run.sh                   # every journey; screens, logs and results in e2e/out
#   Frontend/e2e/run.sh trip wallet       # some of them
#
# E2E_OFFLINE_MAPS=1 answers OpenStreetMap's place search from common.py and loads no tiles.
set -uo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: hand the container paths to Docker as they are

here="$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })"
out="$here/out"
image="rideflow-e2e"
web="rideflow-e2e-web"
journeys=("$@")
[ ${#journeys[@]} -eq 0 ] && journeys=(booking trip live_map wallet)

# What the journeys need, said plainly rather than failing later: reading the secrets volume of a
# demo that is not there would quietly create an empty one.
if [ "$(docker inspect -f '{{.State.Running}}' rideflow-lb 2>/dev/null)" != true ]; then
  echo "The one-command demo is not running. Start it first (docs/development.md)." >&2
  exit 1
fi
if [ ! -f "$here/../dist/index.html" ]; then
  echo "The app is not built. Run npm run build in Frontend/ first." >&2
  exit 1
fi

# The admin's sign-in, from the demo's own secrets volume: never printed.
set -a
eval "$(docker run --rm -v rideflow_secrets:/s:ro alpine:3.22 cat /s/demo.env)"
set +a

# Built from the Dockerfile alone, no context: cached after the first time.
docker build -q -t "$image" - <"$here/Dockerfile" >/dev/null || exit 1
rm -rf "$out" && mkdir -p "$out"
docker run -d --rm --name "$web" --network rideflow_default \
  -v "$here/../dist:/usr/share/nginx/html:ro" -v "$here/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.31-alpine >/dev/null || exit 1

restore() {
  docker stop "$web" >/dev/null 2>&1
  docker start rideflow-simulator-1 >/dev/null
}
trap restore EXIT

# A driver counts as gone once the backend has not heard from it for 30 s: the simulator's,
# before the first journey, and each journey's own, before the next.
docker stop rideflow-simulator-1 >/dev/null
failed=()
for journey in "${journeys[@]}"; do
  sleep 35
  echo "== $journey"
  docker run --rm --network "container:$web" \
    -e ADMIN_PHONE -e ADMIN_PASSWORD -e E2E_OFFLINE_MAPS -e PYTHONDONTWRITEBYTECODE=1 \
    -v "$here:/e2e:ro" -v "$out:/out" -w /e2e "$image" python "$journey.py" 2>&1 | tee "$out/$journey.log"
  [ "${PIPESTATUS[0]}" -eq 0 ] || failed+=("$journey")
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "Failed: ${failed[*]}"
  exit 1
fi
echo "Every journey passed."
