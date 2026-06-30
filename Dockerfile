FROM eclipse-temurin:25-jre-alpine
COPY target/llmlinkj-0.5.0.jar /app/app.jar
EXPOSE 8493
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
