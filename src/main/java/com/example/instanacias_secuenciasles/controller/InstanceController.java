package com.example.instanacias_secuenciasles.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.instanacias_secuenciasles.context.ProcessContext;
import com.example.instanacias_secuenciasles.model.ServiceInstance;
import com.example.instanacias_secuenciasles.service.InstanceRegistrationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/instances")
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceRegistrationService registrationService;
    private final ProcessContext processContext;

    @GetMapping
    public List<ServiceInstance> getInstances() {
        return registrationService.getInstancesForGroup();
    }

    @GetMapping("/all")
    public List<ServiceInstance> getAllInstances() {
        return registrationService.getAllInstances();
    }

    @GetMapping("/me")
    public ServiceInstance getCurrentInstance() {
        return registrationService.getCurrentInstance();
    }

    @GetMapping("/context")
    public ProcessContext getProcessContext() {
        return processContext;
    }
}

