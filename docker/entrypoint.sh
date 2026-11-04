#!/bin/sh
# Starts a virtual display, then launches the Minecraft client with MCDT through portablemc.
set -eu

DATA_DIR=/data
log() { echo "[mcdt-docker] $*"; }

# LOADER, MC_VERSION, MC_TARGET (the portablemc version spec), ... of this image's node.
. /opt/mcdt/node.env

# 1. Check the settings, install the image's and the user's mods (/mods, MC_MODS, MC_MODRINTH),
#    and seed options.txt on first start (docker/setup).
java -jar /opt/mcdt/setup.jar

# 2. Virtual display for software OpenGL (Mesa llvmpipe).
display_num="${DISPLAY#:}"
display_num="${display_num%%.*}"
rm -f "/tmp/.X${display_num}-lock" "/tmp/.X11-unix/X${display_num}"
# setsid keeps Xvfb out of the process group that `docker stop` signals, so the display outlives the game.
setsid Xvfb "$DISPLAY" -screen 0 "${MC_WIDTH}x${MC_HEIGHT}x24" -nolisten tcp >/tmp/xvfb.log 2>&1 &
i=0
while [ ! -S "/tmp/.X11-unix/X${display_num}" ]; do
	i=$((i + 1))
	if [ "$i" -gt 50 ]; then
		log "Xvfb did not start:"
		cat /tmp/xvfb.log
		exit 1
	fi
	sleep 0.2
done

# 3. Account.
set -- --mc-dir "$DATA_DIR" --bin-dir /tmp/mcdt-natives --jvm-policy system \
	--resolution "${MC_WIDTH}x${MC_HEIGHT}" \
	"--jvm-arg=-Xmx${MC_MAX_MEMORY}"

case "${MC_AUTH}" in
	offline)
		set -- "$@" -u "${MC_USERNAME:-McdtBot}"
		;;
	microsoft)
		# Rows after the "sep" line are stored accounts: row<TAB>username<TAB>uuid.
		list_accounts() {
			portablemc auth list --output machine | awk -F '\t' 'seen && $1 == "row" { print $2 "\t" $3 } $1 == "sep" { seen = 1 }'
		}
		if [ -z "$(list_accounts)" ]; then
			log "No Microsoft account is stored in $PMC_MSA_DB_FILE yet."
			log "Open the link below and enter the code to sign in. The login is kept in the /data volume."
			portablemc auth login --no-browser
		fi
		if [ -n "${MC_USERNAME:-}" ]; then
			set -- "$@" -a -u "$MC_USERNAME"
		else
			uuid="$(list_accounts | head -n 1 | cut -f 2)"
			if [ -z "$uuid" ]; then
				log "Microsoft login did not store an account."
				exit 1
			fi
			set -- "$@" -a -i "$uuid"
		fi
		;;
	*)
		log "MC_AUTH must be 'offline' or 'microsoft', got '${MC_AUTH}'."
		exit 1
		;;
esac

# 4. Optional Quick Play. By default the client stays on the title screen.
if [ -n "${MC_JOIN_SERVER:-}" ]; then
	set -- "$@" --join-server "$MC_JOIN_SERVER" --join-server-port "${MC_JOIN_SERVER_PORT:-25565}"
elif [ -n "${MC_JOIN_WORLD:-}" ]; then
	set -- "$@" --join-world "$MC_JOIN_WORLD"
fi

# Extra JVM args, space-separated.
for arg in ${MC_JVM_ARGS:-}; do
	set -- "$@" "--jvm-arg=$arg"
done

if [ -z "${MCDT_TOKEN:-}" ]; then
	log "MCDT_TOKEN is not set, so the API uses a random token. Read it with: docker exec <container> cat $DATA_DIR/config/mcdt/token"
fi
log "Starting ${MC_TARGET}. The first start downloads the game into $DATA_DIR/.portablemc. The API will listen on port ${MCDT_PORT}."

# tini -g sends `docker stop`'s SIGTERM to this whole process group. The launcher exits before the game
# JVM has run its shutdown hooks, so stay alive until the JVM is gone too.
trap 'log "Stopping..."' TERM INT
portablemc start "$@" "$MC_TARGET" &
launcher=$!
status=0
while kill -0 "$launcher" 2>/dev/null; do
	wait "$launcher" || status=$?
done
game_running() { grep -qsx java /proc/[0-9]*/comm; }
while game_running; do
	sleep 0.2
done
exit "$status"
