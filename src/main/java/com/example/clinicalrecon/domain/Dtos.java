package com.example.clinicalrecon.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** API 请求/响应载体（record 集合）。 */
public final class Dtos {
    private Dtos() {}

    // ---------- 受理 ----------
    public record FactIn(
            @NotBlank String sourceRecordId,
            @NotNull Integer sourceRevision,
            @NotBlank String externalSubjectId,
            String externalVisitCode,
            @NotBlank String sourceFieldCode,
            String valueText,
            BigDecimal valueNum,
            String unit,
            OffsetDateTime eventTime,
            LocalDate eventDate,
            @NotBlank String timePrecision,
            String payloadSummary
    ) {}

    public record BatchIn(
            @NotBlank String sourceSystem,
            @NotBlank String centerId,
            @NotBlank String batchLabel,
            OffsetDateTime receivedAt,
            @NotNull List<FactIn> facts
    ) {}

    public record BatchOut(
            long batchId, String sourceSystem, String centerId, String batchLabel,
            String payloadDigest, OffsetDateTime receivedAt, int factCount,
            int mapped, int unmapped, int superseded, int stale,
            String message
    ) {}

    // ---------- 关联 ----------
    public record CandidateOut(
            long id, long rawFactId, long subjectId, String subjectKey, String visitCode,
            String confidence, String rationale, String status,
            String decidedBy, OffsetDateTime decidedAt
    ) {}

    public record LinkDecisionOut(long rawFactId, String linkStatus, Long confirmedCandidateId, String message) {}

    // ---------- 协调 ----------
    public record ReconciledFactOut(
            long id, Long subjectId, String subjectKey, String visitCode, String fieldKey,
            String status, String confirmedValue, String ruleCode, String ruleVersion,
            int version, long seq, OffsetDateTime updatedAt
    ) {}

    public record ResolveIn(@NotBlank String value, String rationale) {}

    public record TraceOut(
            ReconciledFactOut fact,
            List<Map<String, Object>> history,
            List<Map<String, Object>> sources,
            List<Map<String, Object>> queries
    ) {}

    // ---------- 质疑 ----------
    public record QueryOut(
            long id, Long reconciledFactId, Long rawFactId, String issueType,
            String title, String detail, String assigneeRole, String status,
            OffsetDateTime openedAt, String openedBy,
            OffsetDateTime closedAt, String closedBy, Integer lastKnownFactVersion
    ) {}

    public record ResponseIn(@NotBlank String responseText, String evidenceRef) {}

    public record ResponseOut(
            long id, long queryId, String responder, String responderRole,
            String responseText, String evidenceRef,
            Integer factVersionAtReply, String sourceSigAtReply, OffsetDateTime createdAt
    ) {}

    public record CloseIn(String resolution) {}

    // ---------- 快照 ----------
    public record SnapshotIn(
            @NotBlank String name,
            List<String> allowedStatuses,   // 默认 ["CONFIRMED"]
            List<String> requiredFields     // 仅用于摘要评估
    ) {}

    public record SnapshotOut(
            long id, String name, OffsetDateTime createdAt, String createdBy,
            Map<String, Object> gate, long seq, int rowCount, Map<String, Object> excluded
    ) {}

    public record SnapshotRowOut(
            long id, Long subjectId, String subjectKey, String visitCode, String fieldKey,
            String value, long reconciledFactId, int reconciledVersion, String ruleCode
    ) {}

    public record VerifyOut(
            long snapshotId, boolean reproducible,
            List<Long> missingInReplay, List<Long> extraInReplay, String message
    ) {}

    // ---------- 访视窗口符合性 ----------
    public record ComplianceEventIn(
            @NotBlank String eventUid,
            @NotBlank String centerId,
            @NotBlank String externalSubjectId,
            @NotBlank String activityType,      // ASSESSMENT / SAMPLING / IMAGING / DOSING
            @NotBlank String activityCode,
            OffsetDateTime occurredAt,
            LocalDate occurredDate,
            String timePrecision,               // 默认 DATE
            OffsetDateTime reportedAt,          // 结果回报时间(采样类; 数据时效另记)
            String visitCode,                   // 来源显式访视编码(可空)
            Boolean dosingInterrupted,          // 给药中断标记
            String sourceRef
    ) {}

    public record ComplianceEventBatchIn(@NotNull List<@Valid ComplianceEventIn> events) {}

    // ---------- 通用 ----------
    public record ErrorOut(String error, String message) {}
}
