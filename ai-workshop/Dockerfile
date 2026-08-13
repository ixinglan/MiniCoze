# ===== AI Workshop 主应用 Dockerfile（单阶段，不分阶段构建）=====
# 一个镜像搞定「构建 + 运行」：基于 Maven + JDK 17 镜像，先 mvn package 打包 jar，再 java -jar 启动。
# 简单直接，代价是镜像带着 Maven（体积较大）；想要小镜像时再升级多阶段。
#
# 构建：docker build -f ai-workshop/Dockerfile -t ai-workshop:1.0.0 .
# 运行：见 docker/app-deploy.yml（compose 一键起，连宿主机已有 MySQL/Milvus/Ollama/Langfuse）

FROM maven:3.9-eclipse-temurin-17

WORKDIR /app

# 1) 先把 pom 拷进来单独下载依赖（利用 Docker 层缓存：依赖没变时后续构建不会重新下载）
COPY pom.xml .
COPY ai-workshop/pom.xml ai-workshop/pom.xml
COPY mcp-nacos-server/pom.xml mcp-nacos-server/pom.xml
RUN mvn -q dependency:go-offline -pl ai-workshop -am || true

# 2) 拷贝全部源码并打包（跳过测试，构建更快）
COPY . .
RUN mvn -q package -pl ai-workshop -am -DskipTests

# 运行端口（compose 映射 9999）
EXPOSE 9999

# API Key / 数据库地址等敏感配置全部走环境变量（compose 注入），不写进镜像
ENTRYPOINT ["java", "-jar", "/app/ai-workshop/target/ai-workshop-0.0.1-SNAPSHOT.jar"]
