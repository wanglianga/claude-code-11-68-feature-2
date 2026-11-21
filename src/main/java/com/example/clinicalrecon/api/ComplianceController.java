package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.repo.ComplianceRepo;
import com.example.clinicalrecon.service.ComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/compliance")
@Tag(name = "访视窗口符合性", description = "计划目录(方案版本/窗口/适用人群) → 事件整理 → 窗口判定 → 访视汇总 → 执行差异分析；"
        + "只发布状态与偏离天数，不自动创建偏离裁决")
public class ComplianceController {
    private final ComplianceService complianceService;
    private final ComplianceRepo complianceRepo;

    public ComplianceController(ComplianceService complianceService, ComplianceRepo complianceRepo) {
        this.complianceService = complianceService;
        this.complianceRepo = complianceRepo;
    }

    @GetMapping("/plan")
    @Operation(summary = "计划目录", description = "方案版本、名义访视日、各项检查允许窗口及适用人群")
    public List<Map<String, Object>> plan() {
        return complianceService.planCatalog();
    }

    @PostMapping("/events")
    @Operation(summary = "上送活动事件", description = "问诊/采样/影像采集/给药的实际发生时间；eventUid 幂等；"
            + "受理后自动执行事件整理与窗口判定")
    public Map<String, Object> ingestEvents(@Valid @RequestBody Dtos.ComplianceEventBatchIn req) {
        return complianceService.ingestEvents(req);
    }

    @GetMapping("/events")
    @Operation(summary = "活动事件查询", description = "含关联状态与歧义候选；未映射/缺时间/远离窗口的事件完整保留")
    public List<Map<String, Object>> events() {
        return complianceService.listEvents();
    }

    @PostMapping("/run")
    @Operation(summary = "重算窗口判定", description = "全量重建逐项判定与访视汇总（派生数据，可复算）")
    public Map<String, Object> run() {
        return complianceService.recalculate("manual:run");
    }

    @GetMapping("/activities")
    @Operation(summary = "活动级判定", description = "每项活动的 提前/按窗/延迟/未完成/无法判定/不适用 状态、"
            + "偏离天数、适用方案版本与窗口；可按受试者/中心/访视/状态/活动类型过滤")
    public List<Map<String, Object>> activities(@RequestParam(required = false) String subjectKey,
                                                @RequestParam(required = false) String centerId,
                                                @RequestParam(required = false) String visitCode,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String activityType) {
        return JsonMaps.camel(complianceRepo.listJudgments(subjectKey, centerId, visitCode, status, activityType));
    }

    @GetMapping("/visits")
    @Operation(summary = "访视级汇总", description = "访视状态、分母(适用的必要活动数)、各状态计数与最大偏离天数")
    public List<Map<String, Object>> visits(@RequestParam(required = false) String subjectKey,
                                            @RequestParam(required = false) String centerId,
                                            @RequestParam(required = false) String status) {
        return JsonMaps.camel(complianceRepo.listSummaries(subjectKey, centerId, status));
    }

    @GetMapping("/analysis")
    @Operation(summary = "执行差异分析", description = "研究团队按中心/访视/检查类型(可组合 groupBy=centerId,visitCode,activityType)"
            + " 统计各状态计数、分母与最大偏离天数；仅统计分析，不自动创建偏离裁决")
    public List<Map<String, Object>> analysis(@RequestParam(required = false) String groupBy) {
        return JsonMaps.camel(complianceService.analysis(groupBy));
    }
}
