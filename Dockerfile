FROM eclipse-temurin:22-jdk AS builder

WORKDIR /build
COPY . /build

RUN chmod +x ./gradlew && \
    ./gradlew clean jar --no-daemon -x test && \
    rm -rf /root/.gradle

FROM eclipse-temurin:22-jre

WORKDIR /app

# jar + 依赖 libs 目录都要 COPY
COPY --from=builder /build/build/libs/ /app/

EXPOSE 8080

ENV TZ=Asia/Shanghai

CMD ["java", "-jar", "/app/solon-read-1.0-SNAPSHOT.jar"]
