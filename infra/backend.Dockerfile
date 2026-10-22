# Build context is the repository root:
#   docker build -f infra/backend.Dockerfile .
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy the build descriptors first so dependency download is cached separately
# from source changes.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
RUN chmod +x mvnw && ./mvnw -B -ntp -pl backend -am dependency:go-offline

COPY backend/src backend/src
RUN ./mvnw -B -ntp -pl backend -am -DskipTests package \
    && cp backend/target/rideflow-backend-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run the application as root.
RUN addgroup -S rideflow && adduser -S rideflow -G rideflow
USER rideflow

COPY --from=build --chown=rideflow:rideflow /workspace/app.jar app.jar

# 8090 is actuator (metrics, health): for the internal network only, never published.
EXPOSE 8080 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
