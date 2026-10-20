FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/jxsc-backend-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod JXSC_MEDIA_FFMPEG=/usr/bin/ffmpeg JXSC_MEDIA_FFPROBE=/usr/bin/ffprobe
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl --fail --silent http://127.0.0.1:8080/api/v1/health/ready || exit 1
ENTRYPOINT ["java","-Xmx1536m","-Djava.io.tmpdir=/tmp","-jar","/app/app.jar"]
