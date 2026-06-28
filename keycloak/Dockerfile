ARG KEYCLOAK_VERSION=26.2
ARG KC_DISCORD_VERSION=0.6.1

FROM alpine AS downloader
ARG KC_DISCORD_VERSION
RUN wget -O /keycloak-discord.jar \
    "https://github.com/wadahiro/keycloak-discord/releases/download/v${KC_DISCORD_VERSION}/keycloak-discord-${KC_DISCORD_VERSION}.jar"

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
COPY --from=downloader /keycloak-discord.jar /opt/keycloak/providers/keycloak-discord.jar

ENV KC_DB=postgres
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
