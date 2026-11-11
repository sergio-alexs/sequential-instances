package com.example.instanacias_secuenciasles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.example.instanacias_secuenciasles.model.InstanceStatus;
import com.example.instanacias_secuenciasles.model.ServiceInstance;
import com.example.instanacias_secuenciasles.repository.ServiceInstanceRepository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/sequential_instances_test",
        "instance.startup-delay-ms=0",
        "instance.suspect-verification-delay-ms=0",
        "instance.heartbeat-interval-ms=999999999",
        "instance.expiry-check-interval-ms=999999999",
        "instance.cleanup-interval-ms=999999999"
})
class InstanceRegistrationIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ServiceInstanceRepository repository;

    @Autowired
    private com.example.instanacias_secuenciasles.context.ProcessContext processContext;

    private static final String GROUP = "test-service";

    @BeforeEach
    void cleanup() {
        mongoTemplate.dropCollection(ServiceInstance.class);
        // Recrear índice único después de drop
        mongoTemplate.indexOps(ServiceInstance.class).ensureIndex(
                new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(
                        new org.bson.Document("groupName", 1).append("instanceNumber", 1))
                        .unique());
    }

    // ──────────────────────────────────────────────
    //  Test 1: Registro secuencial básico
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Registrar 3 instancias debe asignar números 1, 2, 3")
    void shouldAssignSequentialNumbers() {
        ServiceInstance i1 = registerInstance("host-a:8081");
        ServiceInstance i2 = registerInstance("host-b:8082");
        ServiceInstance i3 = registerInstance("host-c:8083");

        assertEquals(1, i1.getInstanceNumber());
        assertEquals(2, i2.getInstanceNumber());
        assertEquals(3, i3.getInstanceNumber());
        assertEquals(3, repository.findByGroupNameAndStatus(GROUP, InstanceStatus.ACTIVE).size());
    }

    // ──────────────────────────────────────────────
    //  Test 2: Reutilización de IDs
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Al desactivar la instancia 2, la siguiente debe tomar el número 2")
    void shouldReuseInactiveInstanceNumber() {
        registerInstance("host-a:8081");
        ServiceInstance i2 = registerInstance("host-b:8082");
        registerInstance("host-c:8083");

        // Desactivar instancia #2
        deactivateInstance(i2.getId());

        // Registrar nueva — debe tomar el #2
        ServiceInstance reused = registerInstance("host-d:8084");
        assertEquals(2, reused.getInstanceNumber());
        assertEquals("host-d:8084", reused.getHostInfo());
        assertEquals(InstanceStatus.ACTIVE, reused.getStatus());
    }

    // ──────────────────────────────────────────────
    //  Test 3: Reutilización toma el menor disponible
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Con instancias 1 y 3 activas, 2 inactive, nueva toma el 2")
    void shouldReuseLowestInactiveNumber() {
        ServiceInstance i1 = registerInstance("host-a:8081");
        ServiceInstance i2 = registerInstance("host-b:8082");
        ServiceInstance i3 = registerInstance("host-c:8083");

        // Desactivar 1 y 2
        deactivateInstance(i1.getId());
        deactivateInstance(i2.getId());

        // La siguiente debe tomar el 1 (el menor INACTIVE)
        ServiceInstance reused = registerInstance("host-d:8084");
        assertEquals(1, reused.getInstanceNumber());
    }

    // ──────────────────────────────────────────────
    //  Test 4: Concurrencia — registro simultáneo
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("5 registros simultáneos deben asignar números únicos del 1 al 5")
    void shouldHandleConcurrentRegistrations() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CopyOnWriteArrayList<ServiceInstance> results = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Esperar a que todos estén listos
                    ServiceInstance instance = registerInstanceWithRetry("host-" + idx + ":808" + idx);
                    results.add(instance);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // ¡Todos a la vez!
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Errores durante registro concurrente: " + errors);
        assertEquals(threadCount, results.size());

        // Verificar que todos los números son únicos y van del 1 al 5
        List<Integer> numbers = results.stream()
                .map(ServiceInstance::getInstanceNumber)
                .sorted()
                .toList();

        assertEquals(List.of(1, 2, 3, 4, 5), numbers,
                "Los números asignados deben ser 1-5 sin huecos ni duplicados");
    }

    // ──────────────────────────────────────────────
    //  Test 5: Detección de heartbeat expirado
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Instancias con heartbeat expirado deben marcarse como INACTIVE")
    void shouldMarkExpiredInstancesAsInactive() {
        // Crear instancia con heartbeat viejo (hace 1 minuto)
        ServiceInstance old = ServiceInstance.builder()
                .groupName(GROUP)
                .instanceNumber(1)
                .status(InstanceStatus.ACTIVE)
                .hostInfo("host-a:8081")
                .lastHeartbeat(Instant.now().minusSeconds(60))
                .startedAt(Instant.now().minusSeconds(120))
                .build();
        repository.save(old);

        // Crear instancia con heartbeat reciente
        ServiceInstance fresh = ServiceInstance.builder()
                .groupName(GROUP)
                .instanceNumber(2)
                .status(InstanceStatus.ACTIVE)
                .hostInfo("host-b:8082")
                .lastHeartbeat(Instant.now())
                .startedAt(Instant.now())
                .build();
        repository.save(fresh);

        // Marcar expiradas (umbral: 30s)
        int marked = markExpiredInstances(30000);

        assertEquals(1, marked);

        // Verificar estados
        ServiceInstance updatedOld = repository.findById(old.getId()).orElseThrow();
        ServiceInstance updatedFresh = repository.findById(fresh.getId()).orElseThrow();

        assertEquals(InstanceStatus.INACTIVE, updatedOld.getStatus());
        assertEquals(InstanceStatus.ACTIVE, updatedFresh.getStatus());
    }

    // ──────────────────────────────────────────────
    //  Test 6: Limpieza de registros obsoletos
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Registros INACTIVE con más de 7 días deben eliminarse")
    void shouldCleanupOldInactiveRecords() {
        // Registro INACTIVE viejo (hace 10 días)
        ServiceInstance oldInactive = ServiceInstance.builder()
                .groupName(GROUP)
                .instanceNumber(1)
                .status(InstanceStatus.INACTIVE)
                .hostInfo("host-a:8081")
                .lastHeartbeat(Instant.now().minusSeconds(864000)) // 10 días
                .startedAt(Instant.now().minusSeconds(864000))
                .stoppedAt(Instant.now().minusSeconds(864000))
                .build();
        repository.save(oldInactive);

        // Registro INACTIVE reciente (hace 1 día)
        ServiceInstance recentInactive = ServiceInstance.builder()
                .groupName(GROUP)
                .instanceNumber(2)
                .status(InstanceStatus.INACTIVE)
                .hostInfo("host-b:8082")
                .lastHeartbeat(Instant.now().minusSeconds(86400)) // 1 día
                .startedAt(Instant.now().minusSeconds(86400))
                .stoppedAt(Instant.now().minusSeconds(86400))
                .build();
        repository.save(recentInactive);

        // Registro ACTIVE (no debe tocarse)
        ServiceInstance active = ServiceInstance.builder()
                .groupName(GROUP)
                .instanceNumber(3)
                .status(InstanceStatus.ACTIVE)
                .hostInfo("host-c:8083")
                .lastHeartbeat(Instant.now())
                .startedAt(Instant.now())
                .build();
        repository.save(active);

        // Limpiar registros de más de 7 días
        long deleted = cleanupOldRecords(7);

        assertEquals(1, deleted);
        assertEquals(2, repository.findByGroupName(GROUP).size());
        assertFalse(repository.findById(oldInactive.getId()).isPresent(), "El registro viejo debe haberse eliminado");
        assertTrue(repository.findById(recentInactive.getId()).isPresent(), "El registro reciente debe permanecer");
        assertTrue(repository.findById(active.getId()).isPresent(), "El registro activo debe permanecer");
    }

    // ──────────────────────────────────────────────
    //  Test 7: Aislamiento entre grupos
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Instancias de diferentes grupos deben tener numeración independiente")
    void shouldIsolateGroupNumbering() {
        // Grupo A
        ServiceInstance a1 = insertInstance("group-a", 1, "host-a:8081");
        ServiceInstance a2 = insertInstance("group-a", 2, "host-a:8082");

        // Grupo B
        ServiceInstance b1 = insertInstance("group-b", 1, "host-b:8081");

        assertEquals(1, a1.getInstanceNumber());
        assertEquals(2, a2.getInstanceNumber());
        assertEquals(1, b1.getInstanceNumber());

        // Ambos grupos con instancia #1, sin conflicto
        assertEquals(2, repository.findByGroupName("group-a").size());
        assertEquals(1, repository.findByGroupName("group-b").size());
    }

    // ──────────────────────────────────────────────
    //  Test 8: Identificador de proceso y ProcessContext
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Debe generar proceso_cre_[numero] y tener el bean ProcessContext listo para inyección")
    void shouldGenerateProcessIdentifierAndContext() {
        assertNotNull(processContext, "ProcessContext debe inyectarse como bean de Spring");
        assertTrue(processContext.isRegistered(), "ProcessContext debe estar en estado REGISTERED");
        assertEquals("REGISTERED", processContext.getStatus());
        assertTrue(processContext.getInstanceNumber() > 0, "Debe tener un número de instancia asignado");
        assertTrue(processContext.getProcessIdentifier().startsWith("proceso_cre_"),
                "El identificador debe comenzar con el prefijo proceso_cre_");
        assertEquals("proceso_cre_" + processContext.getInstanceNumber(), processContext.getProcessIdentifier());
    }

    // ──────────────────────────────────────────────
    //  Helpers (replican lógica del servicio para tests)
    // ──────────────────────────────────────────────

    private ServiceInstance registerInstance(String hostInfo) {
        // Intentar reclamar INACTIVE
        ServiceInstance claimed = claimInactive(hostInfo);
        if (claimed != null) return claimed;

        // Crear nueva
        return createNew(hostInfo);
    }

    private ServiceInstance registerInstanceWithRetry(String hostInfo) {
        int maxRetries = 10;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            ServiceInstance claimed = claimInactive(hostInfo);
            if (claimed != null) return claimed;

            try {
                return createNew(hostInfo);
            } catch (DuplicateKeyException e) {
                // Reintentar
            }
        }
        throw new RuntimeException("No se pudo registrar después de " + maxRetries + " intentos");
    }

    private ServiceInstance claimInactive(String hostInfo) {
        Query query = new Query(Criteria.where("groupName").is(GROUP)
                .and("status").is(InstanceStatus.INACTIVE))
                .with(Sort.by(Sort.Direction.ASC, "instanceNumber"));

        Update update = new Update()
                .set("status", InstanceStatus.ACTIVE)
                .set("hostInfo", hostInfo)
                .set("lastHeartbeat", Instant.now())
                .set("startedAt", Instant.now())
                .unset("stoppedAt");

        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true),
                ServiceInstance.class);
    }

    private ServiceInstance createNew(String hostInfo) {
        List<Integer> usedNumbers = repository.findByGroupName(GROUP)
                .stream()
                .map(ServiceInstance::getInstanceNumber)
                .sorted()
                .toList();

        int nextNumber = 1;
        for (int used : usedNumbers) {
            if (used == nextNumber) {
                nextNumber++;
            } else if (used > nextNumber) {
                break;
            }
        }

        ServiceInstance instance = ServiceInstance.builder()
                .groupName(GROUP)
                .instanceNumber(nextNumber)
                .status(InstanceStatus.ACTIVE)
                .hostInfo(hostInfo)
                .lastHeartbeat(Instant.now())
                .startedAt(Instant.now())
                .build();

        return repository.save(instance);
    }

    private void deactivateInstance(String id) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .set("status", InstanceStatus.INACTIVE)
                .set("stoppedAt", Instant.now());
        mongoTemplate.updateFirst(query, update, ServiceInstance.class);
    }

    private int markExpiredInstances(long thresholdMs) {
        Instant expiryThreshold = Instant.now().minusMillis(thresholdMs);
        Query query = new Query(Criteria.where("groupName").is(GROUP)
                .and("status").is(InstanceStatus.ACTIVE)
                .and("lastHeartbeat").lt(expiryThreshold));
        Update update = new Update()
                .set("status", InstanceStatus.INACTIVE)
                .set("stoppedAt", Instant.now());
        return (int) mongoTemplate.updateMulti(query, update, ServiceInstance.class).getModifiedCount();
    }

    private long cleanupOldRecords(int retentionDays) {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(retentionDays));
        Query query = new Query(Criteria.where("groupName").is(GROUP)
                .and("status").is(InstanceStatus.INACTIVE)
                .and("stoppedAt").lt(cutoff));
        return mongoTemplate.remove(query, ServiceInstance.class).getDeletedCount();
    }

    private ServiceInstance insertInstance(String group, int number, String hostInfo) {
        ServiceInstance instance = ServiceInstance.builder()
                .groupName(group)
                .instanceNumber(number)
                .status(InstanceStatus.ACTIVE)
                .hostInfo(hostInfo)
                .lastHeartbeat(Instant.now())
                .startedAt(Instant.now())
                .build();
        return repository.save(instance);
    }
}

