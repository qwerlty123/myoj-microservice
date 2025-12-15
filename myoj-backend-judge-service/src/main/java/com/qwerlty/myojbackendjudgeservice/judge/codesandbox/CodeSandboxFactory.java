package com.qwerlty.myojbackendjudgeservice.judge.codesandbox;

import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.impl.ExampleCodeSandbox;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.impl.RemoteCodeSandbox;
import com.qwerlty.myojbackendjudgeservice.judge.codesandbox.impl.ThirdPartyCodeSandbox;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 代码沙箱工厂，remote 类型从 Spring 容器获取以支持配置注入
 */
@Component
public class CodeSandboxFactory {

    @Resource
    private ApplicationContext applicationContext;

    public CodeSandbox newInstance(String type) {
        switch (type) {
            case "example":
                return new ExampleCodeSandbox();
            case "remote":
                return applicationContext.getBean(RemoteCodeSandbox.class);
            case "thirdParty":
                return new ThirdPartyCodeSandbox();
            default:
                return new ExampleCodeSandbox();
        }
    }
}
