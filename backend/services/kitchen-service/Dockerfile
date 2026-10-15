
# syntax=docker/dockerfile:1

# ==========================================
# RESTOFY - BUILD STAGE
# ==========================================

FROM maven:3.9-eclipse-temurin-25 AS build

ARG SERVICE_NAME

WORKDIR /workspace

# Copy Maven multi-module backend
COPY backend/ ./backend/

# Build selected microservice
RUN test -n "$SERVICE_NAME" \
    && mvn -B -f backend/pom.xml \
       -pl "services/${SERVICE_NAME}" \
       -am -DskipTests package \
    && JAR_PATH=$(find "backend/services/${SERVICE_NAME}/target" \
       -maxdepth 1 -type f -name '*.jar' \
       ! -name '*-sources.jar' \
       ! -name '*-javadoc.jar' \
       -print -quit) \
    && test -n "$JAR_PATH" \
    && cp "$JAR_PATH" /workspace/app.jar

# ==========================================
# RESTOFY - RUNTIME STAGE
# ==========================================

FROM eclipse-temurin:25-jre-noble

WORKDIR /app

# Non-root application user
RUN groupadd --system restofy \
    && useradd --system --gid restofy restofy

# Copy executable application
COPY --from=build \
    --chown=restofy:restofy \
    /workspace/app.jar /app/app.jar

USER restofy

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
