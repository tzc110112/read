FROM eclipse-temurin:22-jdk AS builder

WORKDIR /build
COPY . /build

RUN chmod +x ./gradlew && \
    ./gradlew clean jar --no-daemon -x test && \
    rm -rf /root/.gradle

FROM eclipse-temurin:22-jre

WORKDIR /app
RUN mkdir -p /app/data

COPY --from=builder /build/build/libs/solon-read-1.0-SNAPSHOT.jar /app/read.jar
COPY --from=builder /build/conf/conf.yml /app/conf.yml

# 从 Release 下载 Flutter Web 前端资源
RUN set -eux; \
    apt-get update; \
    apt-get install -y wget; \
    wget -q "https://github.com/tzc110112/read/releases/download/flutter-static/flutter-static.tar.gz" -O /tmp/flutter.tar.gz; \
    tar xzf /tmp/flutter.tar.gz -C /app; \
    rm -f /tmp/flutter.tar.gz; \
    apt-get remove -y wget; \
    apt-get autoremove -y; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*

EXPOSE 8080

ENV TZ=Asia/Shanghai

CMD ["java", "-jar", "/app/read.jar"]
