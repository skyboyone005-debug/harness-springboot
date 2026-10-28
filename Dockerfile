# docker build --build-arg SVC=user .
FROM maven:3.9-eclipse-temurin-17 AS build
ARG SVC
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl services/${SVC} -am package -DskipTests \
 && cp services/${SVC}/target/${SVC}-service-*.jar /app.jar

FROM eclipse-temurin:17-jre
COPY --from=build /app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
