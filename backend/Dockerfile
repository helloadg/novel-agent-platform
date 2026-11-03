# ============ novel-agent-platform 后端 Dockerfile ============
# 多阶段构建, 最终镜像 <200MB
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
# 先下载依赖, 利用 Docker 缓存
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q && ls target/*.jar

# 运行时镜像 (JRE 17 + headless)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 创建非 root 用户
RUN addgroup -S app && adduser -S app -G app

COPY --from=builder /app/target/*.jar app.jar

USER app
EXPOSE 8080

# JVM 参数: 容器化场景推荐的内存设置
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
