package com.example.instanacias_secuenciasles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.instanacias_secuenciasles.config.InstanceConfig;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(InstanceConfig.class)
public class InstanaciasSecuenciaslesApplication {

	public static void main(String[] args) {
		SpringApplication.run(InstanaciasSecuenciaslesApplication.class, args);
	}

}
