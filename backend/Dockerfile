FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon clean test bootJar

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system app && \
    useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=builder /workspace/build/libs/app.jar ./app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC"
EXPOSE 8080

USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
