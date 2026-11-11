package com.example.instanacias_secuenciasles.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.instanacias_secuenciasles.model.InstanceStatus;
import com.example.instanacias_secuenciasles.model.ServiceInstance;

@Repository
public interface ServiceInstanceRepository extends MongoRepository<ServiceInstance, String> {

    List<ServiceInstance> findByGroupName(String groupName);

    List<ServiceInstance> findByGroupNameAndStatus(String groupName, InstanceStatus status);
}

