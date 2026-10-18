# Multi-stage Dockerfile for ForgeAI Durable Background Worker (Java 21 LTS)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache maven

COPY pom.xml ./
COPY packages/shared/pom.xml packages/shared/pom.xml
COPY services/api/pom.xml services/api/pom.xml
COPY workers/jobs/pom.xml workers/jobs/pom.xml

COPY packages/shared/src packages/shared/src
COPY workers/jobs/src workers/jobs/src

RUN mvn -B -pl workers/jobs -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S forgeai && adduser -S forgeai -G forgeai
USER forgeai

COPY --from=build /workspace/workers/jobs/target/*.jar /app/worker.jar

EXPOSE 4001
HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=3 \
  CMD wget -qO- http://localhost:${WORKER_PORT:-4001}/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/worker.jar"]
