# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src/main ./src/main
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S reagent \
    && adduser -S -D -H -h /app -G reagent reagent \
    && mkdir -p /app /var/reagent/workspaces \
    && chown -R reagent:reagent /app /var/reagent
WORKDIR /app
COPY --from=build --chown=reagent:reagent \
    /workspace/target/reagent-0.1.0-SNAPSHOT.jar /app/reagent.jar

USER reagent
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=24 \
    CMD wget -q -O /dev/null \
    http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/reagent.jar"]
