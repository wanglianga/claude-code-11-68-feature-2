package com.example.clinicalrecon.service;

import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.domain.Enums;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示种子：主数据（受试者/中心映射/访视窗口/字段目录）+ 四类来源样例批次
 *        + 访视窗口符合性（计划目录两版方案 + 五类场景活动事件）。
 * lab-batch-2（检验更正修订）不预置，留给验收流程演示"源更正→质疑重开"。
 */
@Service
public class DemoSeedService {
    private final JdbcTemplate jdbc;
    private final IntakeService intakeService;
    private final ComplianceService complianceService;
    private final ObjectMapper om;

    private static final List<String> SEED_BATCHES = List.of(
            "samples/crf-batch-1.json",
            "samples/lab-batch-1.json",
            "samples/imaging-batch-1.json",
            "samples/monitoring-batch-1.json",
            "samples/crf-batch-2-unmapped.json");

    public DemoSeedService(JdbcTemplate jdbc, IntakeService intakeService,
                           ComplianceService complianceService, ObjectMapper om) {
        this.jdbc = jdbc;
        this.intakeService = intakeService;
        this.complianceService = complianceService;
        this.om = om;
    }

    @Transactional
    public void reset() {
        jdbc.execute("""
                truncate table analysis_snapshot_row, analysis_snapshot,
                    query_response, query_item,
                    reconciled_fact_source, reconciled_fact_history, reconciled_fact, reconcile_run,
                    fact_link, link_candidate, raw_fact, source_batch,
                    event_link_candidate, activity_judgment, visit_summary, activity_event,
                    plan_activity, plan_visit, protocol_version,
                    field_catalog, visit_schedule, center_subject_map, subject
                restart identity cascade""");
    }

    @Transactional
    public Map<String, Object> seed() {
        reset();
        seedMaster();
        var results = new LinkedHashMap<String, Object>();
        for (var path : SEED_BATCHES) {
            try {
                String json = new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                var out = intakeService.ingest(om.readValue(json, Dtos.BatchIn.class));
                results.put(out.batchLabel(), Map.of("mapped", out.mapped(), "unmapped", out.unmapped()));
            } catch (Exception e) {
                throw new IllegalStateException("种子批次加载失败: " + path + " - " + e.getMessage(), e);
            }
        }
        seedCompliancePlan();
        var compliance = seedComplianceEvents();
        return Map.of(
                "master", "ONCO-2026-01 (4受试者/2中心/3访视/6字段)",
                "batches", results,
                "compliance", compliance);
    }

    private void seedMaster() {
        insertSubject("C01", "SUBJ-001", "2026-03-01", "COHORT_A");
        insertSubject("C01", "SUBJ-002", "2026-03-10", "COHORT_B");
        insertSubject("C02", "SUBJ-003", "2026-04-01", "COHORT_A");
        insertSubject("C02", "SUBJ-004", "2026-03-01", "COHORT_A");

        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C01','S-001',id from subject where subject_key='SUBJ-001'""");
        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C01','S-002',id from subject where subject_key='SUBJ-002'""");
        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C02','S-101',id from subject where subject_key='SUBJ-003'""");
        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C02','S-102',id from subject where subject_key='SUBJ-004'""");

        // 方案访视窗口：V2 与 V3 在第18天相接（窗口跨边界场景）
        jdbc.update("insert into visit_schedule(trial_id, visit_code, anchor_day, window_start, window_end) values(?,?,?,?,?)",
                "ONCO-2026-01", "V1", 0, -2, 2);
        jdbc.update("insert into visit_schedule(trial_id, visit_code, anchor_day, window_start, window_end) values(?,?,?,?,?)",
                "ONCO-2026-01", "V2", 14, 13, 18);
        jdbc.update("insert into visit_schedule(trial_id, visit_code, anchor_day, window_start, window_end) values(?,?,?,?,?)",
                "ONCO-2026-01", "V3", 28, 18, 31);

        // 统一字段目录：保留各来源字段编码映射
        insertField("VITAL_SYS_BP", "收缩压", "NUMERIC", "mmHg", true,
                "{\"CRF\":[\"SYSBP\"]}");
        insertField("VITAL_DIA_BP", "舒张压", "NUMERIC", "mmHg", true,
                "{\"CRF\":[\"DIABP\"]}");
        insertField("LAB_ALT", "谷丙转氨酶ALT", "NUMERIC", "U/L", true,
                "{\"LAB\":[\"ALT\"],\"CRF\":[\"ALT\"]}");
        insertField("LAB_AST", "谷草转氨酶AST", "NUMERIC", "U/L", false,
                "{\"LAB\":[\"AST\"]}");
        insertField("TUMOR_LD_SUM", "靶病灶长径和", "NUMERIC", "mm", true,
                "{\"IMAGING\":[\"LD_SUM\"],\"CRF\":[\"TUMSUM\"]}");
        insertField("MON_FINDING", "监查发现", "TEXT", null, false,
                "{\"MONITORING\":[\"FINDING\"]}");
    }

    private void insertSubject(String centerId, String subjectKey, String enrolled, String cohort) {
        jdbc.update("insert into subject(trial_id, center_id, subject_key, enrolled_date, cohort) values(?,?,?,?,?)",
                "ONCO-2026-01", centerId, subjectKey, java.sql.Date.valueOf(enrolled), cohort);
    }

    private void insertField(String key, String label, String type, String unit,
                             boolean required, String mapJson) {
        jdbc.update("""
                insert into field_catalog(field_key, label, value_type, unit, required_for_analysis, source_field_map)
                values(?,?,?,?,?,?::jsonb)""", key, label, type, unit, required, mapJson);
    }

    // ---------- 符合性计划目录：v1.0 全量计划；v2.0(修订) 仅改 V3 影像窗口 ----------
    private void seedCompliancePlan() {
        long v1 = insertVersion("v1.0", "2026-01-01", "ALL", "初始方案");
        long v2 = insertVersion("v2.0", "2026-04-15", "ALL",
                "修订案：V3 影像采集窗口由 [名义-10,+3] 收窄为 [-4,+5]；仅覆盖名义访视日 ≥ 2026-04-15 的访视实例");

        // V1(第0天): 问诊/采血 ±2 天, 给药当天, PK采血(仅COHORT_A) 0~1 天
        long v1V1 = insertPlanVisit(v1, "V1", 0);
        insertPlanActivity(v1V1, Enums.ActivityType.ASSESSMENT, "ASSESS", -2, 2, "ALL");
        insertPlanActivity(v1V1, Enums.ActivityType.SAMPLING, "LAB_SAMPLING", -2, 2, "ALL");
        insertPlanActivity(v1V1, Enums.ActivityType.DOSING, "STUDY_DRUG", 0, 0, "ALL");
        insertPlanActivity(v1V1, Enums.ActivityType.SAMPLING, "PK_SAMPLING", 0, 1, "COHORT_A");
        // V2(第14天): 各项 -1~+4 天
        long v1V2 = insertPlanVisit(v1, "V2", 14);
        insertPlanActivity(v1V2, Enums.ActivityType.ASSESSMENT, "ASSESS", -1, 4, "ALL");
        insertPlanActivity(v1V2, Enums.ActivityType.SAMPLING, "LAB_SAMPLING", -1, 4, "ALL");
        insertPlanActivity(v1V2, Enums.ActivityType.IMAGING, "CT_SCAN", -1, 4, "ALL");
        insertPlanActivity(v1V2, Enums.ActivityType.DOSING, "STUDY_DRUG", -1, 4, "ALL");
        insertPlanActivity(v1V2, Enums.ActivityType.SAMPLING, "PK_SAMPLING", -1, 4, "COHORT_A");
        // V3(第28天): 各项 -10~+3 天
        long v1V3 = insertPlanVisit(v1, "V3", 28);
        insertPlanActivity(v1V3, Enums.ActivityType.ASSESSMENT, "ASSESS", -10, 3, "ALL");
        insertPlanActivity(v1V3, Enums.ActivityType.SAMPLING, "LAB_SAMPLING", -10, 3, "ALL");
        insertPlanActivity(v1V3, Enums.ActivityType.IMAGING, "CT_SCAN", -10, 3, "ALL");
        insertPlanActivity(v1V3, Enums.ActivityType.DOSING, "STUDY_DRUG", -10, 3, "ALL");

        // v2.0 与 v1.0 相同, 仅 V3 影像窗口收窄为 [-4,+5]
        long v2V1 = insertPlanVisit(v2, "V1", 0);
        insertPlanActivity(v2V1, Enums.ActivityType.ASSESSMENT, "ASSESS", -2, 2, "ALL");
        insertPlanActivity(v2V1, Enums.ActivityType.SAMPLING, "LAB_SAMPLING", -2, 2, "ALL");
        insertPlanActivity(v2V1, Enums.ActivityType.DOSING, "STUDY_DRUG", 0, 0, "ALL");
        insertPlanActivity(v2V1, Enums.ActivityType.SAMPLING, "PK_SAMPLING", 0, 1, "COHORT_A");
        long v2V2 = insertPlanVisit(v2, "V2", 14);
        insertPlanActivity(v2V2, Enums.ActivityType.ASSESSMENT, "ASSESS", -1, 4, "ALL");
        insertPlanActivity(v2V2, Enums.ActivityType.SAMPLING, "LAB_SAMPLING", -1, 4, "ALL");
        insertPlanActivity(v2V2, Enums.ActivityType.IMAGING, "CT_SCAN", -1, 4, "ALL");
        insertPlanActivity(v2V2, Enums.ActivityType.DOSING, "STUDY_DRUG", -1, 4, "ALL");
        insertPlanActivity(v2V2, Enums.ActivityType.SAMPLING, "PK_SAMPLING", -1, 4, "COHORT_A");
        long v2V3 = insertPlanVisit(v2, "V3", 28);
        insertPlanActivity(v2V3, Enums.ActivityType.ASSESSMENT, "ASSESS", -10, 3, "ALL");
        insertPlanActivity(v2V3, Enums.ActivityType.SAMPLING, "LAB_SAMPLING", -10, 3, "ALL");
        insertPlanActivity(v2V3, Enums.ActivityType.IMAGING, "CT_SCAN", -4, 5, "ALL");
        insertPlanActivity(v2V3, Enums.ActivityType.DOSING, "STUDY_DRUG", -10, 3, "ALL");
    }

    private long insertVersion(String code, String effectiveFrom, String appliesTo, String note) {
        var kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "insert into protocol_version(trial_id, version_code, effective_from, applies_to, note) values(?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, "ONCO-2026-01");
            ps.setString(2, code);
            ps.setDate(3, java.sql.Date.valueOf(effectiveFrom));
            ps.setString(4, appliesTo);
            ps.setString(5, note);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private long insertPlanVisit(long versionId, String visitCode, int nominalDay) {
        var kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "insert into plan_visit(version_id, visit_code, nominal_day) values(?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, versionId);
            ps.setString(2, visitCode);
            ps.setInt(3, nominalDay);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private void insertPlanActivity(long planVisitId, String type, String code,
                                    int offsetStart, int offsetEnd, String appliesTo) {
        jdbc.update("""
                insert into plan_activity(plan_visit_id, activity_type, activity_code, nominal_offset,
                    offset_start, offset_end, required, applies_to)
                values(?,?,?,?,?,?,true,?)""", planVisitId, type, code, 0, offsetStart, offsetEnd, appliesTo);
    }

    // ---------- 符合性活动事件：提前采样/跨窗分次完成/迟报/方案修订/给药中断/无法判定 ----------
    private Map<String, Object> seedComplianceEvents() {
        var events = new ArrayList<Dtos.ComplianceEventIn>();
        // SUBJ-001(C01, COHORT_A, 入组 2026-03-01)：V1 按窗；V2 跨窗分次完成(影像延迟1天落入V3访视窗)+采血迟报
        events.addAll(List.of(
                ev("EV-001", "C01", "S-001", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-03-01"),
                ev("EV-002", "C01", "S-001", Enums.ActivityType.SAMPLING, "LAB_SAMPLING", "2026-03-01"),
                ev("EV-003", "C01", "S-001", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-03-01"),
                ev("EV-004", "C01", "S-001", Enums.ActivityType.SAMPLING, "PK_SAMPLING", "2026-03-02"),
                ev("EV-005", "C01", "S-001", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-03-14"),
                ev("EV-006", "C01", "S-001", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-03-15"),
                evReported("EV-007", "C01", "S-001", Enums.ActivityType.SAMPLING, "LAB_SAMPLING",
                        "2026-03-16", "2026-03-25T09:00:00Z"),   // 窗内采样, 窗后回报: 合规按采样时刻, 时效另记
                ev("EV-008", "C01", "S-001", Enums.ActivityType.SAMPLING, "PK_SAMPLING", "2026-03-16"),
                ev("EV-009", "C01", "S-001", Enums.ActivityType.IMAGING, "CT_SCAN", "2026-03-20")));
        // SUBJ-002(C01, COHORT_B, 入组 2026-03-10)：V1 按窗(PK不适用)；2026-03-20 给药中断 → V2/V3 不适用
        events.addAll(List.of(
                ev("EV-010", "C01", "S-002", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-03-10"),
                ev("EV-011", "C01", "S-002", Enums.ActivityType.SAMPLING, "LAB_SAMPLING", "2026-03-10"),
                ev("EV-012", "C01", "S-002", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-03-10"),
                evInterrupted("EV-013", "C01", "S-002", "2026-03-20")));
        // SUBJ-003(C02, COHORT_A, 入组 2026-04-01)：V2 起适用 v2.0；V2 影像缺发生时间→无法判定；
        // V3 影像 2026-04-23 按 v2.0 窗 [04-25,05-04] 判提前2天(若按 v1.0 则按窗)
        events.addAll(List.of(
                ev("EV-014", "C02", "S-101", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-04-01"),
                ev("EV-015", "C02", "S-101", Enums.ActivityType.SAMPLING, "LAB_SAMPLING", "2026-04-01"),
                ev("EV-016", "C02", "S-101", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-04-01"),
                ev("EV-017", "C02", "S-101", Enums.ActivityType.SAMPLING, "PK_SAMPLING", "2026-04-02"),
                ev("EV-018", "C02", "S-101", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-04-15"),
                ev("EV-019", "C02", "S-101", Enums.ActivityType.SAMPLING, "LAB_SAMPLING", "2026-04-15"),
                ev("EV-020", "C02", "S-101", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-04-15"),
                evNoDate("EV-021", "C02", "S-101", Enums.ActivityType.IMAGING, "CT_SCAN", "V2"),
                ev("EV-022", "C02", "S-101", Enums.ActivityType.SAMPLING, "PK_SAMPLING", "2026-04-16"),
                ev("EV-023", "C02", "S-101", Enums.ActivityType.IMAGING, "CT_SCAN", "2026-04-23")));
        // SUBJ-004(C02, COHORT_A, 入组 2026-03-01)：V2 采血提前3天；问诊事件等距歧义保留候选；
        // V3 影像 2026-03-23 按 v1.0 窗 [03-19,04-01] 判按窗(同一相对日, 与 SUBJ-003 版本对照)
        events.addAll(List.of(
                ev("EV-024", "C02", "S-102", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-03-01"),
                ev("EV-025", "C02", "S-102", Enums.ActivityType.SAMPLING, "LAB_SAMPLING", "2026-03-01"),
                ev("EV-026", "C02", "S-102", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-03-01"),
                ev("EV-027", "C02", "S-102", Enums.ActivityType.SAMPLING, "PK_SAMPLING", "2026-03-01"),
                ev("EV-028", "C02", "S-102", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-03-15"),
                ev("EV-029", "C02", "S-102", Enums.ActivityType.SAMPLING, "LAB_SAMPLING", "2026-03-11"),
                ev("EV-030", "C02", "S-102", Enums.ActivityType.DOSING, "STUDY_DRUG", "2026-03-15"),
                ev("EV-031", "C02", "S-102", Enums.ActivityType.IMAGING, "CT_SCAN", "2026-03-16"),
                ev("EV-032", "C02", "S-102", Enums.ActivityType.SAMPLING, "PK_SAMPLING", "2026-03-15"),
                ev("EV-033", "C02", "S-102", Enums.ActivityType.ASSESSMENT, "ASSESS", "2026-03-22"),
                ev("EV-034", "C02", "S-102", Enums.ActivityType.IMAGING, "CT_SCAN", "2026-03-23")));
        return complianceService.ingestEvents(new Dtos.ComplianceEventBatchIn(events));
    }

    private static Dtos.ComplianceEventIn ev(String uid, String center, String extSubj,
                                             String type, String code, String date) {
        return new Dtos.ComplianceEventIn(uid, center, extSubj, type, code, null,
                LocalDate.parse(date), "DATE", null, null, null, "seed");
    }

    private static Dtos.ComplianceEventIn evReported(String uid, String center, String extSubj,
                                                     String type, String code, String date, String reportedAt) {
        return new Dtos.ComplianceEventIn(uid, center, extSubj, type, code, null,
                LocalDate.parse(date), "DATE", OffsetDateTime.parse(reportedAt), null, null, "seed");
    }

    private static Dtos.ComplianceEventIn evInterrupted(String uid, String center, String extSubj, String date) {
        return new Dtos.ComplianceEventIn(uid, center, extSubj, Enums.ActivityType.DOSING, "STUDY_DRUG", null,
                LocalDate.parse(date), "DATE", null, null, true, "seed");
    }

    private static Dtos.ComplianceEventIn evNoDate(String uid, String center, String extSubj,
                                                   String type, String code, String visitCode) {
        return new Dtos.ComplianceEventIn(uid, center, extSubj, type, code, null,
                null, "DATE", null, visitCode, null, "seed");
    }
}
