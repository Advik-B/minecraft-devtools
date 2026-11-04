# syntax=docker/dockerfile:1.7
#
# Runs a headless Minecraft client with MCDT (and Fabric API on Fabric) on a virtual X display with software
# OpenGL, so the MCDT API can be deployed as a service. One image per Stonecutter node:
#   docker build --build-arg NODE=1.21.1-neoforge -t mcdt-1.21.1-neoforge .
# The image has no Minecraft files: the game, the loader and the assets download into /data on first start.
# See the Docker section of README.md.

ARG NODE=1.21.4-fabric
# Must match java_version in versions/<NODE>/gradle.properties (CI passes it from scripts/ci/node-env.sh).
ARG JAVA_VERSION=21
# Empty: the version in gradle.properties. Release builds pass the tag.
ARG MCDT_VERSION=
ARG PORTABLEMC_VERSION=5.0.6
ARG PORTABLEMC_SHA256=9b55d0d75b4e5a30d697109869a301009c5927883d34746ce212605e8a61ecad

FROM oven/bun:1.3.14 AS bun
# The node's JDK, for builds that ask Gradle for a toolchain (ModDevGradle does).
FROM eclipse-temurin:${JAVA_VERSION}-jdk-noble AS node-jdk

# ---- Node facts (loader, Minecraft version, launcher target, pinned jars) ----------------------------------------
FROM eclipse-temurin:25-jdk-noble AS meta
ARG NODE
WORKDIR /src
COPY gradle.properties ./
COPY versions/ versions/
COPY scripts/ci/node-env.sh scripts/ci/
RUN sh scripts/ci/node-env.sh "$NODE" /src > /node.env && cat /node.env

# ---- Build the mod jar and the setup tool ------------------------------------------------------------------------
# Gradle needs JDK 25 to run; the mod itself compiles with its node's --release.
FROM eclipse-temurin:25-jdk-noble AS build
ARG NODE
ARG MCDT_VERSION
COPY --from=bun /usr/local/bin/bun /usr/local/bin/bun
COPY --from=node-jdk /opt/java/openjdk /opt/node-jdk
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
	chmod +x gradlew \
	&& ./gradlew ":${NODE}:build" :docker-setup:shadowJar -x test --no-daemon --console=plain \
		-Porg.gradle.java.installations.paths=/opt/node-jdk \
		${MCDT_VERSION:+-Pversion=$MCDT_VERSION} \
	&& mkdir -p /out/mods /out/baritone \
	&& cp build/libs/mcdt-*+"${NODE}".jar /out/mods/ \
	&& cp docker/setup/build/libs/mcdt-docker-setup.jar /out/setup.jar
COPY --from=meta /node.env /node.env
RUN . /node.env && cp "libs/${BARITONE_JAR}" /out/baritone/

# ---- Runtime ------------------------------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-noble

ARG NODE
ARG MCDT_VERSION
ARG PORTABLEMC_VERSION
ARG PORTABLEMC_SHA256

LABEL org.opencontainers.image.title="mcdt-${NODE}" \
	org.opencontainers.image.description="Headless Minecraft client with MCDT (Minecraft DevTools), ${NODE}" \
	org.opencontainers.image.source="https://github.com/Advik-B/minecraft-devtools" \
	org.opencontainers.image.licenses="CC0-1.0" \
	org.opencontainers.image.version="${MCDT_VERSION}"

RUN apt-get update \
	&& apt-get install -y --no-install-recommends \
		ca-certificates curl tini \
		xvfb xauth libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 \
		libxrender1 libxtst6 libxi6 libxrandr2 libxcursor1 libxxf86vm1 libopenal1 \
	&& rm -rf /var/lib/apt/lists/* \
	&& mkdir -m 1777 -p /tmp/.X11-unix

# portablemc (native build) installs and launches the game and the loader, and handles offline and Microsoft accounts.
RUN curl -fsSL -o /tmp/portablemc.tar.gz \
		"https://github.com/mindstorm38/portablemc/releases/download/v${PORTABLEMC_VERSION}/portablemc-${PORTABLEMC_VERSION}-linux-x86_64-gnu.tar.gz" \
	&& echo "${PORTABLEMC_SHA256}  /tmp/portablemc.tar.gz" | sha256sum -c - \
	&& tar -xzf /tmp/portablemc.tar.gz -C /tmp \
	&& install -m 0755 "/tmp/portablemc-${PORTABLEMC_VERSION}-linux-x86_64-gnu/portablemc" /usr/local/bin/portablemc \
	&& rm -rf /tmp/portablemc*

RUN useradd --create-home --uid 10001 mcdt \
	&& mkdir -p /opt/mcdt/mods /opt/mcdt/baritone /data \
	&& chown -R mcdt:mcdt /data

COPY --from=meta /node.env /opt/mcdt/node.env
# Fabric API (Fabric nodes only), pinned by SHA-512 in versions/<NODE>/gradle.properties.
RUN . /opt/mcdt/node.env \
	&& if [ -n "$FABRIC_API_URL" ]; then \
		curl -fsSL -o "/opt/mcdt/mods/fabric-api-${MC_VERSION}.jar" "$FABRIC_API_URL" \
		&& echo "${FABRIC_API_SHA512}  /opt/mcdt/mods/fabric-api-${MC_VERSION}.jar" | sha512sum -c -; \
	fi
COPY --from=build /out/mods/ /opt/mcdt/mods/
# Baritone (LGPL-3.0, unmodified): the jar pinned in libs/, verified by Gradle against baritone_sha256.
COPY --from=build /out/baritone/ /opt/mcdt/baritone/
COPY --from=build /out/setup.jar /opt/mcdt/setup.jar
COPY --chmod=0755 docker/entrypoint.sh /opt/mcdt/entrypoint.sh
USER mcdt

ENV MC_AUTH=offline \
	MC_BARITONE=true \
	MC_MAX_MEMORY=2G \
	MC_WIDTH=1280 \
	MC_HEIGHT=720 \
	MCDT_BIND_ADDRESS=0.0.0.0 \
	MCDT_PORT=25580 \
	DISPLAY=:99 \
	LIBGL_ALWAYS_SOFTWARE=1 \
	PMC_MAIN_DIR=/data/.portablemc \
	PMC_MSA_DB_FILE=/data/portablemc_msa.json

WORKDIR /data
VOLUME /data
EXPOSE 25580

# The first start downloads the game, hence the long start period.
HEALTHCHECK --interval=30s --timeout=5s --start-period=600s --retries=3 \
	CMD curl -fsS -o /dev/null "http://127.0.0.1:${MCDT_PORT}/openapi.json" || exit 1

# -g: signal the whole process group, so `docker stop` reaches the game JVM behind the launcher.
ENTRYPOINT ["tini", "-g", "--", "/opt/mcdt/entrypoint.sh"]
