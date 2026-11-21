# 构建阶段：使用预装 Maven 与 JDK25 一致的基础镜像
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# 使用阿里云镜像加速依赖下载（与本机开发环境一致）
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B package -DskipTests

# 运行阶段
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/clinical-fact-recon-1.0.0.jar /app/app.jar
EXPOSE 8080
ENV DB_URL=jdbc:postgresql://db:5432/recon \
    DB_USER=recon \
    DB_PASSWORD=recon
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
