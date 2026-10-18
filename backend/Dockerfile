# The backend as a production container image (README, Deployment).
#   docker build -t tech-interviewer-backend backend/
# The tests do not run here: they need Docker themselves (Testcontainers) and run in CI (.github/workflows/ci.yml).

# ---- build: the Maven wrapper packages the jar. The dependencies are their own layer, so they download again only when
# pom.xml changes. (No BuildKit cache mounts: the file builds with any Docker builder, hosted ones included.)
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -o -Dmaven.test.skip=true package \
    && cp target/tech-interviewer-*.jar application.jar \
    # Spring Boot's layered extraction: the dependencies (which rarely change) get their own image layer.
    && java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# ---- runtime: a JRE, an unprivileged user, nothing else
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER app

# The prod profile: JSON logs, no API documentation, CORS and a JWT secret required, TLS to the database (README).
ENV SPRING_PROFILES_ACTIVE=prod \
    # The heap follows the container's memory limit; an out-of-memory error ends the process so the platform restarts it.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    # No Docker CLI in this image: candidate code is not run here (coding answers are reviewed by the AI only).
    CODE_EXECUTION_ENABLED=false
# The platform's PORT is honoured (application.yml); 8080 otherwise.
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT:-${PORT:-8080}}/actuator/health/liveness" || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
