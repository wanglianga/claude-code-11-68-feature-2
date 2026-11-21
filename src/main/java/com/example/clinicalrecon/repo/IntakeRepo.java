package com.example.clinicalrecon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 受理仓：来源批次与原始事实（含修订取代）。 */
@Repository
public class IntakeRepo {
    private final JdbcTemplate jdbc;

    public IntakeRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record RawFactRow(long id, long batchId, String sourceSystem, String sourceRecordId, int sourceRevision,
                             String centerId, String externalSubjectId, String externalVisitCode,
                             String sourceFieldCode, String fieldKey, String mappingStatus,
                             String valueText, BigDecimal valueNum, String unit,
                             OffsetDateTime eventTime, LocalDate eventDate, String timePrecision,
                             String payloadSummary, OffsetDateTime receivedAt,
                             boolean valid, String invalidReason, Long supersededBy, String linkStatus) {
        /** 统一字段上的规范化取值：数值+单位 或 文本 */
        public String normalizedValue() {
            if (valueNum != null) {
                String n = valueNum.stripTrailingZeros().toPlainString();
                return unit != null && !unit.isBlank() ? n + " " + unit : n;
            }
            return valueText;
        }
    }

    public static final RowMapper<RawFactRow> RAW_MAPPER = (rs, i) -> new RawFactRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("source_system"),
            rs.getString("source_record_id"), rs.getInt("source_revision"),
            rs.getString("center_id"), rs.getString("external_subject_id"), rs.getString("external_visit_code"),
            rs.getString("source_field_code"), rs.getString("field_key"), rs.getString("mapping_status"),
            rs.getString("value_text"), rs.getBigDecimal("value_num"), rs.getString("unit"),
            rs.getTimestamp("event_time") == null ? null : rs.getTimestamp("event_time").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getDate("event_date") == null ? null : rs.getDate("event_date").toLocalDate(),
            rs.getString("time_precision"), rs.getString("payload_summary"),
            rs.getTimestamp("received_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getBoolean("valid"), rs.getString("invalid_reason"),
            rs.getObject("superseded_by") == null ? null : rs.getLong("superseded_by"),
            rs.getString("link_status"));

    public Optional<Map<String, Object>> findBatch(String sourceSystem, String centerId, String batchLabel) {
        var list = jdbc.queryForList(
                "select * from source_batch where source_system=? and center_id=? and batch_label=?",
                sourceSystem, centerId, batchLabel);
        return list.stream().findFirst();
    }

    public long insertBatch(String sourceSystem, String centerId, String batchLabel,
                            OffsetDateTime receivedAt, String digest) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into source_batch(source_system, center_id, batch_label, received_at, payload_digest) values(?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, sourceSystem);
            ps.setString(2, centerId);
            ps.setString(3, batchLabel);
            ps.setTimestamp(4, receivedAt == null ? Timestamp.from(java.time.Instant.now())
                    : Timestamp.from(receivedAt.toInstant()));
            ps.setString(5, digest);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void updateBatchCount(long batchId, int count) {
        jdbc.update("update source_batch set fact_count=? where id=?", count, batchId);
    }

    public List<Map<String, Object>> listBatches() {
        return jdbc.queryForList("select * from source_batch order by id");
    }

    /** 同一来源记录号的全部修订（按修订号升序） */
    public List<RawFactRow> findByRecord(String sourceSystem, String sourceRecordId) {
        return jdbc.query("select * from raw_fact where source_system=? and source_record_id=? order by source_revision",
                RAW_MAPPER, sourceSystem, sourceRecordId);
    }

    public Optional<RawFactRow> findById(long id) {
        var list = jdbc.query("select * from raw_fact where id=?", RAW_MAPPER, id);
        return list.stream().findFirst();
    }

    public long insertRawFact(RawFactRow f) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into raw_fact(batch_id, source_system, source_record_id, source_revision, center_id,
                        external_subject_id, external_visit_code, source_field_code, field_key, mapping_status,
                        value_text, value_num, unit, event_time, event_date, time_precision, payload_summary,
                        valid, invalid_reason, link_status)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, f.batchId());
            ps.setString(2, f.sourceSystem());
            ps.setString(3, f.sourceRecordId());
            ps.setInt(4, f.sourceRevision());
            ps.setString(5, f.centerId());
            ps.setString(6, f.externalSubjectId());
            ps.setString(7, f.externalVisitCode());
            ps.setString(8, f.sourceFieldCode());
            ps.setString(9, f.fieldKey());
            ps.setString(10, f.mappingStatus());
            ps.setString(11, f.valueText());
            ps.setBigDecimal(12, f.valueNum());
            ps.setString(13, f.unit());
            ps.setTimestamp(14, f.eventTime() == null ? null : Timestamp.from(f.eventTime().toInstant()));
            ps.setDate(15, f.eventDate() == null ? null : java.sql.Date.valueOf(f.eventDate()));
            ps.setString(16, f.timePrecision());
            ps.setString(17, f.payloadSummary());
            ps.setBoolean(18, f.valid());
            ps.setString(19, f.invalidReason());
            ps.setString(20, f.linkStatus());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void markSuperseded(long oldId, long newId, int newRevision) {
        jdbc.update("update raw_fact set valid=false, invalid_reason=?, superseded_by=? where id=?",
                "SUPERSEDED_BY_REV_" + newRevision, newId, oldId);
    }

    public void markStale(long id, String reason) {
        jdbc.update("update raw_fact set valid=false, invalid_reason=? where id=?", reason, id);
    }

    public void updateLinkStatus(long id, String linkStatus) {
        jdbc.update("update raw_fact set link_status=? where id=?", linkStatus, id);
    }

    public List<RawFactRow> listRawFacts(String sourceSystem, Boolean validOnly) {
        StringBuilder sql = new StringBuilder("select * from raw_fact where true");
        var args = new java.util.ArrayList<Object>();
        if (sourceSystem != null) {
            sql.append(" and source_system=?");
            args.add(sourceSystem);
        }
        if (validOnly != null && validOnly) {
            sql.append(" and valid");
        }
        sql.append(" order by id");
        return jdbc.query(sql.toString(), RAW_MAPPER, args.toArray());
    }
}
