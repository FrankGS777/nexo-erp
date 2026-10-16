FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S demo && adduser -S demo -G demo
WORKDIR /app
COPY --from=build /workspace/target/nexo-erp-0.1.0-SNAPSHOT.jar app.jar
USER demo
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-Djava.awt.headless=true", "-jar", "app.jar"]
