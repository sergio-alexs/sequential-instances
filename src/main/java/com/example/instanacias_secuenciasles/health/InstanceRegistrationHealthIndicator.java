package com.example.instanacias_secuenciasles.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.example.instanacias_secuenciasles.model.InstanceStatus;
import com.example.instanacias_secuenciasles.model.ServiceInstance;
import com.example.instanacias_secuenciasles.service.InstanceRegistrationService;

import lombok.RequiredArgsConstructor;

/**
 * Custom HealthIndicator que reporta el estado de la instancia registrada
 * en /actuator/health bajo la clave "instanceRegistration".
 */
@Component
@RequiredArgsConstructor
public class InstanceRegistrationHealthIndicator implements HealthIndicator {

    private final InstanceRegistrationService registrationService;

    @Override
    public Health health() {
        ServiceInstance current = registrationService.getCurrentInstance();

        if (current == null) {
            return Health.down()
                    .withDetail("reason", "Instancia no registrada")
                    .build();
        }

        if (current.getStatus() == InstanceStatus.ACTIVE) {
            return Health.up()
                    .withDetail("groupName", current.getGroupName())
                    .withDetail("instanceNumber", current.getInstanceNumber())
                    .withDetail("processIdentifier", current.getProcessIdentifier())
                    .withDetail("podName", current.getPodName() != null ? current.getPodName() : "")
                    .withDetail("hostInfo", current.getHostInfo())
                    .withDetail("startedAt", current.getStartedAt().toString())
                    .withDetail("lastHeartbeat", current.getLastHeartbeat().toString())
                    .build();
        }

        return Health.down()
                .withDetail("groupName", current.getGroupName())
                .withDetail("instanceNumber", current.getInstanceNumber())
                .withDetail("processIdentifier", current.getProcessIdentifier() != null ? current.getProcessIdentifier() : "")
                .withDetail("status", current.getStatus().name())
                .build();
    }
}
