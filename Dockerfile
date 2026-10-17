# The build context is this repository's root.
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace
COPY . .

FROM builder AS agent-builder
RUN chmod +x gradlew \
    && ./gradlew standaloneJar \
        --no-daemon

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        libdrm2 \
        ocl-icd-libopencl1 \
        tini \
        util-linux \
    && rm -rf /var/lib/apt/lists/*

# Intel's current XPU-SMI packages are published for Ubuntu 24.04+. Keep that
# newer user-space stack in a separate image so CPU/NVIDIA/AMD images stay small
# and on their existing base. Kernel drivers remain installed on the host.
FROM eclipse-temurin:21-jre-noble AS runtime-intel
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        libdrm2 \
        ocl-icd-libopencl1 \
        software-properties-common \
        tini \
        util-linux \
    && add-apt-repository -y ppa:kobuk-team/intel-graphics \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xpu-smi \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /data
VOLUME ["/data"]
EXPOSE 8084 8092/udp 8093/udp
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8084/api/agent/external/identity > /dev/null || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/opt/solarminer/solarminer-pc-agent.jar"]

WORKDIR /data

# The agent, its explicitly installed miners and their logs live below /data.
# This is deliberately a volume so container replacement never loses configs.
VOLUME ["/data"]

EXPOSE 8084 8092/udp 8093/udp

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8084/api/agent/external/identity > /dev/null || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/opt/solarminer/solarminer-pc-agent.jar"]

FROM runtime AS final
ARG PC_AGENT_RELEASE_CHANNEL=stable
ARG PC_AGENT_RELEASE_TAG=unknown
ENV SOLARMINER_AGENT_DEPLOYMENT=docker \
    SOLARMINER_AGENT_CHANNEL=${PC_AGENT_RELEASE_CHANNEL} \
    SOLARMINER_AGENT_RELEASE_TAG=${PC_AGENT_RELEASE_TAG}
COPY --from=agent-builder /workspace/build/distributions/solarminer-pc-agent-standalone.jar /opt/solarminer/solarminer-pc-agent.jar
EXPOSE 8090 3334 3335
CMD ["--solarminer.agent.standalone=false"]

FROM runtime-intel AS final-intel
ARG PC_AGENT_RELEASE_CHANNEL=stable
ARG PC_AGENT_RELEASE_TAG=unknown
ENV SOLARMINER_AGENT_DEPLOYMENT=docker \
    SOLARMINER_AGENT_CHANNEL=${PC_AGENT_RELEASE_CHANNEL} \
    SOLARMINER_AGENT_RELEASE_TAG=${PC_AGENT_RELEASE_TAG}
COPY --from=agent-builder /workspace/build/distributions/solarminer-pc-agent-standalone.jar /opt/solarminer/solarminer-pc-agent.jar
EXPOSE 8090 3334 3335
CMD ["--solarminer.agent.standalone=false"]
