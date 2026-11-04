#!/bin/sh
# Prints the runtime facts of one Stonecutter node as KEY=VALUE lines, read from gradle.properties and
# versions/<node>/gradle.properties. Used by the live and docker workflows and by the Dockerfile.
# Usage: node-env.sh <node> [repo root]   e.g. node-env.sh 1.21.4-fabric
set -eu

NODE="${1:?usage: node-env.sh <node> [repo root]}"
ROOT="${2:-$(cd "$(dirname "$0")/../.." && pwd)}"
NODE_PROPS="$ROOT/versions/$NODE/gradle.properties"
[ -f "$NODE_PROPS" ] || { echo "node-env.sh: no such node: $NODE" >&2; exit 1; }

prop() { sed -n "s/^$1=//p" "$2" | tr -d '\r' | head -n 1; }

LOADER="${NODE#*-}"
MC_VERSION="$(prop minecraft_version "$NODE_PROPS")"
BARITONE_VERSION="$(prop baritone_version "$NODE_PROPS")"
case "$LOADER" in
	fabric) MC_TARGET="fabric:$MC_VERSION:$(prop loader_version "$ROOT/gradle.properties")" ;;
	neoforge) MC_TARGET="neoforge::$(prop neoforge_version "$NODE_PROPS")" ;;
	*) echo "node-env.sh: unknown loader: $LOADER" >&2; exit 1 ;;
esac

cat <<EOF
NODE=$NODE
LOADER=$LOADER
MC_VERSION=$MC_VERSION
JAVA_VERSION=$(prop java_version "$NODE_PROPS")
MC_TARGET=$MC_TARGET
BARITONE_VERSION=$BARITONE_VERSION
BARITONE_JAR=baritone-api-$LOADER-$BARITONE_VERSION.jar
YACL_VERSION=$(prop yacl_version "$NODE_PROPS")
FABRIC_API_URL=$(prop fabric_api_url "$NODE_PROPS")
FABRIC_API_SHA512=$(prop fabric_api_sha512 "$NODE_PROPS")
EOF
