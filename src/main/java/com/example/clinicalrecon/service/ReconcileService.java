package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.repo.IntakeRepo;
import com.example.clinicalrecon.repo.LinkRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.QueryRepo;
import com.example.clinicalrecon.repo.ReconRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 事实协调器：在统一字段上对照 CRF/检验/影像/监查证据，发布 确认/冲突/缺失/待判 四态。
 * 关键语义：
 *  - 同一访视两条有效但互斥的检验结果：不按接收先后覆盖，双方修订关系保留，字段置 CONFLICT 阻止确认；
 *  - 人工裁定（MANUAL_RESOLUTION）在来源修订签名变化后自动失效，回到证据重算；
 *  - 每次内容变化产生新版本并写入历史，供快照复算与回溯。
 */
@Service
public class ReconcileService {
    private final ReconRepo reconRepo;
    private final IntakeRepo intakeRepo;
    private final LinkRepo linkRepo;
    private final MasterDataRepo masterRepo;
    private final QueryRepo queryRepo;
    private final String ruleVersion;

    public ReconcileService(ReconRepo reconRepo, IntakeRepo intakeRepo, LinkRepo linkRepo,
                            MasterDataRepo masterRepo, QueryRepo queryRepo,
                            @Value("${app.recon-rule-version}") String ruleVersion) {
        this.reconRepo = reconRepo;
        this.intakeRepo = intakeRepo;
        this.linkRepo = linkRepo;
        this.masterRepo = masterRepo;
        this.queryRepo = queryRepo;
        this.ruleVersion = ruleVersion;
    }

    private record GroupKey(Long subjectId, String visitCode, String fieldKey) {}
    private record Bumped(long reconId, int newVersion, String newStatus, String newSig) {}
    private record PendingSpec(String rule, String sig, List<Long> factIds) {}

    @Transactional
    public long reconcile(String note) {
        long seq = reconRepo.nextRun(note);
        List<Bumped> bumped = new ArrayList<>();
        int changed = 0;

        var factsById = new LinkedHashMap<Long, IntakeRepo.RawFactRow>();
        for (var f : intakeRepo.listRawFacts(null, null)) factsById.put(f.id(), f);
        var links = linkRepo.allLinks();
        var linkByFact = links.stream().collect(Collectors.toMap(LinkRepo.FactLinkRow::rawFactId, l -> l, (a, b) -> a));

        // ---------- 1. 已关联事实按 (受试者, 访视, 统一字段) 分组协调 ----------
        Map<GroupKey, List<IntakeRepo.RawFactRow>> groups = new LinkedHashMap<>();
        for (var link : links) {
            var fact = factsById.get(link.rawFactId());
            if (fact == null || !Enums.Mapping.MAPPED.equals(fact.mappingStatus())) continue;
            groups.computeIfAbsent(new GroupKey(link.subjectId(), link.visitCode(), fact.fieldKey()), k -> new ArrayList<>())
                    .add(fact);
        }
        for (var e : groups.entrySet()) {
            if (upsertGroup(e.getKey(), e.getValue(), seq, bumped)) changed++;
        }

        // ---------- 2. 缺失：活跃(受试者,访视)对 × 分析必需字段 ----------
        var activePairs = links.stream().map(l -> l.subjectId() + "|" + l.visitCode()).distinct().toList();
        var required = masterRepo.requiredFields();
        Map<GroupKey, Boolean> desiredMissing = new LinkedHashMap<>();
        for (var pair : activePairs) {
            long subjectId = Long.parseLong(pair.split("\\|")[0]);
            String visitCode = pair.split("\\|")[1];
            var covered = groups.keySet().stream()
                    .filter(k -> Objects.equals(k.subjectId(), subjectId) && k.visitCode().equals(visitCode))
                    .map(GroupKey::fieldKey).collect(Collectors.toSet());
            for (var def : required) {
                if (!covered.contains(def.fieldKey())) {
                    desiredMissing.put(new GroupKey(subjectId, visitCode, def.fieldKey()), Boolean.TRUE);
                }
            }
        }
        for (var key : desiredMissing.keySet()) {
            var existing = reconRepo.findGroup(key.subjectId(), key.visitCode(), key.fieldKey());
            if (existing.isEmpty()) {
                long id = insertRecon(key, Enums.Recon.MISSING, null, Enums.Rule.NO_SOURCE, null, null, null, seq);
                bumped.add(new Bumped(id, 1, Enums.Recon.MISSING, null));
                changed++;
            }
        }

        // ---------- 3. 待判：未映射字段 + 未裁定关联 ----------
        Map<GroupKey, PendingSpec> desiredPending = new LinkedHashMap<>();
        for (var fact : factsById.values()) {
            if (!fact.valid()) continue;
            if (Enums.Mapping.UNMAPPED.equals(fact.mappingStatus())) {
                var link = linkByFact.get(fact.id());
                var key = new GroupKey(link != null ? link.subjectId() : null,
                        link != null ? link.visitCode() : null,
                        "UNMAPPED::" + fact.sourceSystem() + "::" + fact.sourceFieldCode());
                var spec = desiredPending.computeIfAbsent(key,
                        k -> new PendingSpec(Enums.Rule.UNMAPPED_FIELD, null, new ArrayList<>()));
                spec.factIds().add(fact.id());
            } else if (!Enums.Link.LINKED.equals(fact.linkStatus())) {
                var cands = linkRepo.candidatesOfFact(fact.id());
                var subjectIds = cands.stream().map(LinkRepo.CandidateRow::subjectId).distinct().toList();
                Long subjectId = subjectIds.size() == 1 ? subjectIds.get(0) : null;
                var key = new GroupKey(subjectId, null, fact.fieldKey());
                String rule = cands.isEmpty() ? Enums.Rule.NO_CANDIDATE : Enums.Rule.LINK_UNDECIDED;
                var spec = desiredPending.computeIfAbsent(key,
                        k -> new PendingSpec(rule, null, new ArrayList<>()));
                spec.factIds().add(fact.id());
                // 同一键下规则取更严重的 NO_CANDIDATE 优先? 保持首次即可, 事实清单会合并
            }
        }
        // 计算签名并 upsert
        var seenPendingKeys = new LinkedHashMap<GroupKey, Long>();
        for (var e : desiredPending.entrySet()) {
            var key = e.getKey();
            var spec = e.getValue();
            String sig = signature(spec.factIds().stream().map(factsById::get).toList());
            var existing = reconRepo.findGroup(key.subjectId(), key.visitCode(), key.fieldKey());
            if (existing.isPresent() && Enums.Recon.PENDING.equals(existing.get().status())) {
                seenPendingKeys.put(key, existing.get().id());
                var ex = existing.get();
                if (!Objects.equals(ex.sourceSig(), sig) || !ex.ruleCode().equals(spec.rule())) {
                    updateRecon(ex.id(), Enums.Recon.PENDING, null, spec.rule(), null, null, sig,
                            ex.version() + 1, seq);
                    bumped.add(new Bumped(ex.id(), ex.version() + 1, Enums.Recon.PENDING, sig));
                    changed++;
                }
                reconRepo.replaceSources(ex.id(),
                        spec.factIds().stream().map(id -> new long[]{id}).toList(),
                        spec.factIds().stream().map(id -> Enums.SourceRole.PENDING_EVIDENCE).toList());
            } else if (existing.isEmpty()) {
                long id = insertRecon(key, Enums.Recon.PENDING, null, spec.rule(), null, null, sig, seq);
                reconRepo.replaceSources(id,
                        spec.factIds().stream().map(fid -> new long[]{fid}).toList(),
                        spec.factIds().stream().map(fid -> Enums.SourceRole.PENDING_EVIDENCE).toList());
                bumped.add(new Bumped(id, 1, Enums.Recon.PENDING, sig));
                changed++;
            }
        }
        // 已失效的待判占位行（关联已裁定/事实已失效）→ 关闭其活跃质疑后清理
        for (var row : reconRepo.listRecon(Enums.Recon.PENDING)) {
            var key = new GroupKey(row.subjectId(), row.visitCode(), row.fieldKey());
            if (!desiredPending.containsKey(key)) {
                autoCloseQueries(row.id(), "system:reconcile", "待判基础已消除（关联已裁定或事实已失效）");
                reconRepo.deleteRecon(row.id());
                changed++;
            }
        }
        // 已失效的缺失行（字段被覆盖）→ 关闭其活跃质疑后清理
        for (var row : reconRepo.listRecon(Enums.Recon.MISSING)) {
            var key = new GroupKey(row.subjectId(), row.visitCode(), row.fieldKey());
            if (!desiredMissing.containsKey(key)) {
                autoCloseQueries(row.id(), "system:reconcile", "缺失字段已有来源数据");
                reconRepo.deleteRecon(row.id());
                changed++;
            }
        }

        // ---------- 4. 版本跃迁的后续动作：自动关闭已消除差异 + 重开已关闭/已回复质疑 ----------
        for (var b : bumped) {
            var row = reconRepo.findById(b.reconId()).orElse(null);
            if (row == null) continue;
            // 数据驱动的差异消除（非人工裁定）→ 自动关闭活跃质疑
            if (Enums.Recon.CONFIRMED.equals(row.status()) && !Enums.Rule.MANUAL_RESOLUTION.equals(row.ruleCode())) {
                autoCloseQueries(row.id(), "system:reconcile", "来源数据已一致，差异消除");
            }
            // 源事实在回复/关闭后再次更正 → 重新打开，旧回复仍引用各自版本
            for (var q : queryRepo.findReopenable(row.id(), row.version())) {
                queryRepo.setStatus(q.id(), Enums.Query.REOPENED);
                queryRepo.insertResponse(q.id(), "system", "SYSTEM",
                        "源事实在回复/关闭后再次更正（协调版本 v" + row.version() + "），质疑重新打开；"
                                + "此前回复仍引用其对应的事实版本",
                        null, row.version(), row.sourceSig());
            }
        }

        // ---------- 5. 自动开质疑：冲突 / 未映射 / 缺失 ----------
        for (var row : reconRepo.allRecon()) {
            switch (row.status()) {
                case Enums.Recon.CONFLICT -> openReconQuery(row, Enums.Issue.CONFLICT, Enums.Role.DM,
                        "字段 " + row.fieldKey() + " 存在互斥来源值", describeConflict(row));
                case Enums.Recon.PENDING -> {
                    if (Enums.Rule.UNMAPPED_FIELD.equals(row.ruleCode())) {
                        openReconQuery(row, Enums.Issue.UNMAPPED_FIELD, Enums.Role.DM,
                                "字段未映射: " + row.fieldKey(), "来源字段不在字段目录中，保持待判，不得进入分析");
                    }
                }
                case Enums.Recon.MISSING -> openReconQuery(row, Enums.Issue.MISSING_DATA, Enums.Role.CRA,
                        "分析必需字段缺失: " + row.fieldKey(), "访视 " + row.visitCode() + " 无任何来源上报该字段");
                default -> { }
            }
        }

        reconRepo.finishRun(seq, changed);
        return seq;
    }

    /** 单组协调：返回是否有内容变化 */
    private boolean upsertGroup(GroupKey key, List<IntakeRepo.RawFactRow> facts, long seq,
                                List<Bumped> bumped) {
        var valid = facts.stream().filter(IntakeRepo.RawFactRow::valid)
                .sorted(Comparator.comparing(IntakeRepo.RawFactRow::sourceSystem)
                        .thenComparing(IntakeRepo.RawFactRow::sourceRecordId)).toList();
        var superseded = facts.stream().filter(f -> !f.valid()).toList();
        if (valid.isEmpty()) return false;

        String sig = signature(valid);
        var distinctValues = valid.stream().map(IntakeRepo.RawFactRow::normalizedValue)
                .distinct().sorted().toList();

        String status, value, rule, overrideValue = null, overrideSig = null;
        if (distinctValues.size() == 1) {
            status = Enums.Recon.CONFIRMED;
            value = distinctValues.get(0);
            rule = valid.size() == 1 ? Enums.Rule.SINGLE_SOURCE : Enums.Rule.MULTI_AGREE;
        } else {
            // 互斥有效值：不按接收先后覆盖，双方保留，字段阻止确认
            status = Enums.Recon.CONFLICT;
            value = null;
            rule = Enums.Rule.MUTUALLY_EXCLUSIVE;
        }

        var existing = reconRepo.findGroup(key.subjectId(), key.visitCode(), key.fieldKey());
        if (existing.isPresent() && existing.get().overrideValue() != null) {
            if (existing.get().overrideSig().equals(sig)) {
                // 人工裁定仍有效（来源未再更正）
                status = Enums.Recon.CONFIRMED;
                value = existing.get().overrideValue();
                rule = Enums.Rule.MANUAL_RESOLUTION;
                overrideValue = existing.get().overrideValue();
                overrideSig = existing.get().overrideSig();
            }
            // 否则裁定失效：来源已再更正，回到证据重算（override 清空）
        }

        String role = Enums.Recon.CONFIRMED.equals(status)
                ? Enums.SourceRole.CONFIRMING : Enums.SourceRole.CONFLICTING;
        var srcIds = new ArrayList<long[]>();
        var srcRoles = new ArrayList<String>();
        for (var f : valid) { srcIds.add(new long[]{f.id()}); srcRoles.add(role); }
        for (var f : superseded) { srcIds.add(new long[]{f.id()}); srcRoles.add(Enums.SourceRole.SUPERSEDED); }

        if (existing.isEmpty()) {
            long id = insertRecon(key, status, value, rule, overrideValue, overrideSig, sig, seq);
            reconRepo.replaceSources(id, srcIds, srcRoles);
            bumped.add(new Bumped(id, 1, status, sig));
            return true;
        }
        var ex = existing.get();
        boolean contentChanged = !Objects.equals(ex.status(), status)
                || !Objects.equals(ex.confirmedValue(), value)
                || !Objects.equals(ex.ruleCode(), rule)
                || !Objects.equals(ex.sourceSig(), sig)
                || !Objects.equals(ex.overrideValue(), overrideValue);
        if (contentChanged) {
            updateRecon(ex.id(), status, value, rule, overrideValue, overrideSig, sig, ex.version() + 1, seq);
            bumped.add(new Bumped(ex.id(), ex.version() + 1, status, sig));
        }
        reconRepo.replaceSources(ex.id(), srcIds, srcRoles);
        return contentChanged;
    }

    private long insertRecon(GroupKey key, String status, String value, String rule,
                             String overrideValue, String overrideSig, String sig, long seq) {
        long id = reconRepo.insertRecon(new ReconRepo.ReconRow(0, key.subjectId(), key.visitCode(),
                key.fieldKey(), status, value, rule, ruleVersion, overrideValue, overrideSig, sig, 1, seq, null));
        reconRepo.insertHistory(id, 1, seq, status, value, rule, ruleVersion);
        return id;
    }

    private void updateRecon(long id, String status, String value, String rule,
                             String overrideValue, String overrideSig, String sig, int version, long seq) {
        reconRepo.updateRecon(id, status, value, rule, ruleVersion, overrideValue, overrideSig, sig, version, seq);
        reconRepo.insertHistory(id, version, seq, status, value, rule, ruleVersion);
    }

    /** 来源修订签名：有效事实按来源/记录号排序的 "系统:记录号#修订" 列表 */
    static String signature(List<IntakeRepo.RawFactRow> validFacts) {
        return validFacts.stream()
                .map(f -> f.sourceSystem() + ":" + f.sourceRecordId() + "#" + f.sourceRevision())
                .sorted().collect(Collectors.joining(";"));
    }

    private void openReconQuery(ReconRepo.ReconRow row, String issueType, String role,
                                String title, String detail) {
        if (queryRepo.hasActiveQueryForRecon(row.id(), issueType)) return;
        String subject = row.subjectId() == null ? "?" :
                masterRepo.findSubjectById(row.subjectId()).map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        queryRepo.insertQuery(row.id(), null, issueType,
                title + "（" + subject + "/" + (row.visitCode() == null ? "未定访视" : row.visitCode()) + "）",
                detail, role, "system:reconcile", row.version());
    }

    private String describeConflict(ReconRepo.ReconRow row) {
        var sources = reconRepo.sourcesOf(row.id());
        return sources.stream()
                .filter(s -> Enums.SourceRole.CONFLICTING.equals(s.get("role")))
                .map(s -> s.get("source_system") + " " + s.get("source_record_id") + "#rev"
                        + s.get("source_revision") + "="
                        + (s.get("value_num") != null ? s.get("value_num") + "" + (s.get("unit") != null ? s.get("unit") : "")
                        : s.get("value_text")))
                .collect(Collectors.joining(" 互斥 ", "互斥值双方保留: ", "；不按接收先后覆盖，字段已阻止确认"));
    }

    private void autoCloseQueries(long reconId, String closedBy, String note) {
        var row = reconRepo.findById(reconId).orElse(null);
        Integer ver = row != null ? row.version() : null;
        String sig = row != null ? row.sourceSig() : null;
        for (var q : queryRepo.activeQueriesForRecon(reconId)) {
            if (List.of(Enums.Issue.CONFLICT, Enums.Issue.MISSING_DATA, Enums.Issue.UNMAPPED_FIELD)
                    .contains(q.issueType())) {
                queryRepo.close(q.id(), closedBy, ver);
                queryRepo.insertResponse(q.id(), "system", "SYSTEM", note, null, ver, sig);
            }
        }
    }

    /** 人工裁定：仅允许对 CONFLICT 状态做裁定；裁定在来源再更正后自动失效 */
    @Transactional
    public void resolve(long reconId, String value, String rationale) {
        var row = reconRepo.findById(reconId)
                .orElseThrow(() -> new NotFoundException("协调事实不存在: " + reconId));
        if (!Enums.Recon.CONFLICT.equals(row.status())) {
            throw new BadRequestException("仅 CONFLICT 状态可人工裁定，当前=" + row.status());
        }
        long seq = reconRepo.nextRun("manual-resolve:" + reconId + " by " + UserContext.user());
        updateRecon(row.id(), Enums.Recon.CONFIRMED, value, Enums.Rule.MANUAL_RESOLUTION,
                value, row.sourceSig(), row.sourceSig(), row.version() + 1, seq);
        reconRepo.finishRun(seq, 1);
    }
}
