package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.ConflictException;
import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.repo.IntakeRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.QueryRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 受理仓：按中心批次接收各来源载荷，保留载荷摘要、事件时间、接收时间与来源修订。
 * 专用适配器策略：每类来源按自己的字段编码上送，受理时经字段目录映射到共同事件模型；
 * 目录外的字段不丢弃、不猜测，标记 UNMAPPED 保持待判。
 */
@Service
public class IntakeService {
    private final IntakeRepo intakeRepo;
    private final MasterDataRepo masterRepo;
    private final QueryRepo queryRepo;
    private final LinkingService linkingService;
    private final ReconcileService reconcileService;
    private final ObjectMapper om;

    public IntakeService(IntakeRepo intakeRepo, MasterDataRepo masterRepo, QueryRepo queryRepo,
                         LinkingService linkingService, ReconcileService reconcileService,
                         ObjectMapper om) {
        this.intakeRepo = intakeRepo;
        this.masterRepo = masterRepo;
        this.queryRepo = queryRepo;
        this.linkingService = linkingService;
        this.reconcileService = reconcileService;
        this.om = om;
    }

    @Transactional
    public Dtos.BatchOut ingest(Dtos.BatchIn req) {
        if (!Enums.Source.ALL.contains(req.sourceSystem())) {
            throw new BadRequestException("未知来源系统: " + req.sourceSystem() + "，支持 " + Enums.Source.ALL);
        }
        if (intakeRepo.findBatch(req.sourceSystem(), req.centerId(), req.batchLabel()).isPresent()) {
            throw new ConflictException("批次已受理: " + req.sourceSystem() + "/" + req.centerId()
                    + "/" + req.batchLabel() + "（批次幂等，重复上送不重复入库）");
        }
        String digest = sha256(canonical(req));
        long batchId = intakeRepo.insertBatch(req.sourceSystem(), req.centerId(), req.batchLabel(),
                req.receivedAt(), digest);

        var catalog = masterRepo.fieldCatalog();
        int mapped = 0, unmapped = 0, superseded = 0, stale = 0;
        List<Long> newValidFactIds = new ArrayList<>();

        for (Dtos.FactIn in : req.facts()) {
            validateFact(in);
            // 字段目录映射：来源字段编码 -> 统一字段键
            String fieldKey = null;
            for (var def : catalog) {
                var codes = def.sourceFieldMap().getOrDefault(req.sourceSystem(), List.of());
                if (codes.contains(in.sourceFieldCode())) { fieldKey = def.fieldKey(); break; }
            }
            String mappingStatus = fieldKey != null ? Enums.Mapping.MAPPED : Enums.Mapping.UNMAPPED;

            // 修订归并：同一来源记录号按修订号取舍，不按接收先后覆盖
            var existing = intakeRepo.findByRecord(req.sourceSystem(), in.sourceRecordId());
            IntakeRepo.RawFactRow predecessor = existing.stream()
                    .filter(IntakeRepo.RawFactRow::valid).findFirst().orElse(null);

            boolean valid = true;
            String invalidReason = null;
            if (predecessor != null && in.sourceRevision() <= predecessor.sourceRevision()) {
                valid = false;
                invalidReason = "STALE_REVISION(已存在 rev" + predecessor.sourceRevision() + " >= 本次 rev"
                        + in.sourceRevision() + ")";
                stale++;
            }

            var row = new IntakeRepo.RawFactRow(0, batchId, req.sourceSystem(), in.sourceRecordId(),
                    in.sourceRevision(), req.centerId(), in.externalSubjectId(), in.externalVisitCode(),
                    in.sourceFieldCode(), fieldKey, mappingStatus,
                    in.valueText(), in.valueNum(), in.unit(), in.eventTime(), in.eventDate(),
                    in.timePrecision(), summaryOf(req, in), null, valid, invalidReason, null,
                    Enums.Link.UNLINKED);
            long newId = intakeRepo.insertRawFact(row);

            if (valid && predecessor != null) {
                // 新修订取代旧修订：旧修订失效但保留修订关系（superseded_by）
                intakeRepo.markSuperseded(predecessor.id(), newId, in.sourceRevision());
                superseded++;
                linkingService.inheritLinkIfIdentityUnchanged(predecessor, newId);
            }
            if (valid) {
                if (mappingStatus.equals(Enums.Mapping.MAPPED)) mapped++; else unmapped++;
                newValidFactIds.add(newId);
                // 监查发现类事实：受理即生成质疑，责任角色 CRA
                if (Enums.Source.MONITORING.equals(req.sourceSystem())) {
                    openMonitoringQuery(newId, req, in);
                }
            }
        }
        intakeRepo.updateBatchCount(batchId, req.facts().size());

        // 受理后自动跑关联器与协调器，保证下游状态即时一致
        linkingService.linkFacts(newValidFactIds);
        reconcileService.reconcile("intake:" + req.sourceSystem() + "/" + req.batchLabel());

        return new Dtos.BatchOut(batchId, req.sourceSystem(), req.centerId(), req.batchLabel(), digest,
                null, req.facts().size(), mapped, unmapped, superseded, stale,
                "批次已受理并完成关联与协调");
    }

    private void openMonitoringQuery(long rawFactId, Dtos.BatchIn req, Dtos.FactIn in) {
        if (queryRepo.hasActiveQueryForFact(rawFactId, Enums.Issue.MONITORING_FINDING)) return;
        queryRepo.insertQuery(null, rawFactId, Enums.Issue.MONITORING_FINDING,
                "监查发现: " + in.sourceFieldCode() + " / 受试者 " + in.externalSubjectId(),
                "来源批次 " + req.batchLabel() + "，内容: " + summaryOf(req, in),
                Enums.Role.CRA, "system:intake", null);
    }

    static void validateFact(Dtos.FactIn in) {
        if (!Enums.Precision.ALL.contains(in.timePrecision())) {
            throw new BadRequestException("timePrecision 须为 DATETIME 或 DATE: " + in.sourceRecordId());
        }
        if (Enums.Precision.DATETIME.equals(in.timePrecision()) && in.eventTime() == null) {
            throw new BadRequestException("DATETIME 精度必须提供 eventTime: " + in.sourceRecordId());
        }
        if (Enums.Precision.DATE.equals(in.timePrecision()) && in.eventDate() == null) {
            throw new BadRequestException("DATE 精度必须提供 eventDate: " + in.sourceRecordId());
        }
        if (in.valueNum() == null && (in.valueText() == null || in.valueText().isBlank())) {
            throw new BadRequestException("事实必须携带 valueNum 或 valueText: " + in.sourceRecordId());
        }
    }

    static String summaryOf(Dtos.BatchIn req, Dtos.FactIn in) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.sourceSystem()).append(' ').append(in.sourceRecordId())
          .append("#rev").append(in.sourceRevision())
          .append(" 受试者=").append(in.externalSubjectId());
        if (in.externalVisitCode() != null) sb.append(" 访视=").append(in.externalVisitCode());
        sb.append(" 字段=").append(in.sourceFieldCode()).append(" 值=");
        sb.append(in.valueNum() != null ? in.valueNum().stripTrailingZeros().toPlainString()
                + (in.unit() != null ? in.unit() : "") : in.valueText());
        if (in.eventTime() != null) sb.append(" 事件时间=").append(in.eventTime());
        if (in.eventDate() != null) sb.append(" 事件日期=").append(in.eventDate());
        return sb.toString();
    }

    private String canonical(Dtos.BatchIn req) {
        try {
            return om.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
