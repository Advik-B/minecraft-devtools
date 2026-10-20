# syntax=docker/dockerfile:1.7
#
# Runs a headless Minecraft 1.21.4 client (Fabric + Fabric API + MCDT) on a virtual X display with
# software OpenGL, so the MCDT API can be deployed as a service. See the Docker section of README.md.

ARG MINECRAFT_VERSION=1.21.4
ARG LOADER_VERSION=0.19.5

# ---- Build the mod jar --------------------------------------------------------------------------
# Loom needs JDK 25 to run; the mod itself still compiles with --release 21.
FROM oven/bun:1.3.14 AS bun

FROM eclipse-temurin:25-jdk-noble AS build
COPY --from=bun /usr/local/bin/bun /usr/local/bin/bun
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
	chmod +x gradlew \
	&& ./gradlew build -x test --no-daemon --console=plain \
	&& mkdir -p /out \
	&& find build/libs -maxdepth 1 -name 'mcdt-*.jar' ! -name '*-sources.jar' -exec cp {} /out/ \;

# ---- Runtime ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

ARG MINECRAFT_VERSION
ARG LOADER_VERSION
ARG FABRIC_API_VERSION=0.119.4+1.21.4
ARG FABRIC_API_URL=https://cdn.modrinth.com/data/P7dR8mSH/versions/p96k10UR/fabric-api-0.119.4%2B1.21.4.jar
ARG FABRIC_API_SHA512=16b679f6a42549ca3b6bc147de710b2c8ba2d5e4661ed88df22782c05a4a5f6c1163d7b13867687ed91acde0fdfe5a5037d05662431c946865b58396284e7ee7
ARG PORTABLEMC_VERSION=5.0.6

RUN apt-get update \
	&& apt-get install -y --no-install-recommends \
		ca-certificates curl tini python3 python3-venv \
		xvfb xauth libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 \
		libxrender1 libxtst6 libxi6 libxrandr2 libxcursor1 libxxf86vm1 libopenal1 \
	&& rm -rf /var/lib/apt/lists/* \
	&& mkdir -m 1777 -p /tmp/.X11-unix

# portablemc installs and launches Fabric, and handles offline and Microsoft accounts.
RUN printf 'portablemc==%s --hash=sha256:%s --hash=sha256:%s\n' "$PORTABLEMC_VERSION" \
		ba21415d880c0d79216998eeafdc5ca8bef1c4c9ccf8cdf0a802e14bcf1ce9a2 \
		b4cd104ff888f07c6e4ed18ae54261e605b77032952e52c9a37184b1d8776335 > /tmp/requirements.txt \
	&& python3 -m venv /opt/portablemc \
	&& /opt/portablemc/bin/pip install --no-cache-dir --only-binary=:all: --require-hashes -r /tmp/requirements.txt \
	&& ln -s /opt/portablemc/bin/portablemc /usr/local/bin/portablemc \
	&& rm /tmp/requirements.txt

RUN useradd --create-home --uid 10001 mcdt \
	&& mkdir -p /opt/minecraft /opt/mcdt/mods /data \
	&& chown -R mcdt:mcdt /opt/minecraft /data

USER mcdt

# Pre-install the game, Fabric loader, libraries and assets so a container starts without downloading them.
RUN portablemc --main-dir /opt/minecraft start --dry --jvm-policy system \
	"fabric:${MINECRAFT_VERSION}:${LOADER_VERSION}"

USER root
RUN curl -fsSL -o "/opt/mcdt/mods/fabric-api-${FABRIC_API_VERSION}.jar" "$FABRIC_API_URL" \
	&& echo "${FABRIC_API_SHA512}  /opt/mcdt/mods/fabric-api-${FABRIC_API_VERSION}.jar" | sha512sum -c -
COPY --from=build /out/ /opt/mcdt/mods/
COPY --chmod=0755 docker/entrypoint.sh /opt/mcdt/entrypoint.sh
USER mcdt

ENV MC_VERSION="fabric:${MINECRAFT_VERSION}:${LOADER_VERSION}" \
	MC_AUTH=offline \
	MC_MAX_MEMORY=2G \
	MC_WIDTH=1280 \
	MC_HEIGHT=720 \
	MCDT_BIND_ADDRESS=0.0.0.0 \
	MCDT_PORT=25580 \
	DISPLAY=:99 \
	LIBGL_ALWAYS_SOFTWARE=1 \
	PMC_MAIN_DIR=/opt/minecraft \
	PMC_MSA_DB_FILE=/data/portablemc_msa.json

WORKDIR /data
VOLUME /data
EXPOSE 25580

HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
	CMD curl -fsS -o /dev/null "http://127.0.0.1:${MCDT_PORT}/openapi.json" || exit 1

# -g: signal the whole process group, so `docker stop` reaches the game JVM behind the launcher.
ENTRYPOINT ["tini", "-g", "--", "/opt/mcdt/entrypoint.sh"]
