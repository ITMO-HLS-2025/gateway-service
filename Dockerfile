FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests -T 1C package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY --from=build /app/${JAR_FILE} app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"]
