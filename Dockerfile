# 使用 eclipse-temurin 提供的基于 Alpine 的轻量级 JRE 8 镜像
# 仅包含 JRE 环境，满足无 JDK 也能运行的需求，且镜像体积更小
FROM eclipse-temurin:8-jre-alpine

# 指定工作目录
WORKDIR /app

# 声明挂载卷，可用于外部挂载日志和动态配置
VOLUME /tmp
VOLUME /app/config
VOLUME /app/logs

# 将预编译的 jar 包及外置的基础配置文件复制到容器内
COPY sty-visit.jar /app/app.jar
COPY application.yml /app/application.yml

# 声明项目所需暴露的端口
EXPOSE 8080

# 配置启动参数，优化 JVM 内存并启动应用 (Spring Boot 默认优先读取同级目录的 application.yml)
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
