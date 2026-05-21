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
# Sprint 9-DS-r4 (TD-3) — force UTF-8 default encoding. eclipse-temurin
# JRE images can ship with a non-UTF-8 platform default depending on
# the underlying glibc locale; without -Dfile.encoding=UTF-8 the JVM
# uses platform default for any I/O without an explicit charset and
# Cyrillic strings ship through the wire as Windows-1251 → UTF-8 →
# UTF-8 → "double-encoded mojibake" (Sprint 8 reports of garbled
# Cyrillic in /api/v1/pools JSON). JAVA_TOOL_OPTIONS is the
# belt-and-braces fallback: any java subprocess spawned by the app
# inherits the encoding.
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
