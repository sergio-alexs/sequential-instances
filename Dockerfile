# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────
# Etapa 1: Build con Maven Wrapper
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copiar archivos de Maven para aprovechar cache de capas
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copiar código fuente y compilar JAR
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# ─────────────────────────────────────────────────────────────
# Etapa 2: Imagen de Runtime ligera y segura
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Crear usuario sin privilegios de root para Kubernetes
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copiar el JAR desde la etapa de compilación
COPY --from=builder /workspace/target/*.jar app.jar

# Puerto HTTP por defecto
EXPOSE 8080

# Opciones JVM optimizadas para contenedores
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

