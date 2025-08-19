# Etapa 1: Build
FROM gradle:8.7-jdk17 AS build
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

RUN gradle dependencies --no-daemon || return 0

COPY src ./src

RUN gradle assemble --parallel -x test --no-daemon

FROM azul/zulu-openjdk-alpine:17-jre
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
RUN mkdir -p /app/data && chown -R app:app /app/data

COPY --from=build --chown=app:app /app/build/libs/*-all.jar debut.jar

USER app

ENV JVM_OPTS="-XX:MaxRAMPercentage=75 -Xss256k -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JVM_OPTS -jar debut.jar"]
