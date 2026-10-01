# syntax=docker/dockerfile:1

# ---- Stage 1: build -----------------------------------------------------------------------------
# Full JDK + Gradle wrapper. Ubuntu-based (not Alpine) because the protoc binary that the protobuf
# Gradle plugin downloads is linked against glibc.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src

# Copy only the build definition first so the dependency download is cached as its own layer and
# is not repeated when only source code changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon --quiet bootJar -x test \
 && java -Djarmode=tools -jar build/libs/leaderboard-service.jar extract --layers --launcher --destination /extracted

# ---- Stage 2: runtime ---------------------------------------------------------------------------
# JRE only: no compiler, no Gradle, no sources.
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app
USER app

# Spring Boot layers, least to most frequently changing, so a code change only rebuilds the last one.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# 9090 = gRPC API, 8080 = actuator (health probes + Prometheus metrics)
EXPOSE 9090 8080

# Size the heap from the container's memory limit instead of the host's RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
