# --- Stage 1: build with Maven (dependencies copied to target/lib by pom) ---
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests

# --- Stage 2: only JRE + app artifacts ---
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app

# One fat-less JAR (main) + dependency JARs (same layout as local mvn package)
COPY --from=build /build/target/smn-weather-mail-1.0.0.jar /app/app.jar
COPY --from=build /build/target/lib/ /app/lib/

# Classpath: manifest expects lib/ next to the main jar; matches run.sh
CMD ["java", "-cp", "/app/app.jar:/app/lib/*", "ar.gob.smn.weather.WeatherMailApplication"]
