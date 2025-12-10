package com.qwerlty.myojbackendaiservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class GenerationProductionConfigurationGuard {
    public GenerationProductionConfigurationGuard(
            Environment environment,
            @Value("${myoj.ai.generation.public-enabled:false}") boolean publicEnabled,
            @Value("${myoj.ai.generation.model-concurrency.public:0}") int publicModelConcurrency,
            @Value("${myoj.ai.generation.model-concurrency.review:0}") int reviewModelConcurrency,
            @Value("${myoj.ai.generation.cost.public-daily-budget-micros:0}") long publicDailyBudget,
            @Value("${myoj.ai.generation.cost.review-daily-budget-micros:0}") long reviewDailyBudget,
            @Value("${myoj.ai.generation.cost.input-price-micros-per-million:0}") long inputPrice,
            @Value("${myoj.ai.generation.cost.output-price-micros-per-million:0}") long outputPrice) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && (publicModelConcurrency <= 0 || reviewModelConcurrency <= 0
                || publicDailyBudget <= 0 || reviewDailyBudget <= 0
                || inputPrice <= 0 || outputPrice <= 0)) {
            throw new IllegalStateException("生产 AI 创作必须配置公共/质检模型并发、每日预算与输入/输出价格");
        }
        if (publicEnabled && (publicModelConcurrency <= 0 || publicDailyBudget <= 0
                || reviewModelConcurrency <= 0 || reviewDailyBudget <= 0
                || inputPrice <= 0 || outputPrice <= 0)) {
            throw new IllegalStateException("公开 AI 出题前必须配置模型并发、每日预算与输入/输出价格");
        }
    }
}
