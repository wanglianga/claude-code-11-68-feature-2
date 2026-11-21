package com.example.clinicalrecon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 主数据：受试者、中心映射、访视窗口、字段目录。 */
@Repository
public class MasterDataRepo {
    private final JdbcTemplate jdbc;

    public MasterDataRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SubjectRow(long id, String trialId, String centerId, String subjectKey, LocalDate enrolledDate,
                             String cohort) {}
    public record VisitWindow(String visitCode, int anchorDay, int windowStart, int windowEnd) {}
    public record FieldDef(String fieldKey, String label, String valueType, String unit,
                           boolean requiredForAnalysis, Map<String, List<String>> sourceFieldMap) {}

    private static final RowMapper<SubjectRow> SUBJECT_MAPPER = (rs, i) -> new SubjectRow(
            rs.getLong("id"), rs.getString("trial_id"), rs.getString("center_id"), rs.getString("subject_key"),
            rs.getDate("enrolled_date").toLocalDate(), rs.getString("cohort"));

    public Optional<SubjectRow> findSubjectByKey(String key) {
        var list = jdbc.query("select * from subject where subject_key=?", SUBJECT_MAPPER, key);
        return list.stream().findFirst();
    }

    public Optional<SubjectRow> findSubjectById(long id) {
        var list = jdbc.query("select * from subject where id=?", SUBJECT_MAPPER, id);
        return list.stream().findFirst();
    }

    public List<SubjectRow> listSubjectRows() {
        return jdbc.query("select * from subject order by subject_key", SUBJECT_MAPPER);
    }

    /** 中心映射：来源中心+外部受试者号 -> 统一受试者 */
    public List<SubjectRow> mapSubject(String centerId, String externalSubjectId) {
        return jdbc.query("""
                select s.* from center_subject_map m join subject s on s.id = m.subject_id
                where m.center_id=? and m.external_subject_id=?""",
                SUBJECT_MAPPER, centerId, externalSubjectId);
    }

    public List<VisitWindow> scheduleOf(String trialId) {
        return jdbc.query("select visit_code, anchor_day, window_start, window_end from visit_schedule where trial_id=? order by anchor_day",
                (rs, i) -> new VisitWindow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), trialId);
    }

    public boolean visitExists(String trialId, String visitCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from visit_schedule where trial_id=? and visit_code=?)",
                Boolean.class, trialId, visitCode));
    }

    public List<FieldDef> fieldCatalog() {
        return jdbc.query("select field_key, label, value_type, unit, required_for_analysis, source_field_map from field_catalog order by field_key",
                (rs, i) -> new FieldDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), parseMap(rs.getString(6))));
    }

    public List<FieldDef> requiredFields() {
        return jdbc.query("select field_key, label, value_type, unit, required_for_analysis, source_field_map from field_catalog where required_for_analysis",
                (rs, i) -> new FieldDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), parseMap(rs.getString(6))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> parseMap(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public List<Map<String, Object>> listSubjects() {
        return jdbc.queryForList("select id, trial_id, center_id, subject_key, enrolled_date, cohort from subject order by subject_key");
    }

    public List<Map<String, Object>> listSchedule() {
        return jdbc.queryForList("select trial_id, visit_code, anchor_day, window_start, window_end from visit_schedule order by trial_id, anchor_day");
    }

    public List<Map<String, Object>> listCenterMap() {
        return jdbc.queryForList("""
                select m.center_id, m.external_subject_id, s.subject_key
                from center_subject_map m join subject s on s.id=m.subject_id
                order by m.center_id, m.external_subject_id""");
    }
}
