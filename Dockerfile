# Multi-stage build for LLM API Gateway
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget \
    && addgroup -S gateway && adduser -S gateway -G gateway
USER gateway
COPY --from=build /app/target/llm-gateway-*.jar app.jar
EXPOSE 8080
# Override via docker-compose (e.g. -Xmx256m for 512MB droplets)
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
