# Multi-stage Dockerfile for ForgeAI Spring Boot API (Java 21 LTS)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache maven

COPY pom.xml ./
COPY packages/shared/pom.xml packages/shared/pom.xml
COPY services/api/pom.xml services/api/pom.xml
COPY workers/jobs/pom.xml workers/jobs/pom.xml

COPY packages/shared/src packages/shared/src
COPY services/api/src services/api/src

RUN mvn -B -pl services/api -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S forgeai && adduser -S forgeai -G forgeai
USER forgeai

COPY --from=build /workspace/services/api/target/*.jar /app/app.jar

EXPOSE 4000
HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=3 \
  CMD wget -qO- http://localhost:${API_PORT:-4000}/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
