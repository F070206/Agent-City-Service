FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY gateway/pom.xml ./gateway/pom.xml
COPY gateway/src ./gateway/src
RUN mvn -q -f gateway/pom.xml -DskipTests package
COPY src/main/java/com/hmdp/health/HttpHealthcheck.java ./health/HttpHealthcheck.java
RUN mkdir -p ./health/classes && javac -d ./health/classes ./health/HttpHealthcheck.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /source/gateway/target/cityai-gateway-0.0.1-SNAPSHOT.jar ./gateway.jar
COPY --from=build /source/health/classes ./healthcheck
COPY src/main/resources/static ./static
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
