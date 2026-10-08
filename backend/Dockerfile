# Build the jar, then ship only the runtime — no Maven, no sources in the final image.
#
#   docker build -t teilen-backend backend/
#   docker run --rm -p 8081:8081 \
#     -e DB_HOST=host.docker.internal -e DB_NAME=teilen \
#     -e DB_USER=postgres -e DB_PASSWORD=postgres \
#     -v teilen-blobs:/data/blobs \
#     teilen-backend
#
# Configuration is entirely environment variables, so nothing has to be baked in:
#   PORT, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD,
#   TEILEN_STORAGE_DIR, TEILEN_MAX_BLOB_BYTES, TEILEN_DEFAULT_TTL_SECONDS
#   TEILEN_BLOB_LINK_SECRET  ← set this, or signed blob links break on every restart

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# dependencies in their own layer: source edits then rebuild in seconds, not minutes
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# split the fat jar into layers (Spring Boot 3.3+ "tools" jarmode).
# The jar is renamed first so the extracted launcher is always called application.jar.
RUN mv target/backend-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# shared blobs live here; mount a volume over it or they die with the container
RUN addgroup -S teilen && adduser -S -G teilen teilen \
    && mkdir -p /data/blobs \
    && chown -R teilen:teilen /data/blobs

WORKDIR /app
# least-changing first: only application/ changes when the code does
COPY --from=build --chown=teilen:teilen /build/extracted/dependencies/ ./
COPY --from=build --chown=teilen:teilen /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=teilen:teilen /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=teilen:teilen /build/extracted/application/ ./

ENV PORT=8081 \
    TEILEN_STORAGE_DIR=/data/blobs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8081
VOLUME ["/data/blobs"]

USER teilen

# There is no /actuator/health, so ask for something that needs no token. It used to be
# /api/items, which since the auth layer answers 401 and would have marked a perfectly
# healthy container unhealthy; the static shell is served without credentials by design.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/" | grep -q Teilen || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
