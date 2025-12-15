package com.qwerlty.myojbackenduserservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {RedisAutoConfiguration.class})
@MapperScan("com.qwerlty.myojbackenduserservice.mapper")
@EnableScheduling
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
//@ComponentScan("com.qwerlty")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.qwerlty.myojbackendserviceclient.service"})
public class MyojBackendUserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyojBackendUserServiceApplication.class, args);
    }

}
