# ==============================================================================
# 阶段 1: 前端静态资源构建 (Vue 3 + Element Plus + Vite)
# ==============================================================================
FROM node:18-alpine AS frontend-builder
WORKDIR /app/frontend

# 优先安装依赖，利用 Docker 缓存加速后续构建
COPY frontend/package*.json ./
RUN npm install

# 复制前端代码并执行生产打包 (输出至 /app/frontend/dist)
COPY frontend/ ./
RUN npm run build


# ==============================================================================
# 阶段 2: 后端打包 (Maven + Spring Boot 3 + Temurin JDK 17)
# ==============================================================================
FROM maven:3.9-eclipse-temurin-17-alpine AS backend-builder
WORKDIR /app/backend

# 复制 pom.xml
COPY backend/pom.xml .

# 复制后端源代码
COPY backend/src ./src

# 将阶段 1 构建出的前端静态资源放入 Spring Boot static 目录
# 这样 Spring Boot 启动后可直接在根路径 "/" 提供前端界面，无需额外配置 Nginx
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static

# 执行编译打包并跳过单元测试
RUN mvn clean package -DskipTests


# ==============================================================================
# 阶段 3: 生产运行时镜像 (Eclipse Temurin JRE 17 Alpine)
# ==============================================================================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 配置上海时区 (确保 A 股交易时间与日志时间正确)
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 创建数据存储目录供 SQLite 使用
RUN mkdir -p /app/data

# 从构建阶段复制打包好的可执行 jar 包
COPY --from=backend-builder /app/backend/target/*.jar app.jar

# Render 平台会自动注入 PORT 环境变量（通常是 10000），默认兜底为 8080
ENV PORT=8080
# 针对 Render 免费套餐 512MB 内存进行 JVM 参数优化，防止 OOM
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseSerialGC"

# 暴露端口
EXPOSE 8080

# 启动 Spring Boot 后端应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
