package com.example.instanacias_secuenciasles.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "service_instances")
@CompoundIndex(def = "{'groupName': 1, 'instanceNumber': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceInstance {

    @Id
    private String id;

    private String groupName;

    private int instanceNumber;

    private String processIdentifier;

    private String podName;

    private InstanceStatus status;

    private String hostInfo;

    private Instant lastHeartbeat;

    private Instant startedAt;

    private Instant stoppedAt;
}
