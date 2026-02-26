package com.qwerlty.myojbackendgateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySentinelConfigurationTest {

    static {
        System.setProperty("csp.sentinel.log.dir", System.getProperty("java.io.tmpdir"));
    }

    @AfterEach
    void clearSentinelManagers() {
        GatewayRuleManager.loadRules(Collections.emptySet());
        GatewayApiDefinitionManager.loadApiDefinitions(Collections.emptySet());
    }

    @Test
    void loadsRouteAndApiRulesFromProperties() {
        GatewaySentinelProperties properties = new GatewaySentinelProperties();

        GatewaySentinelProperties.LimitRule route = new GatewaySentinelProperties.LimitRule();
        route.setCount(25);
        route.setBurst(5);
        properties.getRoutes().put("question-route", route);

        GatewaySentinelProperties.ApiRule api = new GatewaySentinelProperties.ApiRule();
        api.setName("write-api");
        api.setPaths(Arrays.asList("/api/exact", "/api/prefix/**"));
        api.setCount(3);
        api.setIntervalSeconds(10);
        api.setByClientIp(true);
        properties.getApis().add(api);

        new GatewaySentinelConfiguration(properties).initializeLocalRules();

        assertThat(GatewayRuleManager.getRules()).hasSize(2);
        GatewayFlowRule apiFlowRule = GatewayRuleManager.getRulesForResource("write-api")
                .iterator().next();
        assertThat(apiFlowRule.getResourceMode())
                .isEqualTo(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME);
        assertThat(apiFlowRule.getParamItem().getParseStrategy())
                .isEqualTo(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_CLIENT_IP);

        ApiDefinition definition = GatewayApiDefinitionManager.getApiDefinition("write-api");
        assertThat(definition).isNotNull();
        assertThat(definition.getPredicateItems())
                .filteredOn(ApiPathPredicateItem.class::isInstance)
                .map(ApiPathPredicateItem.class::cast)
                .anySatisfy(item -> {
                    assertThat(item.getPattern()).isEqualTo("/api/prefix/");
                    assertThat(item.getMatchStrategy())
                            .isEqualTo(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX);
                });
    }
}
