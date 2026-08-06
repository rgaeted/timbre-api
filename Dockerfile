FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn ./.mvn
COPY pom.xml mvnw ./
COPY src ./src
RUN chmod +x mvnw && ./mvnw -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh
EXPOSE 8081
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
