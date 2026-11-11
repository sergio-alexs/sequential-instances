# PoC: Sequential Instances Manager con MongoDB

Prueba de concepto en **Spring Boot 4.x / Java 21** para el control de instancias secuenciales de microservicios, con heartbeat, detección de caídas, reutilización de números de instancia y concurrencia segura.

---

## 🚀 Características Implementadas

1. **Numeración Secuencial Automática y Mínimo Hueco Disponible**:
   - Cada instancia consulta MongoDB al iniciar y toma el menor número disponible.
   - Si se inician 3 instancias: se asignan los números `#1`, `#2`, `#3`.
   - Si se apaga la `#2` y luego se levanta una nueva, esta reutiliza el número `#2`.
2. **Identificador de Proceso (`proceso_cre_[numero]`) y Bean Inyectable**:
   - Al registrarse, se genera el identificador (ej: `proceso_cre_1`, `proceso_cre_2`).
   - Se inyecta automáticamente en el bean `ProcessContext` para que cualquier `@Service` lo consuma:
     ```java
     @Autowired
     private ProcessContext processContext;

     public void ejecutar() {
         String id = processContext.getProcessIdentifier(); // "proceso_cre_1"
         int num = processContext.getInstanceNumber();      // 1
     }
     ```
   - Además queda registrado en System properties: `System.getProperty("process.identifier")` y `System.getProperty("instance.number")`.
3. **Banner Visual en Logs al Iniciar**:
   - Se imprime un banner destacado al arrancar con el Pod/Host, grupo, número e identificador de proceso.
4. **Soporte Kubernetes (K8s)**:
   - Detección automática del nombre del Pod y su IP mediante variables de entorno estándar (`POD_NAME`, `HOSTNAME`, `POD_IP`).
5. **Aislamiento por Grupo**:
   - Agrupación automática usando `spring.application.name`. Cada servicio mantiene su propio contador y ciclo de vida de instancias.
6. **Heartbeat y Detección de Muertes (Sin Saturar MongoDB)**:
   - Cada instancia envía un pulso periódico (default: cada **15s**) actualizando `lastHeartbeat`.
   - Si no hay heartbeat en más de **45s** (3 pulsos perdidos), las demás instancias o la siguiente al iniciar la marcan como `INACTIVE`.
   - Chequeo periódico ligero cada **60s**.
7. **Protocolo de Espera Segura y Control de Tráfico en Kubernetes**:
   - **Espera inicial de estabilización (`startupDelayMs: 5000ms`)**: Al arrancar, el microservicio espera 5 segundos para permitir que cualquier instancia preexistente emita sus latidos y la red esté sincronizada.
   - **Bloqueo de Readiness Probe (`ReadinessState.REFUSING_TRAFFIC`)**: Durante esta espera, Kubernetes recibe un HTTP 503 (`OUT_OF_SERVICE`) en `/actuator/health/readiness`, evitando enviarle tráfico al Pod antes de tiempo.
   - **Doble Verificación de Muerte (`suspectVerificationDelayMs: 3000ms`)**: Antes de reclamar un número presuntamente inactivo, se realiza una pausa de 3s y re-consulta a Mongo para corroborar que no haya emitido latidos tardíos.
   - **Activación de Tráfico (`ReadinessState.ACCEPTING_TRAFFIC`)**: Tras confirmar y registrar la secuencia de manera atómica, el Readiness pasa a HTTP 200 (`UP`) y el Pod comienza a recibir peticiones.
8. **Apagado Limpio y Concurrencia**:
   - `@PreDestroy` marca inmediatamente el estado en `INACTIVE` ante un shutdown normal.
   - Índice único compuesto en `(groupName, instanceNumber)` + operaciones atómicas `findAndModify` con reintentos para evitar colisiones en arranques simultáneos.
8. **Spring Boot Actuator Custom Health Indicator**:
   - Endpoint `/actuator/health` con el componente `instanceRegistration` que expone el estado, número de instancia, host, pod y `processIdentifier`.
9. **Limpieza Automática (Purgado)**:
   - Scheduler que elimina registros `INACTIVE` con más de 7 días de antigüedad para mantener la colección limpia.
10. **Suite de Pruebas de Integración**:
   - Pruebas automatizadas cubriendo concurrencia (múltiples hilos compitiendo), asignación secuencial, reutilización, expiración por heartbeat, aislamiento y purga.

---

## ☸️ Despliegue en Kubernetes

En tu `deployment.yaml` de Kubernetes, puedes pasar los metadatos del Pod mediante la **Downward API**:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mi-microservicio
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: app
          image: mi-microservicio:latest
          env:
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: POD_IP
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
            - name: SPRING_DATA_MONGODB_URI
              value: "mongodb://mongo-service:27017/sequential-instances"
```

---

## 🛠️ Cómo Ejecutar

### 1. Iniciar MongoDB con Docker
```bash
docker-compose up -d
```

> [!NOTE]
> **Si en producción tu DBA no permite `auto-index-creation=true`:**
> Puedes entregar a tu equipo de base de datos el script [`scripts/mongo-init.js`](./scripts/mongo-init.js) o aplicarlo con:
> ```powershell
> .\scripts\apply-mongo-indexes.ps1
> ```
> O manualmente con `mongosh`:
> ```bash
> mongosh "mongodb://localhost:27017/sequential-instances" scripts/mongo-init.js
> ```
> Luego en `application.properties` puedes colocar tranquilamente `spring.data.mongodb.auto-index-creation=false`.

### 2. Ejecutar Múltiples Instancias en Distintos Puertos

**Opción Rápida (Script PowerShell que abre 3 ventanas):**
```powershell
.\scripts\start-instances.ps1
```
Para detenerlas todas:
```powershell
.\scripts\stop-instances.ps1
```

**Opción Manual (terminales individuales):**

**Terminal 1:**
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

**Terminal 2:**
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

**Terminal 3:**
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8083"
```

---

## 📡 Endpoints Disponibles

| Endpoint | Método | Descripción |
|---|---|---|
| `/instances` | `GET` | Lista todas las instancias del grupo con su estado (`ACTIVE`/`INACTIVE`). |
| `/instances/me` | `GET` | Información detallada de la instancia consultada. |
| `/actuator/health` | `GET` | Health check general y detalle de salud de la instancia. |

---

## 📮 Colección Postman

Importa el archivo [`Sequential_Instances_PoC.postman_collection.json`](./Sequential_Instances_PoC.postman_collection.json) en Postman. Ya contiene carpetas y llamadas listas para las instancias en los puertos 8081, 8082 y 8083.

---

## 🧪 Ejecutar Tests

```powershell
.\mvnw.cmd test
```

