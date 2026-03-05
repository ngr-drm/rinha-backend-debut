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

ENV JVM_OPTS="-XX:MaxRAMPercentage=60 \
              -XX:InitialRAMPercentage=50 \
              -Xss256k \
              -XX:+UseG1GC \
              -XX:MaxGCPauseMillis=100 \
              -XX:+UnlockExperimentalVMOptions \
              -XX:+UseContainerSupport \
              -XX:+OptimizeStringConcat \
              -XX:+UseStringDeduplication \
              -XX:StringDeduplicationAgeThreshold=1 \
              -XX:MaxMetaspaceSize=64m \
              -XX:CompressedClassSpaceSize=32m \
              -XX:ReservedCodeCacheSize=32m \
              -XX:+TieredCompilation \
              -XX:TieredStopAtLevel=1 \
              -Djava.security.egd=file:/dev/./urandom \
              -Djava.awt.headless=true \
              -Dfile.encoding=UTF-8 \
              -XX:+HeapDumpOnOutOfMemoryError \
              -XX:HeapDumpPath=/app/data/"

ENTRYPOINT ["sh", "-c", "java $JVM_OPTS -jar debut.jar"]
