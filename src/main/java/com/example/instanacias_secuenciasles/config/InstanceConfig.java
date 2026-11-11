package com.example.instanacias_secuenciasles.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@ConfigurationProperties(prefix = "instance")
@Data
public class InstanceConfig {

    /**
     * Tiempo en milisegundos sin heartbeat para considerar una instancia muerta (45s).
     */
    private long expiryThresholdMs = 45000;

    /**
     * Máximo de reintentos al registrar una instancia (manejo de concurrencia).
     */
    private int maxRegistrationRetries = 10;

    /**
     * Prefijo para el identificador de proceso (ej. proceso_cre_1).
     */
    private String processPrefix = "proceso_cre_";

    /**
     * Tiempo en días para considerar un registro INACTIVE como obsoleto y eliminarlo.
     */
    private int cleanupRetentionDays = 7;
}

