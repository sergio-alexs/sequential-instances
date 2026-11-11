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

    private volatile int instanceNumber = -1;
    private volatile String processIdentifier = "unregistered";
    private volatile String groupName = "";
    private volatile String podName = "";

    public void update(int instanceNumber, String processIdentifier, String groupName, String podName) {
        this.instanceNumber = instanceNumber;
        this.processIdentifier = processIdentifier;
        this.groupName = groupName;
        this.podName = podName;
    }

    public boolean isRegistered() {
        return this.instanceNumber > 0;
    }
}

