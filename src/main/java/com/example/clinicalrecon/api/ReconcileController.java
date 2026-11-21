package com.example.clinicalrecon.api;

import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.QueryRepo;
import com.example.clinicalrecon.repo.ReconRepo;
import com.example.clinicalrecon.service.ReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "协调器", description = "统一字段上的事实协调：确认/冲突/缺失/待判")
public class ReconcileController {
    private final ReconcileService reconcileService;
    private final ReconRepo reconRepo;
    private final MasterDataRepo masterRepo;
    private final QueryRepo queryRepo;

    public ReconcileController(ReconcileService reconcileService, ReconRepo reconRepo,
                               MasterDataRepo masterRepo, QueryRepo queryRepo) {
        this.reconcileService = reconcileService;
        this.reconRepo = reconRepo;
        this.masterRepo = masterRepo;
        this.queryRepo = queryRepo;
    }

    @PostMapping("/reconcile/run")
    @Operation(summary = "手动触发协调", description = "全量重算协调数据集；每次运行产生新的协调序号")
    public Map<String, Object> run() {
        long seq = reconcileService.reconcile("manual:api");
        return Map.of("seq", seq, "message", "协调完成");
    }

    @GetMapping("/reconciled-facts")
    @Operation(summary = "协调数据集", description = "四态发布：CONFIRMED/CONFLICT/MISSING/PENDING，可按状态过滤")
    public List<Dtos.ReconciledFactOut> list(@RequestParam(required = false) String status) {
        return reconRepo.listRecon(status).stream().map(this::toOut).toList();
    }

    @GetMapping("/reconciled-facts/{id}/trace")
    @Operation(summary = "字段级回溯", description = "任一协调字段回溯到原始来源批次（载荷摘要/接收时间/修订）、协调规则版本、历史版本与关联质疑")
    public Dtos.TraceOut trace(@PathVariable long id) {
        var row = reconRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("协调事实不存在: " + id));
        return new Dtos.TraceOut(toOut(row), JsonMaps.camel(reconRepo.historyOf(id)),
                JsonMaps.camel(reconRepo.sourcesOf(id)), JsonMaps.camel(queryRepo.queriesOfRecon(id)));
    }

    @PostMapping("/reconciled-facts/{id}/resolve")
    @Operation(summary = "人工裁定冲突", description = "仅 CONFLICT 可裁定；裁定值在来源修订签名变化前有效，源再更正后自动失效回到证据重算")
    public Dtos.ReconciledFactOut resolve(@PathVariable long id, @Valid @RequestBody Dtos.ResolveIn in) {
        reconcileService.resolve(id, in.value(), in.rationale());
        return toOut(reconRepo.findById(id).orElseThrow());
    }

    @GetMapping("/reconcile/status")
    @Operation(summary = "协调状态总览")
    public Map<String, Object> status() {
        return Map.of("currentSeq", reconRepo.currentSeq(), "counts", reconRepo.countByStatus());
    }

    private Dtos.ReconciledFactOut toOut(ReconRepo.ReconRow r) {
        String key = r.subjectId() == null ? null : masterRepo.findSubjectById(r.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        return new Dtos.ReconciledFactOut(r.id(), r.subjectId(), key, r.visitCode(), r.fieldKey(),
                r.status(), r.confirmedValue(), r.ruleCode(), r.ruleVersion(), r.version(), r.seq(),
                r.updatedAt());
    }
}
