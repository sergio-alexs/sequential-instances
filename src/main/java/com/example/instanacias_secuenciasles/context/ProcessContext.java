package com.example.instanacias_secuenciasles.context;

import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.ToString;

/**
 * Bean inyectable en cualquier @Service o @Component para acceder
 * al número de instancia y al identificador de proceso (ej. proceso_cre_1).
 */
@Component
@Getter
@ToString
public class ProcessContext {

    private volatile String status = "PENDING_REGISTRATION";
    private volatile int instanceNumber = -1;
    private volatile String processIdentifier = "unregistered";
    private volatile String groupName = "";
    private volatile String podName = "";

    public void update(int instanceNumber, String processIdentifier, String groupName, String podName) {
        this.status = "REGISTERED";
        this.instanceNumber = instanceNumber;
        this.processIdentifier = processIdentifier;
        this.groupName = groupName;
        this.podName = podName;
    }

    public void markDeregistered() {
        this.status = "DEREGISTERED";
    }

    public boolean isRegistered() {
        return "REGISTERED".equals(this.status) && this.instanceNumber > 0;
    }
}

