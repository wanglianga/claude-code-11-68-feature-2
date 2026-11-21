package com.example.clinicalrecon.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("临床试验多源事实对账与质疑数据服务")
                .version("1.0.0")
                .description("""
                        对账链路：受理仓(批次/修订) → 受试者访视关联(候选+置信理由) → 事实协调(确认/冲突/缺失/待判) → 质疑闭环 → 分析快照(质量门槛)。
                        任一统计字段可经 /api/reconciled-facts/{id}/trace 回溯到原始来源批次、修订与协调规则。
                        访视窗口符合性：计划目录(方案版本/窗口/适用人群) → 事件整理 → 窗口判定(提前/按窗/延迟/未完成/无法判定/不适用)
                        → 访视汇总与执行差异分析（/api/compliance/**，只发布状态，不自动创建偏离裁决）。
                        身份：请求头 X-User / X-Role（演示账号见 README，如 dm1/DM、cra1/CRA）。
                        """));
    }
}
