# ===== MCP Nacos Server Dockerfile（单阶段，不分阶段构建）=====
# 独立 MCP Server（SSE 传输），注册到 Nacos 供客户端发现（本项目主应用是 SSE 直连 9988）。
# 构建：docker build -f mcp-nacos-server/Dockerfile -t mcp-nacos-server:1.0.0 .
# 运行：见 docker/app-deploy.yml

FROM maven:3.9-eclipse-temurin-17

WORKDIR /app

COPY pom.xml .
COPY mcp-nacos-server/pom.xml mcp-nacos-server/pom.xml
RUN mvn -q dependency:go-offline -pl mcp-nacos-server -am || true

COPY . .
RUN mvn -q package -pl mcp-nacos-server -am -DskipTests

EXPOSE 9988

# Nacos 地址等走环境变量（compose 注入）
ENTRYPOINT ["java", "-jar", "/app/mcp-nacos-server/target/mcp-nacos-server-0.0.1-SNAPSHOT.jar"]
