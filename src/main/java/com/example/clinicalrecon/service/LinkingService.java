package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.repo.IntakeRepo;
import com.example.clinicalrecon.repo.LinkRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.QueryRepo;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 受试者/访视关联器：依据中心映射与方案访视窗口生成候选关联及置信理由。
 * 铁律：存在多条候选（如仅日期精度且窗口跨边界）时只列出候选与依据，不擅自分配，等待人工裁定。
 */
@Service
public class LinkingService {
    private final IntakeRepo intakeRepo;
    private final LinkRepo linkRepo;
    private final MasterDataRepo masterRepo;
    private final QueryRepo queryRepo;
    private final ReconcileService reconcileService;

    public LinkingService(IntakeRepo intakeRepo, LinkRepo linkRepo, MasterDataRepo masterRepo,
                          QueryRepo queryRepo, @Lazy ReconcileService reconcileService) {
        this.intakeRepo = intakeRepo;
        this.linkRepo = linkRepo;
        this.masterRepo = masterRepo;
        this.queryRepo = queryRepo;
        this.reconcileService = reconcileService;
    }

    /** 对一批新受理的有效事实生成候选并按规则自动确认 */
    @Transactional
    public void linkFacts(List<Long> rawFactIds) {
        for (long id : rawFactIds) {
            intakeRepo.findById(id).ifPresent(this::linkOne);
        }
    }

    /** 全量重跑（仍只处理未关联事实） */
    @Transactional
    public int linkAllPending() {
        var pending = intakeRepo.listRawFacts(null, true).stream()
                .filter(f -> !Enums.Link.LINKED.equals(f.linkStatus()))
                .map(IntakeRepo.RawFactRow::id).toList();
        linkFacts(pending);
        reconcileService.reconcile("link:rerun");
        return pending.size();
    }

    private record CandDraft(long subjectId, String visitCode, String confidence, String rationale) {}

    private void linkOne(IntakeRepo.RawFactRow fact) {
        if (!fact.valid() || Enums.Link.LINKED.equals(fact.linkStatus())) return;

        var subjects = masterRepo.mapSubject(fact.centerId(), fact.externalSubjectId());
        if (subjects.isEmpty()) {
            // 中心映射缺失：不产生候选，保持未关联，由协调器发布待判并开质疑
            openAmbiguityQuery(fact, "中心映射缺失: " + fact.centerId() + "/" + fact.externalSubjectId()
                    + " 无法定位统一受试者");
            return;
        }

        var cands = new ArrayList<CandDraft>();

        for (var subject : subjects) {
            String mapNote = "中心映射 " + fact.centerId() + "/" + fact.externalSubjectId()
                    + "→" + subject.subjectKey();
            if (fact.externalVisitCode() != null
                    && masterRepo.visitExists(subject.trialId(), fact.externalVisitCode())) {
                cands.add(new CandDraft(subject.id(), fact.externalVisitCode(), Enums.Confidence.HIGH,
                        mapNote + "；来源显式访视编码 " + fact.externalVisitCode() + " 命中方案访视"));
                continue;
            }
            // 依据事件时间落入方案窗口
            LocalDate eventDay = fact.eventTime() != null ? fact.eventTime().toLocalDate() : fact.eventDate();
            if (eventDay == null) {
                openAmbiguityQuery(fact, "无显式访视编码且无事件时间，无法定位访视");
                continue;
            }
            long day = ChronoUnit.DAYS.between(subject.enrolledDate(), eventDay);
            var windows = masterRepo.scheduleOf(subject.trialId()).stream()
                    .filter(w -> day >= w.windowStart() && day <= w.windowEnd()).toList();
            boolean dateOnly = Enums.Precision.DATE.equals(fact.timePrecision());
            for (var w : windows) {
                String confidence;
                String rationale;
                if (windows.size() > 1) {
                    // 窗口跨边界：只列候选，不擅自分配
                    confidence = Enums.Confidence.MEDIUM;
                    rationale = mapNote + "；事件" + (dateOnly ? "仅有日期精度(" + eventDay + ")"
                            : "时间(" + eventDay + ")") + " 距入组第 " + day + " 天，同时落在 "
                            + windows.size() + " 个访视窗口（含 " + w.visitCode() + "["
                            + w.windowStart() + "," + w.windowEnd() + "]）边界重叠区，需人工裁定";
                } else if (dateOnly) {
                    confidence = Enums.Confidence.MEDIUM;
                    rationale = mapNote + "；事件仅有日期精度(" + eventDay + ")，距入组第 " + day
                            + " 天，落在唯一窗口 " + w.visitCode() + "[" + w.windowStart() + ","
                            + w.windowEnd() + "]";
                } else {
                    confidence = Enums.Confidence.HIGH;
                    rationale = mapNote + "；事件时间 " + eventDay + " 距入组第 " + day + " 天，落在唯一窗口 "
                            + w.visitCode() + "[" + w.windowStart() + "," + w.windowEnd() + "]";
                }
                cands.add(new CandDraft(subject.id(), w.visitCode(), confidence, rationale));
            }
            if (windows.isEmpty()) {
                openAmbiguityQuery(fact, mapNote + "；事件日期 " + eventDay + "（距入组第 " + day
                        + " 天）不在任何方案访视窗口内");
            }
        }

        for (var c : cands) {
            linkRepo.insertCandidate(fact.id(), c.subjectId(), c.visitCode(), c.confidence(), c.rationale());
        }

        if (cands.size() == 1) {
            // 唯一候选（含仅日期精度但只命中单一窗口）→ 自动确认
            var c = cands.get(0);
            Long candidateId = linkRepo.candidatesOfFact(fact.id()).stream()
                    .filter(row -> row.subjectId() == c.subjectId() && row.visitCode().equals(c.visitCode()))
                    .map(LinkRepo.CandidateRow::id).findFirst().orElse(null);
            confirmInternal(fact.id(), c.subjectId(), c.visitCode(), candidateId, "auto:linker");
        } else if (cands.size() > 1) {
            intakeRepo.updateLinkStatus(fact.id(), Enums.Link.PENDING_DECISION);
            openAmbiguityQuery(fact, "存在 " + cands.size() + " 个候选访视（窗口跨边界/多候选），关联器不擅自分配，待人工裁定");
        }
    }

    /** 新修订与旧修订身份字段一致时继承已确认关联，避免纯值修订触发重复裁定 */
    @Transactional
    public void inheritLinkIfIdentityUnchanged(IntakeRepo.RawFactRow oldFact, long newFactId) {
        var oldLink = linkRepo.findLink(oldFact.id());
        if (oldLink.isEmpty()) return;
        var newFact = intakeRepo.findById(newFactId).orElseThrow();
        boolean sameIdentity = Objects.equals(oldFact.externalSubjectId(), newFact.externalSubjectId())
                && Objects.equals(oldFact.externalVisitCode(), newFact.externalVisitCode())
                && Objects.equals(oldFact.eventTime(), newFact.eventTime())
                && Objects.equals(oldFact.eventDate(), newFact.eventDate());
        if (sameIdentity) {
            linkRepo.insertFactLink(newFactId, oldLink.get().subjectId(), oldLink.get().visitCode(),
                    null, "system:inherit-rev" + newFact.sourceRevision());
            intakeRepo.updateLinkStatus(newFactId, Enums.Link.LINKED);
        }
    }

    /** 人工裁定确认某候选 */
    @Transactional
    public Dtos.LinkDecisionOut confirmCandidate(long candidateId) {
        var cand = linkRepo.findCandidate(candidateId)
                .orElseThrow(() -> new NotFoundException("候选不存在: " + candidateId));
        if (!Enums.Candidate.CANDIDATE.equals(cand.status())) {
            throw new BadRequestException("候选已裁定，状态=" + cand.status());
        }
        String user = UserContext.user();
        confirmInternal(cand.rawFactId(), cand.subjectId(), cand.visitCode(), candidateId, user);
        // 其余候选驳回
        for (var other : linkRepo.candidatesOfFact(cand.rawFactId())) {
            if (other.id() != candidateId && Enums.Candidate.CANDIDATE.equals(other.status())) {
                linkRepo.decideCandidate(other.id(), Enums.Candidate.REJECTED, user);
            }
        }
        closeAmbiguityQueries(cand.rawFactId(), user);
        reconcileService.reconcile("link:confirm candidate " + candidateId);
        return new Dtos.LinkDecisionOut(cand.rawFactId(), Enums.Link.LINKED, candidateId, "已确认关联");
    }

    /** 人工驳回某候选 */
    @Transactional
    public Dtos.LinkDecisionOut rejectCandidate(long candidateId) {
        var cand = linkRepo.findCandidate(candidateId)
                .orElseThrow(() -> new NotFoundException("候选不存在: " + candidateId));
        if (!Enums.Candidate.CANDIDATE.equals(cand.status())) {
            throw new BadRequestException("候选已裁定，状态=" + cand.status());
        }
        linkRepo.decideCandidate(candidateId, Enums.Candidate.REJECTED, UserContext.user());
        var remaining = linkRepo.candidatesOfFact(cand.rawFactId()).stream()
                .filter(c -> Enums.Candidate.CANDIDATE.equals(c.status())).count();
        if (remaining == 0) {
            intakeRepo.updateLinkStatus(cand.rawFactId(), Enums.Link.UNLINKED);
        }
        reconcileService.reconcile("link:reject candidate " + candidateId);
        return new Dtos.LinkDecisionOut(cand.rawFactId(),
                remaining == 0 ? Enums.Link.UNLINKED : Enums.Link.PENDING_DECISION, null, "已驳回候选");
    }

    private void confirmInternal(long rawFactId, long subjectId, String visitCode,
                                 Long candidateId, String decidedBy) {
        linkRepo.insertFactLink(rawFactId, subjectId, visitCode, candidateId, decidedBy);
        if (candidateId != null) {
            linkRepo.decideCandidate(candidateId, Enums.Candidate.CONFIRMED, decidedBy);
        }
        intakeRepo.updateLinkStatus(rawFactId, Enums.Link.LINKED);
    }

    private void openAmbiguityQuery(IntakeRepo.RawFactRow fact, String detail) {
        if (queryRepo.hasActiveQueryForFact(fact.id(), Enums.Issue.LINK_AMBIGUITY)) return;
        queryRepo.insertQuery(null, fact.id(), Enums.Issue.LINK_AMBIGUITY,
                "访视关联待裁定: " + fact.sourceSystem() + " " + fact.sourceRecordId(),
                detail, Enums.Role.CRA, "system:linker", null);
    }

    private void closeAmbiguityQueries(long rawFactId, String user) {
        for (var q : queryRepo.activeQueriesForFact(rawFactId, Enums.Issue.LINK_AMBIGUITY)) {
            queryRepo.close(q.id(), user + "(关联已裁定)", null);
        }
    }
}
