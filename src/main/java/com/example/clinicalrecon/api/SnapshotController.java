package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.service.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots")
@Tag(name = "分析快照", description = "只消费通过质量门槛的协调结果；物化可复现")
public class SnapshotController {
    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping
    @Operation(summary = "创建分析快照", description = "按质量门槛（默认仅 CONFIRMED）物化协调结果；"
            + "CONFLICT/MISSING/PENDING 不进入快照并计入排除统计")
    public Dtos.SnapshotOut create(@Valid @RequestBody Dtos.SnapshotIn in) {
        return snapshotService.create(in);
    }

    @GetMapping
    @Operation(summary = "快照列表")
    public List<com.example.clinicalrecon.repo.SnapshotRepo.SnapshotRow> list() {
        return snapshotService.list();
    }

    @GetMapping("/{id}/rows")
    @Operation(summary = "快照行", description = "物化行永不变更；每行锚定协调事实版本，可经 /api/reconciled-facts/{id}/trace 回溯")
    public List<Map<String, Object>> rows(@PathVariable long id) {
        return JsonMaps.camel(snapshotService.rows(id));
    }

    @GetMapping("/{id}/verify")
    @Operation(summary = "复算验证", description = "以快照协调序号为界从追加式历史重放同一质量门槛，与物化行比对，证明旧快照可复现")
    public Dtos.VerifyOut verify(@PathVariable long id) {
        return snapshotService.verify(id);
    }
}
