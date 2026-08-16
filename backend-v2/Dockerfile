# NATIVE production image, built from the REPO ROOT context (Railway: root dir =
# repo root, railwayConfigFile = backend-v2/railway.toml) so the sibling frontend/
# is in scope — the same pattern the retired FastAPI image used. Stage 1 builds the
# React SPA; stage 2 injects it into Quarkus' static resources before the GraalVM
# native build, so the built dist is never committed to the repo.

# Stage 1: build the React SPA (Vite) -> frontend/dist
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: GraalVM (Mandrel) native build. Inject the SPA into static resources
# (served at "/" + bundled into the native image) before packaging.
# PINNED by DIGEST (bean 42u0): this is jdk-25.0.4 — the exact Mandrel image
# Quarkus 3.37.3's container-build resolves to (:jdk-25), so Railway builds with the
# SAME toolchain CI validates (see .github/workflows/backend-v2.yml). A floating tag
# silently rolled once and broke the Railway native build while CI stayed green — a
# digest can't. Bump this DELIBERATELY when upgrading Quarkus (re-resolve :jdk-NN).
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image@sha256:93bcce120e35460b44c57efe2586e1d913cbfe32ddb418f0a7375a86850b54f1 AS build
USER root
WORKDIR /app
COPY backend-v2/pom.xml backend-v2/mvnw ./
COPY backend-v2/.mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY backend-v2/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/META-INF/resources/
RUN ./mvnw -B package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=5g

# Stage 3: runtime = ubi9-minimal (has a shell for start.sh's DATABASE_URL split).
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4
WORKDIR /app
COPY --from=build /app/target/*-runner /app/application
COPY backend-v2/start.sh /start.sh
RUN chmod +x /start.sh /app/application
ENV QUARKUS_HTTP_PORT=8080
EXPOSE 8080
CMD ["/start.sh"]
