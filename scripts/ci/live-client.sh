#!/usr/bin/env bash
# Live client test for CI (GitHub Actions, ubuntu). Not meant for local use: it launches a real game.
#
# 1. Generates a superflat world with the matching dedicated server.
# 2. Launches the client headless (Xvfb + Mesa) through portablemc with the given mods.
# 3. Runs the Python live suite on the title screen, loads the world through MCDT,
#    runs the world and Baritone suites, then quits through the two-step quit flow.
#
# Required environment:
#   MC_VERSION      Minecraft version, e.g. 1.21.4 (used for the dedicated server)
#   MC_TARGET       portablemc version spec, e.g. fabric:1.21.4:0.19.5
#   JAVA_BIN        java executable for the client and the server
#   BARITONE        installed | absent (what the suite must find)
# Arguments: mod jars to install.
set -euo pipefail

: "${MC_VERSION:?}" "${MC_TARGET:?}" "${JAVA_BIN:?}" "${BARITONE:?}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/mcdt-live"
INST="$WORK/instance"
PMC="$WORK/portablemc"
PORT="${MCDT_PORT:-25580}"
BASE="http://127.0.0.1:$PORT"
WORLD="mcdt-flat"
RESULTS="$WORK/results"

log() { echo "[live] $*"; }
mkdir -p "$INST/mods" "$INST/saves" "$RESULTS"
echo "INST=$INST" >> "${GITHUB_ENV:-/dev/null}"
echo "LIVE_RESULTS=$RESULTS" >> "${GITHUB_ENV:-/dev/null}"

for jar in "$@"; do
	cp "$jar" "$INST/mods/"
done
log "mods: $(ls "$INST/mods" | tr '\n' ' ')"

cat > "$INST/options.txt" <<'EOF'
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
narrator:0
realmsNotifications:false
renderDistance:4
simulationDistance:5
maxFps:30
enableVsync:false
graphicsMode:0
renderClouds:"false"
particles:2
soundCategory_master:0.0
EOF

# ---- 1. Superflat world from the dedicated server ---------------------------------------------------------------
log "generating $WORLD with the $MC_VERSION server"
SRV="$WORK/server"
mkdir -p "$SRV"
version_url="$(curl -fsSL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
	| jq -r --arg v "$MC_VERSION" '.versions[] | select(.id == $v) | .url')"
server_url="$(curl -fsSL "$version_url" | jq -r '.downloads.server.url')"
curl -fsSL -o "$SRV/server.jar" "$server_url"
echo "eula=true" > "$SRV/eula.txt"
cat > "$SRV/server.properties" <<EOF
level-name=$WORLD
level-type=minecraft\:flat
generate-structures=false
difficulty=peaceful
spawn-monsters=false
online-mode=false
server-port=25599
spawn-protection=0
EOF
# Console commands run once the server has finished loading, so "stop" saves the new world and exits.
(cd "$SRV" && printf 'stop\n' | timeout 600 "$JAVA_BIN" -Xmx1G -jar server.jar nogui > "$RESULTS/server.log" 2>&1)
test -f "$SRV/$WORLD/level.dat"
cp -r "$SRV/$WORLD" "$INST/saves/$WORLD"

# ---- 2. Launch the client ---------------------------------------------------------------------------------------
log "installing $MC_TARGET"
portablemc --main-dir "$PMC" start --mc-dir "$INST" --dry --jvm "$JAVA_BIN" "$MC_TARGET"

log "launching $MC_TARGET"
export LIBGL_ALWAYS_SOFTWARE=1 MCDT_PERMISSION=reflect-write MCDT_ALLOW_QUIT=true MCDT_PORT="$PORT"
xvfb-run -a -s "-screen 0 1280x720x24" \
	portablemc --main-dir "$PMC" start --mc-dir "$INST" --jvm "$JAVA_BIN" --jvm-arg=-Xmx2G -u McdtBot "$MC_TARGET" \
	> "$RESULTS/launch.log" 2>&1 &
LAUNCHER=$!

game_alive() { kill -0 "$LAUNCHER" 2>/dev/null; }
collect_logs() {
	cp -r "$INST/logs" "$RESULTS/game-logs" 2>/dev/null || true
	cp -r "$INST/crash-reports" "$RESULTS/crash-reports" 2>/dev/null || true
}
trap collect_logs EXIT
fail() {
	log "FAILED: $*"
	tail -n 60 "$RESULTS/launch.log" || true
	kill "$LAUNCHER" 2>/dev/null || true
	exit 1
}

TOKEN=""
api() { curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE$1"; }

log "waiting for the MCDT API and the title screen"
for _ in $(seq 1 600); do
	game_alive || fail "the client exited during startup"
	if [ -z "$TOKEN" ] && [ -s "$INST/config/mcdt/token" ]; then
		TOKEN="$(tr -d '\r\n' < "$INST/config/mcdt/token")"
	fi
	if [ -n "$TOKEN" ] && api /v1/objects/screen 2>/dev/null | jq -e '."$class" | test("TitleScreen")' >/dev/null 2>&1; then
		break
	fi
	sleep 2
done
[ -n "$TOKEN" ] || fail "no token after startup"
api /v1/objects/screen | jq -e '."$class" | test("TitleScreen")' >/dev/null || fail "the title screen never appeared"
log "client ready at $BASE"

suite() {
	(cd "$REPO" && uv run --project tests/live pytest tests/live "$@" \
		--mcdt-url "$BASE" --mcdt-token "$TOKEN" --baritone "$BARITONE")
}

status=0
# ---- 3. Title screen suite ---------------------------------------------------------------------------------------
log "title screen suite"
suite --state menu --junitxml "$RESULTS/menu.xml" || status=1

# ---- 4. World + Baritone suites ----------------------------------------------------------------------------------
log "loading $WORLD"
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" "$BASE/v1/worlds/$WORLD/load" || fail "world load request failed"
for _ in $(seq 1 150); do
	game_alive || fail "the client exited while loading the world"
	api /v1/player >/dev/null 2>&1 && break
	sleep 2
done
api /v1/player >/dev/null || fail "the player never appeared"
sleep 5  # let chunks around the player finish loading before pathing
log "world suite"
suite tests/live/test_world.py tests/live/test_baritone.py --state world --junitxml "$RESULTS/world.xml" || status=1

# ---- 5. Quit through MCDT ----------------------------------------------------------------------------------------
log "quit flow"
suite tests/live/test_client.py -k test_quit --shutdown --state world --junitxml "$RESULTS/quit.xml" || status=1
for _ in $(seq 1 60); do
	game_alive || break
	sleep 2
done
if game_alive; then
	log "the client did not exit after the quit flow"
	kill "$LAUNCHER" 2>/dev/null || true
	status=1
fi
exit "$status"
