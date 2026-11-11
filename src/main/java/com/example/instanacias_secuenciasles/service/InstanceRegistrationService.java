package com.example.instanacias_secuenciasles.service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.instanacias_secuenciasles.config.InstanceConfig;
import com.example.instanacias_secuenciasles.context.ProcessContext;
import com.example.instanacias_secuenciasles.model.InstanceStatus;
import com.example.instanacias_secuenciasles.model.ServiceInstance;
import com.example.instanacias_secuenciasles.repository.ServiceInstanceRepository;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class InstanceRegistrationService {

    private final MongoTemplate mongoTemplate;
    private final ServiceInstanceRepository repository;
    private final InstanceConfig config;
    private final ProcessContext processContext;
    private final ConfigurableEnvironment environment;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${spring.application.name}")
    private String groupName;

    @Value("${server.port:8080}")
    private int serverPort;

    private ServiceInstance currentInstance;

    // ──────────────────────────────────────────────
    //  Startup
    // ──────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        // Bloquear tráfico en Kubernetes durante el arranque y estabilización
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);

        long startupDelay = config.getStartupDelayMs();
        if (startupDelay > 0) {
            log.info("⏳ Iniciando periodo de espera de {}ms para estabilización de latidos en Kubernetes...", startupDelay);
            try {
                Thread.sleep(startupDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("⚠️ Espera inicial interrumpida");
            }
        }

        String podName = resolvePodName();
        String hostInfo = resolveHostInfo(podName);

        // 1. Marcar instancias expiradas como INACTIVE
        markExpiredInstances();

        // 2. Intentar reclamar o crear una instancia con reintentos
        int maxRetries = config.getMaxRegistrationRetries();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Intentar reclamar la INACTIVE con menor número
            currentInstance = claimInactiveInstance(hostInfo, podName);
            if (currentInstance != null) {
                break;
            }

            // No hay INACTIVE disponible, crear nueva
            try {
                currentInstance = createNewInstance(hostInfo, podName);
                break;
            } catch (DuplicateKeyException e) {
                log.warn("⚠️  Conflicto de concurrencia al registrar, reintentando... (intento {}/{})",
                        attempt, maxRetries);
            }
        }

        if (currentInstance == null) {
            throw new IllegalStateException(
                    "No se pudo registrar la instancia después de " + maxRetries + " intentos");
        }

        // 3. Actualizar ProcessContext y Environment de Spring
        int number = currentInstance.getInstanceNumber();
        String processId = currentInstance.getProcessIdentifier();

        processContext.update(number, processId, groupName, podName);

        System.setProperty("instance.number", String.valueOf(number));
        System.setProperty("process.identifier", processId);

        // 4. Habilitar Readiness de Kubernetes: Pod listo para recibir tráfico
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        log.info("🟢 Readiness probe de Kubernetes activada (ACCEPTING_TRAFFIC)");

        // 5. Imprimir banner destacado al iniciar
        printStartupBanner(number, processId, podName, hostInfo);
    }

    // ──────────────────────────────────────────────
    //  Heartbeat (cada 10s)
    // ──────────────────────────────────────────────

    @Scheduled(fixedRateString = "${instance.heartbeat-interval-ms:10000}")
    public void sendHeartbeat() {
        if (currentInstance == null) {
            return;
        }

        Query query = new Query(Criteria.where("_id").is(currentInstance.getId()));
        Update update = new Update().set("lastHeartbeat", Instant.now());
        mongoTemplate.updateFirst(query, update, ServiceInstance.class);

        log.debug("💓 Heartbeat enviado — Instancia #{} del grupo '{}'",
                currentInstance.getInstanceNumber(), groupName);
    }

    // ──────────────────────────────────────────────
    //  Revisión de expirados (cada 30s)
    // ──────────────────────────────────────────────

    @Scheduled(fixedRateString = "${instance.expiry-check-interval-ms:30000}")
    public void checkExpiredInstances() {
        int marked = markExpiredInstances();
        if (marked > 0) {
            log.info("🔍 {} instancia(s) expirada(s) marcada(s) como INACTIVE en grupo '{}'",
                    marked, groupName);
        }
    }

    // ──────────────────────────────────────────────
    //  Limpieza de registros obsoletos
    // ──────────────────────────────────────────────

    @Scheduled(fixedRateString = "${instance.cleanup-interval-ms:21600000}")
    public void cleanupOldInactiveInstances() {
        Instant cutoff = Instant.now().minus(
                java.time.Duration.ofDays(config.getCleanupRetentionDays()));

        Query query = new Query(Criteria.where("groupName").is(groupName)
                .and("status").is(InstanceStatus.INACTIVE)
                .and("stoppedAt").lt(cutoff));

        var result = mongoTemplate.remove(query, ServiceInstance.class);
        long deleted = result.getDeletedCount();

        if (deleted > 0) {
            log.info("🧹 {} registro(s) INACTIVE obsoleto(s) eliminado(s) del grupo '{}' (más de {} días)",
                    deleted, groupName, config.getCleanupRetentionDays());
        }
    }

    // ──────────────────────────────────────────────
    //  Shutdown
    // ──────────────────────────────────────────────

    @PreDestroy
    public void deregister() {
        if (currentInstance == null) {
            return;
        }

        // Informar a Kubernetes que este Pod ya no acepta tráfico
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        processContext.markDeregistered();

        Query query = new Query(Criteria.where("_id").is(currentInstance.getId()));
        Update update = new Update()
                .set("status", InstanceStatus.INACTIVE)
                .set("stoppedAt", Instant.now());
        mongoTemplate.updateFirst(query, update, ServiceInstance.class);

        log.info("🛑 Instancia #{} del grupo '{}' desregistrada (shutdown limpio)",
                currentInstance.getInstanceNumber(), groupName);
    }

    // ──────────────────────────────────────────────
    //  Consultas
    // ──────────────────────────────────────────────

    public List<ServiceInstance> getInstancesForGroup() {
        return repository.findByGroupName(groupName);
    }

    public List<ServiceInstance> getAllInstances() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "groupName", "instanceNumber"));
    }

    public ServiceInstance getCurrentInstance() {
        return currentInstance;
    }

    // ──────────────────────────────────────────────
    //  Métodos privados
    // ──────────────────────────────────────────────

    /**
     * Reclama atómicamente la instancia INACTIVE con el menor número del grupo,
     * realizando una doble verificación ante sospecha para no arrebatarle el número a una viva.
     */
    private ServiceInstance claimInactiveInstance(String hostInfo, String podName) {
        Query query = new Query(Criteria.where("groupName").is(groupName)
                .and("status").is(InstanceStatus.INACTIVE))
                .with(Sort.by(Sort.Direction.ASC, "instanceNumber"));

        // Se busca primero el documento candidato
        ServiceInstance candidate = mongoTemplate.findOne(query, ServiceInstance.class);
        if (candidate == null) {
            return null;
        }

        // Doble verificación: si la verificación está activa, pausar y volver a consultar en Mongo
        long verificationDelay = config.getSuspectVerificationDelayMs();
        if (verificationDelay > 0) {
            log.info("🔎 Realizando doble verificación para instancia vacante #{} (espera de {}ms)...",
                    candidate.getInstanceNumber(), verificationDelay);
            try {
                Thread.sleep(verificationDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("⚠️ Doble verificación interrumpida");
            }

            // Volver a consultar el estado del candidato en MongoDB
            ServiceInstance recheck = mongoTemplate.findById(candidate.getId(), ServiceInstance.class);
            if (recheck == null || recheck.getStatus() == InstanceStatus.ACTIVE) {
                log.warn("⚠️ La instancia #{} se reactivó durante la verificación. Buscando otra...",
                        candidate.getInstanceNumber());
                return null; // La instancia está viva o cambió, abortar reclamo
            }
        }

        String processId = buildProcessIdentifier(candidate.getInstanceNumber());

        Update update = new Update()
                .set("status", InstanceStatus.ACTIVE)
                .set("hostInfo", hostInfo)
                .set("podName", podName)
                .set("processIdentifier", processId)
                .set("lastHeartbeat", Instant.now())
                .set("startedAt", Instant.now())
                .unset("stoppedAt");

        // findAndModify atómico condicionado a que siga estando estrictamente INACTIVE
        Query atomicQuery = new Query(Criteria.where("_id").is(candidate.getId())
                .and("status").is(InstanceStatus.INACTIVE));

        return mongoTemplate.findAndModify(atomicQuery, update,
                FindAndModifyOptions.options().returnNew(true),
                ServiceInstance.class);
    }

    /**
     * Crea una nueva instancia con el menor número secuencial no ocupado.
     */
    private ServiceInstance createNewInstance(String hostInfo, String podName) {
        List<Integer> usedNumbers = repository.findByGroupName(groupName)
                .stream()
                .map(ServiceInstance::getInstanceNumber)
                .sorted()
                .toList();

        // Encontrar el primer hueco: 1, 2, 3... saltar los ocupados
        int nextNumber = 1;
        for (int used : usedNumbers) {
            if (used == nextNumber) {
                nextNumber++;
            } else if (used > nextNumber) {
                break;
            }
        }

        String processId = buildProcessIdentifier(nextNumber);

        ServiceInstance instance = ServiceInstance.builder()
                .groupName(groupName)
                .instanceNumber(nextNumber)
                .processIdentifier(processId)
                .podName(podName)
                .status(InstanceStatus.ACTIVE)
                .hostInfo(hostInfo)
                .lastHeartbeat(Instant.now())
                .startedAt(Instant.now())
                .build();

        return repository.save(instance);
    }

    private String buildProcessIdentifier(int instanceNumber) {
        return config.getProcessPrefix() + instanceNumber;
    }

    /**
     * Marca como INACTIVE todas las instancias del grupo cuyo heartbeat expiró.
     *
     * @return cantidad de instancias marcadas
     */
    private int markExpiredInstances() {
        Instant expiryThreshold = Instant.now().minusMillis(config.getExpiryThresholdMs());

        Query query = new Query(Criteria.where("groupName").is(groupName)
                .and("status").is(InstanceStatus.ACTIVE)
                .and("lastHeartbeat").lt(expiryThreshold));

        Update update = new Update()
                .set("status", InstanceStatus.INACTIVE)
                .set("stoppedAt", Instant.now());

        var result = mongoTemplate.updateMulti(query, update, ServiceInstance.class);
        return (int) result.getModifiedCount();
    }

    private String resolvePodName() {
        // 1. Kubernetes Downward API: POD_NAME
        String k8sPodName = System.getenv("POD_NAME");
        if (k8sPodName != null && !k8sPodName.isBlank()) {
            return k8sPodName.trim();
        }

        // 2. Kubernetes / Docker default container hostname: HOSTNAME
        String envHostname = System.getenv("HOSTNAME");
        if (envHostname != null && !envHostname.isBlank()) {
            return envHostname.trim();
        }

        // 3. Fallback a InetAddress local
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String resolveHostInfo(String podName) {
        String podIp = System.getenv("POD_IP");
        if (podIp != null && !podIp.isBlank()) {
            return podName + " (" + podIp.trim() + ":" + serverPort + ")";
        }
        return podName + ":" + serverPort;
    }

    private void printStartupBanner(int number, String processId, String podName, String hostInfo) {
        log.info("""
                
                ================================================================================
                🚀 INSTANCIA REGISTRADA CON ÉXITO
                --------------------------------------------------------------------------------
                   • Número de Instancia   : #{}
                   • Identificador Proceso : {}
                   • Grupo (Microservicio) : {}
                   • Pod (Kubernetes)      : {}
                   • Host / Puerto         : {}
                ================================================================================
                """, number, processId, groupName, podName, hostInfo);
    }
}

