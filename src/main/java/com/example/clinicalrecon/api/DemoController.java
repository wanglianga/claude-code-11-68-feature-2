package com.example.clinicalrecon.api;

import com.example.clinicalrecon.service.DemoSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@Tag(name = "演示", description = "演示种子与重置")
public class DemoController {
    private final DemoSeedService demoSeedService;

    public DemoController(DemoSeedService demoSeedService) {
        this.demoSeedService = demoSeedService;
    }

    @PostMapping("/seed")
    @Operation(summary = "重置并装载演示数据", description = "幂等：清空后重建主数据并受理 5 个样例批次"
            + "（CRF×2/检验/影像/监查；lab-batch-2 检验更正修订留给验收流程手工上送）；"
            + "并装载访视窗口符合性计划目录(v1.0/v2.0)与 34 条活动事件"
            + "（提前采样/跨窗分次完成/迟报/方案修订/给药中断/无法判定）")
    public Map<String, Object> seed() {
        return demoSeedService.seed();
    }

    @PostMapping("/reset")
    @Operation(summary = "清空全部数据")
    public Map<String, Object> reset() {
        demoSeedService.reset();
        return Map.of("message", "已清空");
    }
}
