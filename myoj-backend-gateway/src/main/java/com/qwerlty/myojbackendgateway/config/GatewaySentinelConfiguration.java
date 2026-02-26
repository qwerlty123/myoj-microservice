package com.qwerlty.myojbackendgateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.qwerlty.myojbackendcommon.common.ErrorCode;
import com.qwerlty.myojbackendcommon.common.ResultUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(GatewaySentinelProperties.class)
public class GatewaySentinelConfiguration implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(GatewaySentinelConfiguration.class);

    private final GatewaySentinelProperties properties;

    public GatewaySentinelConfiguration(GatewaySentinelProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        initializeLocalRules();
    }

    public void initializeLocalRules() {
        if (!properties.isLocalRulesEnabled()) {
            log.info("Sentinel local gateway rules are disabled; waiting for a dynamic data source");
            return;
        }

        Set<GatewayFlowRule> flowRules = new HashSet<>();
        for (Map.Entry<String, GatewaySentinelProperties.LimitRule> entry
                : properties.getRoutes().entrySet()) {
            validateLimit(entry.getKey(), entry.getValue());
            flowRules.add(toFlowRule(entry.getKey(),
                    SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID, entry.getValue()));
        }

        Set<ApiDefinition> apiDefinitions = new HashSet<>();
        Set<String> apiNames = new HashSet<>();
        for (GatewaySentinelProperties.ApiRule api : properties.getApis()) {
            if (!StringUtils.hasText(api.getName()) || api.getPaths() == null || api.getPaths().isEmpty()) {
                throw new IllegalStateException("Sentinel API rule requires a name and at least one path");
            }
            if (!apiNames.add(api.getName())) {
                throw new IllegalStateException("Duplicate Sentinel API rule: " + api.getName());
            }
            validateLimit(api.getName(), api);

            Set<ApiPredicateItem> predicates = new HashSet<>();
            for (String path : api.getPaths()) {
                if (!StringUtils.hasText(path)) {
                    throw new IllegalStateException("Sentinel API rule path must not be blank: " + api.getName());
                }
                boolean prefix = path.endsWith("/**");
                String pattern = prefix ? path.substring(0, path.length() - 2) : path;
                predicates.add(new ApiPathPredicateItem()
                        .setPattern(pattern)
                        .setMatchStrategy(prefix
                                ? SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX
                                : SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT));
            }
            apiDefinitions.add(new ApiDefinition(api.getName()).setPredicateItems(predicates));
            flowRules.add(toFlowRule(api.getName(),
                    SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME, api));
        }

        GatewayApiDefinitionManager.loadApiDefinitions(apiDefinitions);
        GatewayRuleManager.loadRules(flowRules);
        log.info("Loaded {} Sentinel gateway flow rules and {} API groups",
                flowRules.size(), apiDefinitions.size());
    }

    @Bean
    public BlockRequestHandler sentinelBlockRequestHandler() {
        return (exchange, throwable) -> ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.RETRY_AFTER, "1")
                .header("X-Request-Id", exchange.getRequest().getId())
                .body(BodyInserters.fromValue(
                        ResultUtils.error(ErrorCode.TOO_MANY_REQUEST, "请求过于频繁，请稍后重试")));
    }

    private static GatewayFlowRule toFlowRule(String resource, int resourceMode,
                                               GatewaySentinelProperties.LimitRule source) {
        GatewayFlowRule rule = new GatewayFlowRule(resource)
                .setResourceMode(resourceMode)
                .setCount(source.getCount())
                .setIntervalSec(source.getIntervalSeconds())
                .setBurst(source.getBurst());
        if (source.isByClientIp()) {
            rule.setParamItem(new GatewayParamFlowItem()
                    .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_CLIENT_IP));
        }
        return rule;
    }

    private static void validateLimit(String resource, GatewaySentinelProperties.LimitRule rule) {
        if (!StringUtils.hasText(resource) || rule == null || rule.getCount() <= 0
                || rule.getIntervalSeconds() <= 0 || rule.getBurst() < 0) {
            throw new IllegalStateException("Invalid Sentinel gateway rule: " + resource);
        }
    }
}
