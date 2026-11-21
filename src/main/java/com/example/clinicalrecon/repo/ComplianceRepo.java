package com.example.clinicalrecon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 访视窗口符合性：计划目录、活动事件、逐项判定与访视汇总的数据访问。 */
@Repository
public class ComplianceRepo {
    private final JdbcTemplate jdbc;

    public ComplianceRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---------- 计划目录 ----------
    public record VersionRow(long id, String trialId, String versionCode, LocalDate effectiveFrom,
                             String appliesTo, String note) {}
    public record PlanVisitRow(long id, long versionId, String visitCode, int nominalDay) {}
    public record PlanActivityRow(long id, long planVisitId, String activityType, String activityCode,
                                  int nominalOffset, int offsetStart, int offsetEnd,
                                  boolean required, String appliesTo) {}

    public List<VersionRow> listVersions() {
        return jdbc.query("select * from protocol_version order by trial_id, effective_from, version_code",
                (rs, i) -> new VersionRow(rs.getLong("id"), rs.getString("trial_id"), rs.getString("version_code"),
                        rs.getDate("effective_from").toLocalDate(), rs.getString("applies_to"), rs.getString("note")));
    }

    public List<PlanVisitRow> listPlanVisits() {
        return jdbc.query("select * from plan_visit order by version_id, nominal_day",
                (rs, i) -> new PlanVisitRow(rs.getLong("id"), rs.getLong("version_id"),
                        rs.getString("visit_code"), rs.getInt("nominal_day")));
    }

    public List<PlanActivityRow> listPlanActivities() {
        return jdbc.query("select * from plan_activity order by plan_visit_id, activity_code",
                (rs, i) -> new PlanActivityRow(rs.getLong("id"), rs.getLong("plan_visit_id"),
                        rs.getString("activity_type"), rs.getString("activity_code"),
                        rs.getInt("nominal_offset"), rs.getInt("offset_start"), rs.getInt("offset_end"),
                        rs.getBoolean("required"), rs.getString("applies_to")));
    }

    // ---------- 活动事件 ----------
    public record EventRow(long id, String eventUid, String centerId, String externalSubjectId, Long subjectId,
                           String activityType, String activityCode,
                           OffsetDateTime occurredAt, LocalDate occurredDate, String timePrecision,
                           OffsetDateTime reportedAt, String explicitVisitCode, boolean dosingInterrupted,
                           String sourceRef, String linkedVisitCode, String linkStatus, String linkReason) {
        /** 实际发生日期：仅日期精度优先，其次取时间戳的日期部分；均无 = null（缺发生时间） */
        public LocalDate effectiveDate() {
            return occurredDate != null ? occurredDate : occurredAt != null ? occurredAt.toLocalDate() : null;
        }
    }

    public static final RowMapper<EventRow> EVENT_MAPPER = (rs, i) -> new EventRow(
            rs.getLong("id"), rs.getString("event_uid"), rs.getString("center_id"),
            rs.getString("external_subject_id"),
            rs.getObject("subject_id") == null ? null : rs.getLong("subject_id"),
            rs.getString("activity_type"), rs.getString("activity_code"),
            rs.getTimestamp("occurred_at") == null ? null
                    : rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC),
            rs.getDate("occurred_date") == null ? null : rs.getDate("occurred_date").toLocalDate(),
            rs.getString("time_precision"),
            rs.getTimestamp("reported_at") == null ? null
                    : rs.getTimestamp("reported_at").toInstant().atOffset(ZoneOffset.UTC),
            rs.getString("explicit_visit_code"), rs.getBoolean("dosing_interrupted"),
            rs.getString("source_ref"), rs.getString("linked_visit_code"),
            rs.getString("link_status"), rs.getString("link_reason"));

    public boolean eventExists(String eventUid) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from activity_event where event_uid=?)", Boolean.class, eventUid));
    }

    public long insertEvent(String eventUid, String centerId, String externalSubjectId, String activityType,
                            String activityCode, OffsetDateTime occurredAt, LocalDate occurredDate,
                            String timePrecision, OffsetDateTime reportedAt, String explicitVisitCode,
                            boolean dosingInterrupted, String sourceRef) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into activity_event(event_uid, center_id, external_subject_id, activity_type, activity_code,
                        occurred_at, occurred_date, time_precision, reported_at, explicit_visit_code,
                        dosing_interrupted, source_ref)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setString(1, eventUid);
            ps.setString(2, centerId);
            ps.setString(3, externalSubjectId);
            ps.setString(4, activityType);
            ps.setString(5, activityCode);
            ps.setTimestamp(6, occurredAt == null ? null : Timestamp.from(occurredAt.toInstant()));
            ps.setDate(7, occurredDate == null ? null : Date.valueOf(occurredDate));
            ps.setString(8, timePrecision);
            ps.setTimestamp(9, reportedAt == null ? null : Timestamp.from(reportedAt.toInstant()));
            ps.setString(10, explicitVisitCode);
            ps.setBoolean(11, dosingInterrupted);
            ps.setString(12, sourceRef);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<EventRow> listEvents() {
        return jdbc.query("select * from activity_event order by id", EVENT_MAPPER);
    }

    public void updateEventLink(long eventId, Long subjectId, String linkedVisitCode,
                                String linkStatus, String linkReason) {
        jdbc.update("update activity_event set subject_id=?, linked_visit_code=?, link_status=?, link_reason=? where id=?",
                subjectId, linkedVisitCode, linkStatus, linkReason, eventId);
    }

    public void insertEventCandidate(long eventId, String visitCode, String rationale) {
        jdbc.update("insert into event_link_candidate(event_id, visit_code, rationale) values(?,?,?)",
                eventId, visitCode, rationale);
    }

    public List<Map<String, Object>> listEventCandidates() {
        return jdbc.queryForList("select * from event_link_candidate order by event_id, visit_code");
    }

    // ---------- 判定与汇总（派生，全量重建） ----------
    public void clearDerived() {
        jdbc.update("delete from event_link_candidate");
        jdbc.update("delete from activity_judgment");
        jdbc.update("delete from visit_summary");
        jdbc.update("update activity_event set linked_visit_code=null, link_status=null, link_reason=null");
    }

    public void insertJudgment(long subjectId, String visitCode, String activityCode, String activityType,
                               boolean required, String status, Integer deviationDays, Integer overdueDays,
                               boolean lateReport, Integer reportLagDays, String versionCode,
                               LocalDate nominalDate, LocalDate windowStart, LocalDate windowEnd,
                               Long eventId, LocalDate occurredDate, String reason, String ruleVersion) {
        jdbc.update("""
                insert into activity_judgment(subject_id, visit_code, activity_code, activity_type, required,
                    status, deviation_days, overdue_days, late_report, report_lag_days, version_code,
                    nominal_date, window_start, window_end, event_id, occurred_date, reason, rule_version)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                subjectId, visitCode, activityCode, activityType, required, status, deviationDays, overdueDays,
                lateReport, reportLagDays, versionCode,
                nominalDate == null ? null : Date.valueOf(nominalDate),
                windowStart == null ? null : Date.valueOf(windowStart),
                windowEnd == null ? null : Date.valueOf(windowEnd),
                eventId, occurredDate == null ? null : Date.valueOf(occurredDate), reason, ruleVersion);
    }

    public void insertSummary(long subjectId, String visitCode, String versionCode, LocalDate nominalDate,
                              String status, int denominator, int onWindow, int early, int late, int notDone,
                              int indeterminate, int notApplicable, int maxDeviationDays, String ruleVersion) {
        jdbc.update("""
                insert into visit_summary(subject_id, visit_code, version_code, nominal_date, status, denominator,
                    on_window, early, late, not_done, indeterminate, not_applicable, max_deviation_days, rule_version)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                subjectId, visitCode, versionCode,
                nominalDate == null ? null : Date.valueOf(nominalDate),
                status, denominator, onWindow, early, late, notDone, indeterminate, notApplicable,
                maxDeviationDays, ruleVersion);
    }

    // ---------- 查询 ----------
    public List<Map<String, Object>> listJudgments(String subjectKey, String centerId, String visitCode,
                                                   String status, String activityType) {
        StringBuilder sql = new StringBuilder("""
                select j.*, s.subject_key, s.center_id, s.cohort
                from activity_judgment j join subject s on s.id = j.subject_id where true""");
        var args = new java.util.ArrayList<Object>();
        if (subjectKey != null) { sql.append(" and s.subject_key=?"); args.add(subjectKey); }
        if (centerId != null) { sql.append(" and s.center_id=?"); args.add(centerId); }
        if (visitCode != null) { sql.append(" and j.visit_code=?"); args.add(visitCode); }
        if (status != null) { sql.append(" and j.status=?"); args.add(status); }
        if (activityType != null) { sql.append(" and j.activity_type=?"); args.add(activityType); }
        sql.append(" order by s.subject_key, j.nominal_date, j.visit_code, j.activity_code");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> listSummaries(String subjectKey, String centerId, String status) {
        StringBuilder sql = new StringBuilder("""
                select v.*, s.subject_key, s.center_id, s.cohort
                from visit_summary v join subject s on s.id = v.subject_id where true""");
        var args = new java.util.ArrayList<Object>();
        if (subjectKey != null) { sql.append(" and s.subject_key=?"); args.add(subjectKey); }
        if (centerId != null) { sql.append(" and s.center_id=?"); args.add(centerId); }
        if (status != null) { sql.append(" and v.status=?"); args.add(status); }
        sql.append(" order by s.subject_key, v.nominal_date, v.visit_code");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Optional<Map<String, Object>> findSummary(long subjectId, String visitCode) {
        var list = jdbc.queryForList("select * from visit_summary where subject_id=? and visit_code=?",
                subjectId, visitCode);
        return list.stream().findFirst();
    }

    /** 研究团队执行差异分析：按白名单维度分组统计活动判定（alias -> 列 由调用方校验） */
    public List<Map<String, Object>> analysis(java.util.LinkedHashMap<String, String> dims) {
        String selectDims = dims.entrySet().stream()
                .map(e -> e.getValue() + " as " + e.getKey())
                .collect(java.util.stream.Collectors.joining(", "));
        String groupBy = String.join(", ", dims.values());
        String sql = """
                select %s,
                  count(*) filter (where j.required and j.status <> 'NOT_APPLICABLE') as denominator,
                  count(*) filter (where j.status = 'ON_WINDOW') as on_window,
                  count(*) filter (where j.status = 'EARLY') as early,
                  count(*) filter (where j.status = 'LATE') as late,
                  count(*) filter (where j.status = 'NOT_DONE') as not_done,
                  count(*) filter (where j.status = 'INDETERMINATE') as indeterminate,
                  count(*) filter (where j.status = 'NOT_APPLICABLE') as not_applicable,
                  coalesce(max(j.deviation_days) filter (where j.status in ('EARLY','LATE')), 0) as max_deviation_days,
                  count(*) filter (where j.late_report) as late_reports
                from activity_judgment j join subject s on s.id = j.subject_id
                group by %s order by %s""".formatted(selectDims, groupBy, groupBy);
        return jdbc.queryForList(sql);
    }
}
