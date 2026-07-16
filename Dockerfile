FROM eclipse-temurin:22-jdk AS builder

WORKDIR /build
COPY . /build

RUN chmod +x ./gradlew && \
    ./gradlew clean jar --no-daemon -x test && \
    rm -rf /root/.gradle

FROM eclipse-temurin:22-jre

WORKDIR /app

COPY --from=builder /build/build/libs/solon-read-1.0-SNAPSHOT.jar /app/read.jar
COPY --from=builder /build/conf/conf.yml /app/conf.yml

EXPOSE 8080

ENV TZ=Asia/Shanghai

CMD ["java", "-jar", "/app/read.jar"]
