FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /source/target/classes ./classes
COPY --from=build /source/target/dependency ./dependency
ENTRYPOINT ["java", "-cp", "/app/classes:/app/dependency/*", "com.hmdp.micro.MicroserviceApplication"]
