package com.example.clinicalrecon.api;

import com.example.clinicalrecon.repo.MasterDataRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "主数据", description = "受试者/中心映射/访视窗口/字段目录")
public class CatalogController {
    private final MasterDataRepo masterRepo;

    public CatalogController(MasterDataRepo masterRepo) {
        this.masterRepo = masterRepo;
    }

    @GetMapping("/subjects")
    @Operation(summary = "受试者主档")
    public List<Map<String, Object>> subjects() {
        return JsonMaps.camel(masterRepo.listSubjects());
    }

    @GetMapping("/center-map")
    @Operation(summary = "中心映射", description = "来源中心+外部受试者号 → 统一受试者")
    public List<Map<String, Object>> centerMap() {
        return JsonMaps.camel(masterRepo.listCenterMap());
    }

    @GetMapping("/visit-schedule")
    @Operation(summary = "方案访视窗口")
    public List<Map<String, Object>> schedule() {
        return JsonMaps.camel(masterRepo.listSchedule());
    }

    @GetMapping("/field-catalog")
    @Operation(summary = "统一字段目录", description = "统一字段键 ← 各来源字段编码；目录外字段保持待判")
    public List<MasterDataRepo.FieldDef> fieldCatalog() {
        return masterRepo.fieldCatalog();
    }
}
