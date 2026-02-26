package com.qwerlty.myojbackendgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "myoj.gateway.sentinel")
public class GatewaySentinelProperties {

    /**
     * Local rules provide safe defaults. Disable them when the sentinel-nacos profile is
     * enabled because a dynamic Sentinel data source owns the rule managers in that mode.
     */
    private boolean localRulesEnabled = true;

    private Map<String, LimitRule> routes = new LinkedHashMap<>();

    private List<ApiRule> apis = new ArrayList<>();

    public boolean isLocalRulesEnabled() {
        return localRulesEnabled;
    }

    public void setLocalRulesEnabled(boolean localRulesEnabled) {
        this.localRulesEnabled = localRulesEnabled;
    }

    public Map<String, LimitRule> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, LimitRule> routes) {
        this.routes = routes;
    }

    public List<ApiRule> getApis() {
        return apis;
    }

    public void setApis(List<ApiRule> apis) {
        this.apis = apis;
    }

    public static class LimitRule {

        private double count;

        private long intervalSeconds = 1;

        private int burst;

        private boolean byClientIp;

        public double getCount() {
            return count;
        }

        public void setCount(double count) {
            this.count = count;
        }

        public long getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(long intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }

        public boolean isByClientIp() {
            return byClientIp;
        }

        public void setByClientIp(boolean byClientIp) {
            this.byClientIp = byClientIp;
        }
    }

    public static class ApiRule extends LimitRule {

        private String name;

        private List<String> paths = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }
    }
}
