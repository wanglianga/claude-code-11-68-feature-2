package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.repo.ComplianceRepo;
import com.example.clinicalrecon.repo.ComplianceRepo.EventRow;
import com.example.clinicalrecon.repo.ComplianceRepo.PlanActivityRow;
import com.example.clinicalrecon.repo.ComplianceRepo.PlanVisitRow;
import com.example.clinicalrecon.repo.ComplianceRepo.VersionRow;
import com.example.clinicalrecon.repo.MasterDataRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 方案访视窗口符合性：
 *  事件整理器 —— 将问诊/采样/影像采集/给药的实际发生时间关联到受试者及访视实例；
 *  窗口计算器 —— 按当时适用方案版本逐项判定 提前/按窗/延迟/未完成/无法判定/不适用，并汇总访视级结果。
 *
 * 建模取舍（锚点优先）：先由方案锚点（入组日+名义访视日）定位访视实例，再在实例内按每项活动
 * 自己的偏移与窗口逐项判定。锚点（入组日）在本数据集中始终可用，关联稳定、便于按中心/访视/
 * 检查类型批量分析；分次完成由"每项活动独立判定"完整保留。锚点缺失/等距歧义时保留候选或置
 * 无法判定，绝不把其余活动直接算完成。判定结果为派生数据，recalculate 全量重建可复算；
 * 只发布状态与偏离天数，不自动创建偏离裁决。
 */
@Service
public class ComplianceService {
    private final ComplianceRepo repo;
    private final MasterDataRepo masterRepo;
    private final String ruleVersion;
    private final String asOfConfig;
    private final int maxLinkDistanceDays;

    public ComplianceService(ComplianceRepo repo, MasterDataRepo masterRepo,
                             @Value("${app.compliance.rule-version}") String ruleVersion,
                             @Value("${app.compliance.as-of:}") String asOfConfig,
                             @Value("${app.compliance.max-link-distance-days:45}") int maxLinkDistanceDays) {
        this.repo = repo;
        this.masterRepo = masterRepo;
        this.ruleVersion = ruleVersion;
        this.asOfConfig = asOfConfig;
        this.maxLinkDistanceDays = maxLinkDistanceDays;
    }

    // ---------- 计划目录（内存模型） ----------
    private record PlanVisit(String visitCode, int nominalDay, List<PlanActivityRow> activities) {}
    private record PlanVersion(VersionRow version, Map<String, PlanVisit> visits) {}
    private record LinkOutcome(Long subjectId, String visitCode, String status) {}

    private List<PlanVersion> loadPlan() {
        var visitsByVersion = new LinkedHashMap<Long, List<PlanVisitRow>>();
        for (var v : repo.listPlanVisits()) visitsByVersion.computeIfAbsent(v.versionId(), k -> new ArrayList<>()).add(v);
        var actsByVisit = new LinkedHashMap<Long, List<PlanActivityRow>>();
        for (var a : repo.listPlanActivities()) actsByVisit.computeIfAbsent(a.planVisitId(), k -> new ArrayList<>()).add(a);
        var plan = new ArrayList<PlanVersion>();
        for (var ver : repo.listVersions()) {
            var visits = new LinkedHashMap<String, PlanVisit>();
            for (var v : visitsByVersion.getOrDefault(ver.id(), List.of())) {
                var acts = new ArrayList<>(actsByVisit.getOrDefault(v.id(), List.of()));
                acts.sort(Comparator.comparing(PlanActivityRow::activityCode));
                visits.put(v.visitCode(), new PlanVisit(v.visitCode(), v.nominalDay(), acts));
            }
            plan.add(new PlanVersion(ver, visits));
        }
        return plan;
    }

    // ---------- 事件受理（幂等） ----------
    @Transactional
    public Map<String, Object> ingestEvents(Dtos.ComplianceEventBatchIn req) {
        int inserted = 0, skipped = 0;
        var skippedUids = new ArrayList<String>();
        for (var in : req.events()) {
            validateEvent(in);
            if (repo.eventExists(in.eventUid())) {
                skipped++;
                skippedUids.add(in.eventUid());
                continue;
            }
            String precision = in.timePrecision() == null || in.timePrecision().isBlank()
                    ? Enums.Precision.DATE : in.timePrecision();
            repo.insertEvent(in.eventUid(), in.centerId(), in.externalSubjectId(), in.activityType(),
                    in.activityCode(), in.occurredAt(), in.occurredDate(), precision, in.reportedAt(),
                    in.visitCode(), Boolean.TRUE.equals(in.dosingInterrupted()), in.sourceRef());
            inserted++;
        }
        var result = new LinkedHashMap<String, Object>(recalculate("ingest:events"));
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        if (!skippedUids.isEmpty()) result.put("skippedEventUids", skippedUids);
        result.put("message", "事件已受理并完成关联与窗口判定（重复 eventUid 幂等跳过）");
        return result;
    }

    private void validateEvent(Dtos.ComplianceEventIn in) {
        if (!Enums.ActivityType.ALL.contains(in.activityType())) {
            throw new BadRequestException("未知活动类型: " + in.activityType() + "，支持 " + Enums.ActivityType.ALL);
        }
        if (in.timePrecision() != null && !in.timePrecision().isBlank()
                && !Enums.Precision.ALL.contains(in.timePrecision())) {
            throw new BadRequestException("timePrecision 须为 DATETIME 或 DATE: " + in.eventUid());
        }
        // 发生时间允许缺失：事件保留，判定时置"无法判定"，不丢弃事实
    }

    // ---------- 全量重算：事件整理器 + 窗口计算器 ----------
    @Transactional
    public Map<String, Object> recalculate(String note) {
        repo.clearDerived();
        var subjects = masterRepo.listSubjectRows();
        var plan = loadPlan();
        var events = repo.listEvents();

        // ===== 事件整理器：实际发生时间 -> 受试者 + 访视实例 =====
        var links = new LinkedHashMap<Long, LinkOutcome>();
        var ambiguousCands = new LinkedHashMap<Long, List<String>>();
        for (var ev : events) {
            organizeOne(ev, subjects, plan, links, ambiguousCands);
        }

        // ===== 窗口计算器：逐项判定 + 访视级汇总 =====
        LocalDate asOf = asOfConfig == null || asOfConfig.isBlank() ? LocalDate.now() : LocalDate.parse(asOfConfig);
        int summaries = 0;
        var eventsBySubject = new LinkedHashMap<Long, List<EventRow>>();
        for (var ev : events) {
            var link = links.get(ev.id());
            if (link != null && link.subjectId() != null) {
                eventsBySubject.computeIfAbsent(link.subjectId(), k -> new ArrayList<>()).add(ev);
            }
        }
        for (var subject : subjects) {
            var own = eventsBySubject.getOrDefault(subject.id(), List.of());
            var interruptedAt = own.stream()
                    .filter(EventRow::dosingInterrupted)
                    .map(EventRow::effectiveDate).filter(Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
            // 该受试者出现的全部访视实例（各版本计划目录的并集）
            var visitCodes = new TreeMap<String, Integer>();  // visitCode -> 排序用名义日(适用版本)
            for (var pv : plan) {
                for (var visit : pv.visits().values()) visitCodes.putIfAbsent(visit.visitCode(), visit.nominalDay());
            }
            var ordered = visitCodes.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).toList();
            for (String visitCode : ordered) {
                summaries += calculateVisit(subject, visitCode, plan, own, links, ambiguousCands,
                        interruptedAt, asOf);
            }
        }
        long linked = links.values().stream().filter(l -> Enums.EventLink.LINKED.equals(l.status())).count();
        long ambiguous = links.values().stream().filter(l -> Enums.EventLink.AMBIGUOUS.equals(l.status())).count();
        long unlinked = links.values().stream().filter(l -> Enums.EventLink.UNLINKED.equals(l.status())).count();
        long marker = links.values().stream().filter(l -> Enums.EventLink.MARKER.equals(l.status())).count();
        var out = new LinkedHashMap<String, Object>();
        out.put("note", note);
        out.put("asOf", asOf.toString());
        out.put("ruleVersion", ruleVersion);
        out.put("events", events.size());
        out.put("linked", linked);
        out.put("ambiguous", ambiguous);
        out.put("unlinked", unlinked);
        out.put("interruptionMarkers", marker);
        out.put("summaries", summaries);
        return out;
    }

    // ---------- 事件整理器（锚点优先定位访视实例） ----------
    private void organizeOne(EventRow ev, List<MasterDataRepo.SubjectRow> subjects, List<PlanVersion> plan,
                             Map<Long, LinkOutcome> links, Map<Long, List<String>> ambiguousCands) {
        var mapped = masterRepo.mapSubject(ev.centerId(), ev.externalSubjectId());
        var subject = mapped.stream().findFirst().orElse(null);

        if (ev.dosingInterrupted()) {
            // 给药中断标记：只驱动"不适用"，不作为活动证据
            persistLink(ev, subject == null ? null : subject.id(), null, Enums.EventLink.MARKER,
                    "给药中断标记事件（" + ev.effectiveDate() + "），仅驱动后续访视不适用");
            links.put(ev.id(), new LinkOutcome(subject == null ? null : subject.id(), null, Enums.EventLink.MARKER));
            return;
        }
        if (subject == null) {
            persistLink(ev, null, null, Enums.EventLink.UNLINKED,
                    "中心映射缺失: " + ev.centerId() + "/" + ev.externalSubjectId() + " 无法定位统一受试者");
            links.put(ev.id(), new LinkOutcome(null, null, Enums.EventLink.UNLINKED));
            return;
        }
        // 显式访视编码优先
        if (ev.explicitVisitCode() != null && !ev.explicitVisitCode().isBlank()) {
            boolean exists = plan.stream().anyMatch(pv -> pv.visits().containsKey(ev.explicitVisitCode()));
            if (exists) {
                persistLink(ev, subject.id(), ev.explicitVisitCode(), Enums.EventLink.LINKED,
                        "来源显式访视编码 " + ev.explicitVisitCode());
                links.put(ev.id(), new LinkOutcome(subject.id(), ev.explicitVisitCode(), Enums.EventLink.LINKED));
            } else {
                persistLink(ev, subject.id(), null, Enums.EventLink.UNLINKED,
                        "显式访视 " + ev.explicitVisitCode() + " 不在方案计划目录中");
                links.put(ev.id(), new LinkOutcome(subject.id(), null, Enums.EventLink.UNLINKED));
            }
            return;
        }
        LocalDate date = ev.effectiveDate();
        if (date == null) {
            persistLink(ev, subject.id(), null, Enums.EventLink.UNLINKED,
                    "缺少发生时间且无显式访视编码，无法定位访视实例");
            links.put(ev.id(), new LinkOutcome(subject.id(), null, Enums.EventLink.UNLINKED));
            return;
        }
        long studyDay = ChronoUnit.DAYS.between(subject.enrolledDate(), date);
        // 锚点定位：在适用版本计划内，按同活动编码的活动名义日取最近访视实例
        record Cand(String visitCode, long distance, int nominalDay) {}
        var cands = new ArrayList<Cand>();
        for (String visitCode : planVisitCodes(plan)) {
            var governing = selectVersion(subject, visitCode, plan);
            if (governing == null) continue;
            var visit = governing.visits().get(visitCode);
            for (var act : visit.activities()) {
                if (!act.activityCode().equals(ev.activityCode())) continue;
                if (!populationMatch(act.appliesTo(), subject.cohort())) continue;
                int nominalDay = visit.nominalDay() + act.nominalOffset();
                cands.add(new Cand(visitCode, Math.abs(studyDay - nominalDay), nominalDay));
            }
        }
        if (cands.isEmpty()) {
            persistLink(ev, subject.id(), null, Enums.EventLink.UNLINKED,
                    "活动 " + ev.activityCode() + " 不在该受试者适用人群的计划目录中");
            links.put(ev.id(), new LinkOutcome(subject.id(), null, Enums.EventLink.UNLINKED));
            return;
        }
        long best = cands.stream().mapToLong(Cand::distance).min().orElseThrow();
        var nearest = cands.stream().filter(c -> c.distance() == best)
                .map(Cand::visitCode).distinct().sorted().toList();
        if (best > maxLinkDistanceDays) {
            persistLink(ev, subject.id(), null, Enums.EventLink.UNLINKED,
                    "研究日第 " + studyDay + " 天，距最近活动名义日 " + best + " 天，超出关联范围(±"
                            + maxLinkDistanceDays + "天)");
            links.put(ev.id(), new LinkOutcome(subject.id(), null, Enums.EventLink.UNLINKED));
            return;
        }
        if (nearest.size() > 1) {
            // 等距多候选：保留候选与依据，不擅自分配
            String rationale = "研究日第 " + studyDay + " 天，距 " + String.join("/", nearest)
                    + " 活动名义日均为 " + best + " 天，关联歧义待裁定";
            persistLink(ev, subject.id(), null, Enums.EventLink.AMBIGUOUS, rationale);
            for (String vc : nearest) repo.insertEventCandidate(ev.id(), vc, rationale);
            ambiguousCands.put(ev.id(), nearest);
            links.put(ev.id(), new LinkOutcome(subject.id(), null, Enums.EventLink.AMBIGUOUS));
            return;
        }
        String vc = nearest.get(0);
        persistLink(ev, subject.id(), vc, Enums.EventLink.LINKED,
                "研究日第 " + studyDay + " 天，锚定最近活动名义日的访视实例 " + vc);
        links.put(ev.id(), new LinkOutcome(subject.id(), vc, Enums.EventLink.LINKED));
    }

    private void persistLink(EventRow ev, Long subjectId, String visitCode, String status, String reason) {
        repo.updateEventLink(ev.id(), subjectId, visitCode, status, reason);
    }

    // ---------- 窗口计算器：单访视实例逐项判定 + 汇总；返回 1(有汇总) ----------
    private int calculateVisit(MasterDataRepo.SubjectRow subject, String visitCode, List<PlanVersion> plan,
                               List<EventRow> ownEvents, Map<Long, LinkOutcome> links,
                               Map<Long, List<String>> ambiguousCands,
                               LocalDate interruptedAt, LocalDate asOf) {
        var governing = selectVersion(subject, visitCode, plan);
        if (governing == null) {
            repo.insertSummary(subject.id(), visitCode, null, null, Enums.VisitStatus.INDETERMINATE,
                    0, 0, 0, 0, 0, 0, 0, 0, ruleVersion);
            return 1;
        }
        var visit = governing.visits().get(visitCode);
        String versionCode = governing.version().versionCode();
        LocalDate nominalDate = subject.enrolledDate().plusDays(visit.nominalDay());

        int onWindow = 0, early = 0, late = 0, notDone = 0, indeterminate = 0, notApplicable = 0;
        int denominator = 0, maxDeviation = 0;

        for (var act : visit.activities()) {
            LocalDate windowStart = nominalDate.plusDays(act.offsetStart());
            LocalDate windowEnd = nominalDate.plusDays(act.offsetEnd());
            String status, reason = null;
            Integer deviation = null, overdue = null, reportLag = null;
            boolean lateReport = false;
            Long eventId = null;
            LocalDate occurred = null;

            if (!populationMatch(act.appliesTo(), subject.cohort())) {
                status = Enums.Judgment.NOT_APPLICABLE;
                reason = "活动适用人群 " + act.appliesTo() + "，受试者队列 " + subject.cohort() + " 不适用";
            } else if (interruptedAt != null && nominalDate.isAfter(interruptedAt)) {
                status = Enums.Judgment.NOT_APPLICABLE;
                reason = "给药中断（" + interruptedAt + "）后访视不再适用，非漏访";
            } else {
                // 该访视实例内本活动的已关联事件（取最早发生；多次完成保留事实，以首次判定）
                var linked = ownEvents.stream()
                        .filter(e -> !e.dosingInterrupted() && e.activityCode().equals(act.activityCode()))
                        .filter(e -> {
                            var l = links.get(e.id());
                            return l != null && Enums.EventLink.LINKED.equals(l.status())
                                    && visitCode.equals(l.visitCode());
                        })
                        .sorted(Comparator.comparing(EventRow::effectiveDate,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(EventRow::id))
                        .toList();
                var ev = linked.isEmpty() ? null : linked.get(0);
                if (ev != null) {
                    eventId = ev.id();
                    occurred = ev.effectiveDate();
                    String repeatNote = linked.size() > 1 ? "（另有 " + (linked.size() - 1) + " 次重复完成，以首次判定）" : "";
                    if (occurred == null) {
                        status = Enums.Judgment.INDETERMINATE;
                        reason = "事件 " + ev.eventUid() + " 缺少发生时间，无法判定";
                    } else if (occurred.isBefore(windowStart)) {
                        status = Enums.Judgment.EARLY;
                        deviation = (int) ChronoUnit.DAYS.between(occurred, windowStart);
                        reason = "实际 " + occurred + " 早于窗口起点 " + windowStart + "，提前 " + deviation + " 天" + repeatNote;
                    } else if (occurred.isAfter(windowEnd)) {
                        status = Enums.Judgment.LATE;
                        deviation = (int) ChronoUnit.DAYS.between(windowEnd, occurred);
                        reason = "实际 " + occurred + " 晚于窗口终点 " + windowEnd + "，延迟 " + deviation + " 天" + repeatNote;
                    } else {
                        status = Enums.Judgment.ON_WINDOW;
                        deviation = 0;
                        reason = "实际 " + occurred + " 在窗口 [" + windowStart + ", " + windowEnd + "] 内" + repeatNote;
                    }
                    // 数据时效：窗后回报仅作时效标记，合规仍按采样/发生时刻
                    if (ev.reportedAt() != null && occurred != null) {
                        var reportedDate = ev.reportedAt().toLocalDate();
                        reportLag = (int) ChronoUnit.DAYS.between(occurred, reportedDate);
                        if (reportedDate.isAfter(windowEnd)) {
                            lateReport = true;
                            reason += "；结果 " + reportedDate + " 回报（滞后 " + reportLag
                                    + " 天，晚于窗口终点），数据时效另记，合规按发生时刻判定";
                        }
                    }
                } else {
                    // 无已关联事件：存在歧义候选事件（本访视在候选中）→ 无法判定，不得直接算完成或漏访
                    var ambiguous = ownEvents.stream()
                            .filter(e -> !e.dosingInterrupted() && e.activityCode().equals(act.activityCode()))
                            .filter(e -> {
                                var l = links.get(e.id());
                                return l != null && Enums.EventLink.AMBIGUOUS.equals(l.status())
                                        && ambiguousCands.getOrDefault(e.id(), List.of()).contains(visitCode);
                            }).toList();
                    if (!ambiguous.isEmpty()) {
                        status = Enums.Judgment.INDETERMINATE;
                        reason = "事件 " + ambiguous.get(0).eventUid() + " 关联歧义（候选 "
                                + ambiguousCands.get(ambiguous.get(0).id()) + "），待裁定，无法判定";
                    } else if (interruptedAt != null && windowEnd.isAfter(interruptedAt)) {
                        status = Enums.Judgment.NOT_APPLICABLE;
                        reason = "给药中断（" + interruptedAt + "）后本活动不再适用，非漏访";
                    } else {
                        status = Enums.Judgment.NOT_DONE;
                        overdue = Math.max(0, (int) ChronoUnit.DAYS.between(windowEnd, asOf));
                        reason = overdue > 0
                                ? "窗口已截止（" + windowEnd + "），截至 " + asOf + " 未完成，逾期 " + overdue + " 天"
                                : "窗口未截止（至 " + windowEnd + "），尚未完成";
                    }
                }
            }

            repo.insertJudgment(subject.id(), visitCode, act.activityCode(), act.activityType(), act.required(),
                    status, deviation, overdue, lateReport, reportLag, versionCode,
                    nominalDate, windowStart, windowEnd, eventId, occurred, reason, ruleVersion);

            if (Enums.Judgment.NOT_APPLICABLE.equals(status)) {
                notApplicable++;
            } else {
                if (act.required()) denominator++;
                switch (status) {
                    case Enums.Judgment.ON_WINDOW -> onWindow++;
                    case Enums.Judgment.EARLY -> { early++; maxDeviation = Math.max(maxDeviation, deviation); }
                    case Enums.Judgment.LATE -> { late++; maxDeviation = Math.max(maxDeviation, deviation); }
                    case Enums.Judgment.NOT_DONE -> notDone++;
                    case Enums.Judgment.INDETERMINATE -> indeterminate++;
                    default -> { }
                }
            }
        }

        String visitStatus;
        if (denominator == 0) visitStatus = Enums.VisitStatus.NOT_APPLICABLE;
        else if (indeterminate > 0) visitStatus = Enums.VisitStatus.INDETERMINATE;
        else if (notDone > 0) visitStatus = Enums.VisitStatus.INCOMPLETE;
        else if (early + late > 0) visitStatus = Enums.VisitStatus.DEVIATED;
        else visitStatus = Enums.VisitStatus.ON_WINDOW;

        repo.insertSummary(subject.id(), visitCode, versionCode, nominalDate, visitStatus, denominator,
                onWindow, early, late, notDone, indeterminate, notApplicable, maxDeviation, ruleVersion);
        return 1;
    }

    // ---------- 版本适用：名义访视日落在版本生效日之后的最新的适用人群版本 ----------
    private PlanVersion selectVersion(MasterDataRepo.SubjectRow subject, String visitCode, List<PlanVersion> plan) {
        PlanVersion best = null;
        for (var pv : plan) {
            if (!populationMatch(pv.version().appliesTo(), subject.cohort())) continue;
            var visit = pv.visits().get(visitCode);
            if (visit == null) continue;
            LocalDate nominal = subject.enrolledDate().plusDays(visit.nominalDay());
            if (pv.version().effectiveFrom().isAfter(nominal)) continue;
            if (best == null || pv.version().effectiveFrom().isAfter(best.version().effectiveFrom())
                    || (pv.version().effectiveFrom().equals(best.version().effectiveFrom())
                        && pv.version().versionCode().compareTo(best.version().versionCode()) > 0)) {
                best = pv;
            }
        }
        return best;
    }

    private static boolean populationMatch(String appliesTo, String cohort) {
        return "ALL".equals(appliesTo) || Objects.equals(appliesTo, cohort);
    }

    private static List<String> planVisitCodes(List<PlanVersion> plan) {
        return plan.stream().flatMap(pv -> pv.visits().keySet().stream()).distinct().sorted().toList();
    }

    // ---------- 查询 ----------
    public List<Map<String, Object>> planCatalog() {
        var out = new ArrayList<Map<String, Object>>();
        for (var pv : loadPlan()) {
            var ver = new LinkedHashMap<String, Object>();
            ver.put("versionCode", pv.version().versionCode());
            ver.put("effectiveFrom", pv.version().effectiveFrom().toString());
            ver.put("appliesTo", pv.version().appliesTo());
            ver.put("note", pv.version().note());
            var visits = new ArrayList<Map<String, Object>>();
            for (var v : pv.visits().values()) {
                var vm = new LinkedHashMap<String, Object>();
                vm.put("visitCode", v.visitCode());
                vm.put("nominalDay", v.nominalDay());
                vm.put("activities", v.activities().stream().map(a -> {
                    var am = new LinkedHashMap<String, Object>();
                    am.put("activityType", a.activityType());
                    am.put("activityCode", a.activityCode());
                    am.put("nominalOffset", a.nominalOffset());
                    am.put("offsetStart", a.offsetStart());
                    am.put("offsetEnd", a.offsetEnd());
                    am.put("required", a.required());
                    am.put("appliesTo", a.appliesTo());
                    return am;
                }).toList());
                visits.add(vm);
            }
            ver.put("visits", visits);
            out.add(ver);
        }
        return out;
    }

    public List<Map<String, Object>> listEvents() {
        var candsByEvent = new LinkedHashMap<Long, List<String>>();
        for (var c : repo.listEventCandidates()) {
            long eventId = ((Number) c.get("event_id")).longValue();
            candsByEvent.computeIfAbsent(eventId, k -> new ArrayList<>()).add((String) c.get("visit_code"));
        }
        return repo.listEvents().stream().<Map<String, Object>>map(e -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", e.id());
            m.put("eventUid", e.eventUid());
            m.put("centerId", e.centerId());
            m.put("externalSubjectId", e.externalSubjectId());
            m.put("subjectId", e.subjectId());
            m.put("activityType", e.activityType());
            m.put("activityCode", e.activityCode());
            m.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
            m.put("occurredDate", e.occurredDate() == null ? null : e.occurredDate().toString());
            m.put("timePrecision", e.timePrecision());
            m.put("reportedAt", e.reportedAt() == null ? null : e.reportedAt().toString());
            m.put("explicitVisitCode", e.explicitVisitCode());
            m.put("dosingInterrupted", e.dosingInterrupted());
            m.put("sourceRef", e.sourceRef());
            m.put("linkStatus", e.linkStatus());
            m.put("linkedVisitCode", e.linkedVisitCode());
            m.put("linkReason", e.linkReason());
            m.put("candidates", candsByEvent.getOrDefault(e.id(), List.of()));
            return m;
        }).toList();
    }

    private static final Map<String, String> ANALYSIS_DIMS = Map.of(
            "centerId", "s.center_id",
            "visitCode", "j.visit_code",
            "activityType", "j.activity_type",
            "versionCode", "j.version_code");

    public List<Map<String, Object>> analysis(String groupBy) {
        String gb = groupBy == null || groupBy.isBlank() ? "centerId,visitCode,activityType" : groupBy;
        var dims = new LinkedHashMap<String, String>();
        for (String d : gb.split(",")) {
            String key = d.trim();
            var col = ANALYSIS_DIMS.get(key);
            if (col == null) {
                throw new BadRequestException("未知分组维度: " + key + "，支持 " + ANALYSIS_DIMS.keySet());
            }
            // 输出列名保持 snake_case，经 JsonMaps.camel 统一转 camelCase
            String alias = switch (key) {
                case "centerId" -> "center_id";
                case "visitCode" -> "visit_code";
                case "activityType" -> "activity_type";
                case "versionCode" -> "version_code";
                default -> key;
            };
            dims.put(alias, col);
        }
        return repo.analysis(dims);
    }
}
