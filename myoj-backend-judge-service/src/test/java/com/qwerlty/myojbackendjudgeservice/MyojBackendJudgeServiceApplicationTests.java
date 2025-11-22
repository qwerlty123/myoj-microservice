package com.qwerlty.myojbackendjudgeservice;

import com.qwerlty.myojbackendjudgeservice.judge.MyojBackendJudgeServiceApplication;
import com.qwerlty.myojbackendserviceclient.client.QuestionFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(
        classes = MyojBackendJudgeServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false",
                "codesandbox.secretKey=0123456789abcdef0123456789abcdef"
        }
)
class MyojBackendJudgeServiceApplicationTests {

    @MockBean
    private QuestionFeignClient questionFeignClient;

    @Test
    void contextLoads() {
    }

}
