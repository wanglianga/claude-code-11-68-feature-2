package com.example.clinicalrecon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 分析快照：头表与物化行（物化后不再变化，保证旧快照可复现）。 */
@Repository
public class SnapshotRepo {
    private final JdbcTemplate jdbc;

    public SnapshotRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SnapshotRow(long id, String name, OffsetDateTime createdAt, String createdBy,
                              String gateJson, long seq, int rowCount, String excludedJson) {}

    public static final RowMapper<SnapshotRow> SNAP_MAPPER = (rs, i) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("name"),
            rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getString("created_by"), rs.getString("gate"), rs.getLong("seq"),
            rs.getInt("row_count"), rs.getString("excluded"));

    public long insertSnapshot(String name, String createdBy, String gateJson, long seq,
                               int rowCount, String excludedJson) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into analysis_snapshot(name, created_by, gate, seq, row_count, excluded)
                    values(?,?,?::jsonb,?,?,?::jsonb)""", new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, createdBy);
            ps.setString(3, gateJson);
            ps.setLong(4, seq);
            ps.setInt(5, rowCount);
            ps.setString(6, excludedJson);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void insertRow(long snapshotId, Long subjectId, String visitCode, String fieldKey,
                          String value, long reconFactId, int reconVersion, String ruleCode) {
        jdbc.update("""
                insert into analysis_snapshot_row(snapshot_id, subject_id, visit_code, field_key, value,
                    reconciled_fact_id, reconciled_version, rule_code)
                values(?,?,?,?,?,?,?,?)""",
                snapshotId, subjectId, visitCode, fieldKey, value, reconFactId, reconVersion, ruleCode);
    }

    public Optional<SnapshotRow> findById(long id) {
        var list = jdbc.query("select * from analysis_snapshot where id=?", SNAP_MAPPER, id);
        return list.stream().findFirst();
    }

    public List<SnapshotRow> list() {
        return jdbc.query("select * from analysis_snapshot order by id", SNAP_MAPPER);
    }

    public List<Map<String, Object>> rowsOf(long snapshotId) {
        return jdbc.queryForList("""
                select r.id, r.subject_id, s.subject_key, r.visit_code, r.field_key, r.value,
                       r.reconciled_fact_id, r.reconciled_version, r.rule_code
                from analysis_snapshot_row r
                left join subject s on s.id = r.subject_id
                where r.snapshot_id=? order by r.id""", snapshotId);
    }
}
