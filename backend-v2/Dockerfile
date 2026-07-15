# NATIVE production image: GraalVM (Mandrel) build inside Docker so Railway
# ships the same binary CI validates. Runtime = ubi9-minimal (has a shell for
# start.sh's DATABASE_URL splitting; micro image does not).
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS build
USER root
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN ./mvnw -B package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=5g

FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4
WORKDIR /app
COPY --from=build /app/target/*-runner /app/application
COPY start.sh /start.sh
RUN chmod +x /start.sh /app/application
ENV QUARKUS_HTTP_PORT=8080
EXPOSE 8080
CMD ["/start.sh"]
