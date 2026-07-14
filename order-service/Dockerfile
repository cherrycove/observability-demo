ARG MAVEN_BASE_IMAGE=docker.io/library/maven:3.9.9-eclipse-temurin-17
ARG JRE_BASE_IMAGE=docker.io/library/eclipse-temurin:17-jre-jammy
ARG DD_JAVA_AGENT_IMAGE=pubrepo.guance.com/datakit-operator/dd-lib-java-init:v1.55.10-ext

FROM ${DD_JAVA_AGENT_IMAGE} AS java_agent

FROM ${MAVEN_BASE_IMAGE} AS build
WORKDIR /workspace
ARG DD_JAVA_AGENT_SHA256=2682f18135bc52cca743a9326b913086697a9ac38d778f078eda0062f3d4a408

COPY pom.xml ./
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml

COPY . .
COPY --from=java_agent /datadog-init/dd-java-agent.jar /workspace/dd-java-agent.jar
RUN --mount=type=cache,target=/root/.m2 \
    echo "${DD_JAVA_AGENT_SHA256}  /workspace/dd-java-agent.jar" | sha256sum -c - && \
    mvn -pl order-service -am package -DskipTests

FROM ${JRE_BASE_IMAGE}
WORKDIR /app
COPY --from=build /workspace/order-service/target/order-service-1.0.0.jar /app/app.jar
COPY --from=build /workspace/dd-java-agent.jar /app/dd-java-agent.jar
RUN groupadd --system app && useradd --system --gid app --uid 10001 app && \
    mkdir -p /var/log/guance-observability-demo && chown -R app:app /app /var/log/guance-observability-demo
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/dd-java-agent.jar", "-jar", "/app/app.jar"]
