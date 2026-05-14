# --- build stage -----------------------------------------------------------
FROM clojure:temurin-21-tools-deps-bookworm-slim AS build

WORKDIR /build

# Cache deps first so changes to source don't bust the maven layer.
COPY deps.edn build.clj ./
RUN clojure -P -T:build

COPY src/        src/
COPY resources/  resources/

RUN clojure -T:build uber

# --- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# Run as a non-root user.
RUN useradd --create-home --uid 10001 flaglog
WORKDIR /app

COPY --from=build /build/target/flaglog.jar /app/flaglog.jar

USER flaglog
EXPOSE 8080

ENV PORT=8080 \
    HOST=0.0.0.0 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/flaglog.jar"]
