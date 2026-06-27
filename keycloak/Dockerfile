ARG KEYCLOAK_VERSION=26.2

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
# Discord is not a built-in Keycloak provider. This community SPI adds providerId "discord".
# ponytail: one jar beats hand-rolling a generic-OIDC shim against Discord's non-OIDC quirks.
# VERIFY the asset URL/version against https://github.com/wadahiro/keycloak-discord/releases
# and bump KC_DISCORD_URL to the release matching your Keycloak version.
ARG KC_DISCORD_URL=https://github.com/wadahiro/keycloak-discord/releases/download/v0.7.0/keycloak-discord-0.7.0.jar
ADD ${KC_DISCORD_URL} /opt/keycloak/providers/keycloak-discord.jar

ENV KC_DB=postgres
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
