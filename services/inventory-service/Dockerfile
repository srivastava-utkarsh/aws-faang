# =============================================================================
# Multi-stage Dockerfile — 3 stages for minimum final image size
#
# Stage 1 (build)   : Compile the Spring Boot fat JAR using Maven
# Stage 2 (jlink)   : Use jlink to strip the JRE down to only the modules
#                     this app actually uses — cuts ~60% off the JRE size
# Stage 3 (runtime) : Alpine + the custom minimal JRE + the JAR only
#
# Why eclipse-temurin:21-jdk-alpine for build?
#   - Java 21 is the current LTS with full native ARM64 support (Apple Silicon)
#   - Java 19 is EOL — Adoptium no longer publishes ARM64 images for it
#   - Alpine variant is ~170MB vs ~350MB for the Debian/Ubuntu variants
#
# Final image size target: ~120-150MB (vs ~350MB with plain jre-alpine,
#   ~600MB+ with a full JDK base image)
# =============================================================================

# ── STAGE 1: BUILD THE FAT JAR ───────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy Maven wrapper and pom first — Docker caches this layer separately.
# As long as pom.xml doesn't change, `dependency:go-offline` is not re-run
# on subsequent builds, saving 1-2 minutes of download time.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Copy source and build. -DskipTests: tests run in CI, not during image build.
COPY src ./src
RUN ./mvnw package -DskipTests -B


# ── STAGE 2: BUILD A CUSTOM MINIMAL JRE WITH JLINK ──────────────────────────
# jlink (part of the JDK since Java 9) assembles a custom JRE containing ONLY
# the JDK modules this application needs. We use jdeps to auto-discover them
# from the fat JAR, then pass that list to jlink.
#
# Modules typically needed by a Spring Boot + AWS SDK app:
#   java.base, java.logging, java.naming, java.xml, java.sql,
#   java.desktop (for some crypto), jdk.crypto.ec (TLS), etc.
# jdeps figures this out automatically from the JAR bytecode.
FROM eclipse-temurin:21-jdk-alpine AS jlink

WORKDIR /jlink

# Copy the fat JAR from stage 1
COPY --from=build /app/target/inventory-service-*.jar app.jar

# Discover required modules from the JAR and build the minimal JRE.
# --no-header-files --no-man-pages: strip docs (we don't need them in a container)
# --compress=2: zip-level compression on JRE files
# --strip-debug: remove debug symbols — reduces size, not useful in prod containers
# || true on jdeps: some dynamic module references cause warnings; we ignore them
# and fall back to a known-good module list if jdeps produces nothing.
RUN MODULES=$(jdeps \
      --ignore-missing-deps \
      --print-module-deps \
      --multi-release 21 \
      app.jar 2>/dev/null || echo "") && \
    # Ensure a baseline set of modules is always included even if jdeps misses
    # some runtime-loaded ones (e.g. JDBC drivers loaded via Class.forName).
    # java.compiler (javax.lang.model.*) is one of these: Spring Data JPA's
    # RepositoryRegistrationAotProcessor touches javax.lang.model.SourceVersion
    # in a static initializer even on a normal (non-AOT, non-native) run, so
    # it's needed at startup, not just for actual AOT/native-image builds.
    MODULES="java.base,java.logging,java.naming,java.xml,java.sql,java.desktop,\
java.management,java.security.jgss,java.instrument,java.compiler,\
jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,\
jdk.httpserver,${MODULES}" && \
    jlink \
      --add-modules "$MODULES" \
      --no-header-files \
      --no-man-pages \
      --compress=2 \
      --strip-debug \
      --output /custom-jre


# ── STAGE 3: MINIMAL RUNTIME IMAGE ──────────────────────────────────────────
# Plain Alpine Linux (~5MB) + our custom JRE + the JAR.
# NO full JDK, NO Maven, NO build tools — nothing that isn't needed to run.
#
# Why plain alpine (not eclipse-temurin base)?
#   eclipse-temurin includes the full JRE. We already have a custom stripped one
#   from stage 2, so using alpine:3.20 as base is lighter.
FROM alpine:3.20

# Install only what's necessary at runtime:
#   libc6-compat: glibc compatibility shim (Temurin JRE is glibc-linked, Alpine uses musl)
#   ca-certificates: TLS root certs for HTTPS calls to AWS endpoints
RUN apk add --no-cache libc6-compat ca-certificates && \
    # Create a non-root user — running as root in a container is a security risk
    addgroup -S app && adduser -S app -G app

WORKDIR /app

# Copy the custom minimal JRE from stage 2
COPY --from=jlink /custom-jre /opt/java

# Copy the application JAR from stage 1
COPY --from=build /app/target/inventory-service-*.jar app.jar

# Put the custom JRE on PATH
ENV PATH="/opt/java/bin:${PATH}" \
    JAVA_HOME="/opt/java"

# Run as non-root
USER app

EXPOSE 8080

# JVM flags tuned for containers:
#   -XX:+UseContainerSupport     : JVM reads CPU/memory limits from cgroup (Fargate sets these)
#   -XX:MaxRAMPercentage=75.0    : use 75% of the container's memory limit for heap
#                                  (leaves room for metaspace, thread stacks, off-heap)
#   -XX:+ExitOnOutOfMemoryError  : crash fast on OOM rather than limping along
#   -Djava.security.egd=...      : faster SecureRandom init (common Spring Boot startup fix)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
