# Build the fat jar on the host first (KMP/Android toolchain stays out of the image):
#   nix develop --command ./gradlew :banalities-server:buildFatJar
# Build context is the repo root (see compose.yaml).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY banalities-server/build/libs/*-all.jar app.jar
COPY db/migration db/migration
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
