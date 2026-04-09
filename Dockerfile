FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /app
COPY pom.xml .
COPY common/pom.xml common/
COPY identity-service/pom.xml identity-service/
COPY workflow-service/pom.xml workflow-service/
COPY worker-service/pom.xml worker-service/
COPY audit-service/pom.xml audit-service/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:25-jre-alpine AS identity
COPY --from=builder /app/identity-service/target/*.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:25-jre-alpine AS workflow
COPY --from=builder /app/workflow-service/target/*.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:25-jre-alpine AS worker
COPY --from=builder /app/worker-service/target/*.jar /app/app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:25-jre-alpine AS audit
COPY --from=builder /app/audit-service/target/*.jar /app/app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
