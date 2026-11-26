FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY examples ./examples
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 universum && useradd --uid 10001 --gid 10001 --no-create-home universum
COPY --from=build --chown=10001:10001 /workspace/target/config-server-0.0.1-SNAPSHOT.jar /app/server.jar
COPY --chown=10001:10001 docker/healthcheck.sh /app/healthcheck.sh
USER 10001:10001
ENV SERVER_PORT=8888
EXPOSE 8888
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["/bin/bash", "/app/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
