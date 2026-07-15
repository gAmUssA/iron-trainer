# Dark-deploy image: JVM mode first (fast Railway builds, ~1s start).
# Native-image deploys switch in via GHCR once the strangle carries traffic.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/quarkus-app/ ./
COPY start.sh /start.sh
RUN chmod +x /start.sh
ENV QUARKUS_HTTP_PORT=8080
EXPOSE 8080
CMD ["/start.sh"]
