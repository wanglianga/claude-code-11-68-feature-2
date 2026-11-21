package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.repo.ReconRepo;
import com.example.clinicalrecon.repo.SnapshotRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分析快照：只消费通过质量门槛（allowedStatuses）的协调结果，物化行永不变更；
 * verify 依据协调序号从追加式历史重放，证明旧快照可复算复现。
 */
@Service
public class SnapshotService {
    private final SnapshotRepo snapshotRepo;
    private final ReconRepo reconRepo;
    private final ObjectMapper om = new ObjectMapper();

    private static final Set<String> VALID_STATUSES = Set.of(
            Enums.Recon.CONFIRMED, Enums.Recon.CONFLICT, Enums.Recon.MISSING, Enums.Recon.PENDING);

    public SnapshotService(SnapshotRepo snapshotRepo, ReconRepo reconRepo) {
        this.snapshotRepo = snapshotRepo;
        this.reconRepo = reconRepo;
    }

    @Transactional
    public Dtos.SnapshotOut create(Dtos.SnapshotIn in) {
        List<String> allowed = in.allowedStatuses() == null || in.allowedStatuses().isEmpty()
                ? List.of(Enums.Recon.CONFIRMED) : in.allowedStatuses();
        for (var s : allowed) {
            if (!VALID_STATUSES.contains(s)) {
                throw new BadRequestException("非法质量门槛状态: " + s + "，可选 " + VALID_STATUSES);
            }
        }
        long seq = reconRepo.currentSeq();
        var all = reconRepo.allRecon();

        var included = all.stream().filter(r -> allowed.contains(r.status())).toList();
        var excluded = new LinkedHashMap<String, Integer>();
        for (var r : all) {
            if (!allowed.contains(r.status())) excluded.merge(r.status(), 1, Integer::sum);
        }

        var gate = new LinkedHashMap<String, Object>();
        gate.put("allowedStatuses", allowed);
        gate.put("requiredFields", in.requiredFields() == null ? List.of() : in.requiredFields());

        long snapshotId;
        try {
            snapshotId = snapshotRepo.insertSnapshot(in.name(), UserContext.user(),
                    om.writeValueAsString(gate), seq, included.size(), om.writeValueAsString(excluded));
        } catch (Exception e) {
            throw new BadRequestException("快照创建失败（名称可能重复）: " + e.getMessage());
        }
        for (var r : included) {
            snapshotRepo.insertRow(snapshotId, r.subjectId(), r.visitCode(), r.fieldKey(),
                    r.confirmedValue(), r.id(), r.version(), r.ruleCode());
        }
        return new Dtos.SnapshotOut(snapshotId, in.name(), null, UserContext.user(),
                gate, seq, included.size(), Map.copyOf(excluded));
    }

    public List<SnapshotRepo.SnapshotRow> list() {
        return snapshotRepo.list();
    }

    public SnapshotRepo.SnapshotRow get(long id) {
        return snapshotRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("快照不存在: " + id));
    }

    public List<Map<String, Object>> rows(long id) {
        get(id);
        return snapshotRepo.rowsOf(id);
    }

    /**
     * 复算验证：以快照记录的协调序号为界，从追加式历史重放同一质量门槛，
     * 与物化行逐条比对。reproducible=true 证明旧快照可复现。
     */
    @SuppressWarnings("unchecked")
    public Dtos.VerifyOut verify(long id) {
        var snap = get(id);
        Map<String, Object> gate;
        try {
            gate = om.readValue(snap.gateJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var allowed = new HashSet<>((List<String>) gate.get("allowedStatuses"));

        // 重放：截至 snap.seq 每条的最新历史版本
        Set<String> replay = new HashSet<>();
        for (var h : reconRepo.historyAsOf(snap.seq())) {
            if (allowed.contains((String) h.get("status"))) {
                replay.add(h.get("reconciled_fact_id") + "@" + h.get("version"));
            }
        }
        // 物化行
        Set<String> materialized = new HashSet<>();
        Map<Long, Integer> rowVersion = new LinkedHashMap<>();
        for (var r : snapshotRepo.rowsOf(id)) {
            long reconId = ((Number) r.get("reconciled_fact_id")).longValue();
            int ver = ((Number) r.get("reconciled_version")).intValue();
            materialized.add(reconId + "@" + ver);
            rowVersion.put(reconId, ver);
        }

        var missingInReplay = new ArrayList<Long>();
        var extraInReplay = new ArrayList<Long>();
        for (var key : materialized) {
            if (!replay.contains(key)) missingInReplay.add(Long.parseLong(key.split("@")[0]));
        }
        for (var key : replay) {
            if (!materialized.contains(key)) extraInReplay.add(Long.parseLong(key.split("@")[0]));
        }
        boolean ok = missingInReplay.isEmpty() && extraInReplay.isEmpty();
        return new Dtos.VerifyOut(id, ok, missingInReplay, extraInReplay,
                ok ? "复算一致：按协调序号 " + snap.seq() + " 重放质量门槛得到的结果与物化行完全相同"
                        : "复算不一致，请检查历史数据");
    }
}
