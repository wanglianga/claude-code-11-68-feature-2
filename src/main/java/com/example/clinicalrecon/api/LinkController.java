package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.repo.LinkRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.service.LinkingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "关联器", description = "受试者/访视候选关联与人工裁定")
public class LinkController {
    private final LinkingService linkingService;
    private final LinkRepo linkRepo;
    private final MasterDataRepo masterRepo;

    public LinkController(LinkingService linkingService, LinkRepo linkRepo, MasterDataRepo masterRepo) {
        this.linkingService = linkingService;
        this.linkRepo = linkRepo;
        this.masterRepo = masterRepo;
    }

    @PostMapping("/linking/run")
    @Operation(summary = "重跑关联器", description = "处理全部未关联的有效事实；多候选不擅自分配")
    public Map<String, Object> run() {
        int n = linkingService.linkAllPending();
        return Map.of("processed", n, "message", "关联器已处理未关联事实");
    }

    @GetMapping("/link-candidates")
    @Operation(summary = "候选关联列表", description = "含置信度与置信理由；status=CANDIDATE 为待裁定")
    public List<Dtos.CandidateOut> candidates(@RequestParam(required = false) String status) {
        return linkRepo.listCandidates(status).stream().map(this::toOut).toList();
    }

    @PostMapping("/link-candidates/{id}/confirm")
    @Operation(summary = "确认候选关联", description = "人工裁定：确认该候选并驳回同事实其余候选，随后自动重协调")
    public Dtos.LinkDecisionOut confirm(@PathVariable long id) {
        return linkingService.confirmCandidate(id);
    }

    @PostMapping("/link-candidates/{id}/reject")
    @Operation(summary = "驳回候选关联")
    public Dtos.LinkDecisionOut reject(@PathVariable long id) {
        return linkingService.rejectCandidate(id);
    }

    @GetMapping("/links")
    @Operation(summary = "已确认关联列表")
    public List<Map<String, Object>> links() {
        return JsonMaps.camel(linkRepo.listLinksView());
    }

    private Dtos.CandidateOut toOut(LinkRepo.CandidateRow c) {
        String key = masterRepo.findSubjectById(c.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        return new Dtos.CandidateOut(c.id(), c.rawFactId(), c.subjectId(), key, c.visitCode(),
                c.confidence(), c.rationale(), c.status(), c.decidedBy(), c.decidedAt());
    }
}
