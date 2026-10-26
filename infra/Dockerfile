# One image recipe for both applications. Build context is the repository root:
#   docker build -f infra/Dockerfile -t rideflow-backend .
#   docker build -f infra/Dockerfile --build-arg MODULE=simulator --build-arg PORT=8081 \
#     -t rideflow-simulator .
# The image runs as a non-root user on a Java runtime cut down by jlink to the modules the
# application uses, with the jar split into layers so a code change rebuilds only the last one.

ARG MODULE=backend

FROM eclipse-temurin:21-jdk-alpine AS build
ARG MODULE
WORKDIR /workspace

# Build descriptors first, so the dependency download is a layer of its own and survives source
# changes. Both module poms: the reactor refuses to start with one missing.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
COPY simulator/pom.xml simulator/pom.xml
RUN chmod +x mvnw && ./mvnw -B -ntp -q -pl ${MODULE} -am dependency:go-offline

# Tests and formatting have run in CI before any image is built; here they would only be slower.
COPY ${MODULE}/src/main ${MODULE}/src/main
RUN ./mvnw -B -ntp -q -pl ${MODULE} -am package -Dmaven.test.skip=true -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar ${MODULE}/target/rideflow-${MODULE}-*.jar \
      extract --layers --launcher --destination extracted

# The Java modules the application uses: jdeps finds those its classes name; the rest are loaded
# by name at run time - elliptic curves for TLS, the zip file system, DNS, extra charsets.
RUN jlink --add-modules "$(jdeps --ignore-missing-deps -q -R --multi-release 21 \
        --print-module-deps --class-path 'extracted/dependencies/BOOT-INF/lib/*' \
        extracted/application/BOOT-INF/classes),jdk.crypto.ec,jdk.zipfs,jdk.naming.dns,jdk.charsets" \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre

FROM alpine:3.24
ARG MODULE
# The backend's public port; the simulator's is 8081. The backend's actuator, on 8090, is for the
# internal network only and never published.
ARG PORT=8080
ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:$PATH \
    PORT=${PORT} \
    # Container-aware: the heap is a share of the container's memory limit, not the host's, and an
    # out-of-memory error ends the process so the orchestrator restarts it.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
COPY --from=build /jre /opt/java

# A fixed numeric id: Kubernetes can only verify runAsNonRoot against a number, not a name.
RUN addgroup -S -g 10001 rideflow && adduser -S -u 10001 -G rideflow rideflow
USER 10001:10001
WORKDIR /app

# Least to most often changed.
COPY --from=build --chown=10001:10001 /workspace/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /workspace/extracted/application/ ./

EXPOSE ${PORT}
# Ready, not merely alive: the backend's /readyz is DOWN while PostgreSQL or Redis is.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/readyz" || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
