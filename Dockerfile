FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Resolve dependencies in their own layer so source edits don't re-download them.
RUN chmod +x mvnw && ./mvnw -B -q dependency:resolve dependency:resolve-plugins
COPY src src
RUN ./mvnw -B -q package -DskipTests && cp target/tickwise-backend-*.jar app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system tickwise && useradd --system --gid tickwise --no-create-home tickwise
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER tickwise
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
