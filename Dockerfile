# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY dlmm-common/pom.xml dlmm-common/
COPY dlmm-user-service/pom.xml dlmm-user-service/
COPY dlmm-token-service/pom.xml dlmm-token-service/
COPY dlmm-pool-engine/pom.xml dlmm-pool-engine/
COPY dlmm-transaction-service/pom.xml dlmm-transaction-service/
COPY dlmm-fee-service/pom.xml dlmm-fee-service/
COPY dlmm-price-oracle/pom.xml dlmm-price-oracle/
COPY dlmm-notification-service/pom.xml dlmm-notification-service/
COPY dlmm-admin-bff/pom.xml dlmm-admin-bff/
COPY dlmm-gateway/pom.xml dlmm-gateway/
RUN mvn dependency:go-offline -B || true
COPY . .
ARG MODULE
RUN mvn package -pl ${MODULE} -am -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG MODULE
COPY --from=build /app/${MODULE}/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
