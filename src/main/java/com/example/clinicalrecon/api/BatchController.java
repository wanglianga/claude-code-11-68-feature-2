package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.repo.IntakeRepo;
import com.example.clinicalrecon.service.IntakeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "受理仓", description = "来源批次受理与原始事实查询")
public class BatchController {
    private final IntakeService intakeService;
    private final IntakeRepo intakeRepo;

    public BatchController(IntakeService intakeService, IntakeRepo intakeRepo) {
        this.intakeService = intakeService;
        this.intakeRepo = intakeRepo;
    }

    @PostMapping("/batches")
    @Operation(summary = "受理来源批次", description = "按中心批次接收 CRF/LAB/IMAGING/MONITORING 载荷；"
            + "保留载荷摘要、事件时间、接收时间与来源修订；批次幂等；受理后自动执行关联与协调")
    public Dtos.BatchOut ingest(@Valid @RequestBody Dtos.BatchIn req) {
        return intakeService.ingest(req);
    }

    @GetMapping("/batches")
    @Operation(summary = "批次列表")
    public List<Map<String, Object>> batches() {
        return JsonMaps.camel(intakeRepo.listBatches());
    }

    @GetMapping("/raw-facts")
    @Operation(summary = "原始事实查询", description = "可按来源系统与有效性过滤；含修订取代关系")
    public List<IntakeRepo.RawFactRow> rawFacts(@RequestParam(required = false) String sourceSystem,
                                                @RequestParam(required = false, defaultValue = "false") boolean validOnly) {
        return intakeRepo.listRawFacts(sourceSystem, validOnly);
    }
}
