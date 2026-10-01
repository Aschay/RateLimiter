FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --gid 1001 appgroup \
    && useradd --uid 1001 --gid 1001 --create-home appuser

COPY target/*.jar app.jar

RUN chown -R 1001:1001 /app

USER 1001:1001

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "app.jar"]