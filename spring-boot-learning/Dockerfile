# ===========================================
# 后端 Dockerfile(多阶段构建)
# 构建: maven:3.9-eclipse-temurin-21
# 运行: eclipse-temurin:21-jdk(必须 JDK 而非 JRE:
#       Java 在线沙箱依赖 javac 编译用户代码)
# 数据: /app/data 挂载数据卷, SQLite 持久化
# ===========================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷贝 pom 利用依赖缓存层
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jdk
WORKDIR /app

COPY --from=build /build/target/spring-boot-learning-*.jar app.jar

# SQLite 数据库文件目录(admin.db / sandbox.db),挂载卷实现持久化
VOLUME /app/data

EXPOSE 8080

ENV SERVER_PORT=8080 \
    JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
