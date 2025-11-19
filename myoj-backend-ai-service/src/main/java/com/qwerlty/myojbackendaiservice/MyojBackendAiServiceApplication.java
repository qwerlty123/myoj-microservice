package com.qwerlty.myojbackendaiservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@MapperScan("com.qwerlty.myojbackendaiservice.mapper")
public class MyojBackendAiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyojBackendAiServiceApplication.class, args);
    }
}
