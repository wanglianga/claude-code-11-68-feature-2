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

/** 协调层：协调运行序号、协调事实（当前态）、来源关联、版本历史（追加式）。 */
@Repository
public class ReconRepo {
    private final JdbcTemplate jdbc;

    public ReconRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ReconRow(long id, Long subjectId, String visitCode, String fieldKey,
                           String status, String confirmedValue, String ruleCode, String ruleVersion,
                           String overrideValue, String overrideSig, String sourceSig,
                           int version, long seq, OffsetDateTime updatedAt) {}

    public static final RowMapper<ReconRow> RECON_MAPPER = (rs, i) -> new ReconRow(
            rs.getLong("id"),
            rs.getObject("subject_id") == null ? null : rs.getLong("subject_id"),
            rs.getString("visit_code"), rs.getString("field_key"),
            rs.getString("status"), rs.getString("confirmed_value"),
            rs.getString("rule_code"), rs.getString("rule_version"),
            rs.getString("override_value"), rs.getString("override_sig"), rs.getString("source_sig"),
            rs.getInt("version"), rs.getLong("seq"),
            rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));

    public long nextRun(String note) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into reconcile_run(trigger_note) values(?)", new String[]{"seq"});
            ps.setString(1, note);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void finishRun(long seq, int changedGroups) {
        jdbc.update("update reconcile_run set changed_groups=? where seq=?", changedGroups, seq);
    }

    public long currentSeq() {
        Long v = jdbc.queryForObject("select coalesce(max(seq), 0) from reconcile_run", Long.class);
        return v == null ? 0 : v;
    }

    /** 空安全查找逻辑组 */
    public Optional<ReconRow> findGroup(Long subjectId, String visitCode, String fieldKey) {
        var list = jdbc.query("""
                select * from reconciled_fact
                where coalesce(subject_id,-1)=coalesce(cast(? as bigint), -1)
                  and coalesce(visit_code,'')=coalesce(cast(? as text), '')
                  and field_key=?""",
                RECON_MAPPER, subjectId, visitCode, fieldKey);
        return list.stream().findFirst();
    }

    public Optional<ReconRow> findById(long id) {
        var list = jdbc.query("select * from reconciled_fact where id=?", RECON_MAPPER, id);
        return list.stream().findFirst();
    }

    public long insertRecon(ReconRow r) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into reconciled_fact(subject_id, visit_code, field_key, status, confirmed_value,
                        rule_code, rule_version, override_value, override_sig, source_sig, version, seq)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setObject(1, r.subjectId());
            ps.setString(2, r.visitCode());
            ps.setString(3, r.fieldKey());
            ps.setString(4, r.status());
            ps.setString(5, r.confirmedValue());
            ps.setString(6, r.ruleCode());
            ps.setString(7, r.ruleVersion());
            ps.setString(8, r.overrideValue());
            ps.setString(9, r.overrideSig());
            ps.setString(10, r.sourceSig());
            ps.setInt(11, r.version());
            ps.setLong(12, r.seq());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void updateRecon(long id, String status, String confirmedValue, String ruleCode, String ruleVersion,
                            String overrideValue, String overrideSig, String sourceSig, int version, long seq) {
        jdbc.update("""
                update reconciled_fact set status=?, confirmed_value=?, rule_code=?, rule_version=?,
                    override_value=?, override_sig=?, source_sig=?, version=?, seq=?, updated_at=now()
                where id=?""",
                status, confirmedValue, ruleCode, ruleVersion, overrideValue, overrideSig, sourceSig,
                version, seq, id);
    }

    public void deleteRecon(long id) {
        jdbc.update("delete from reconciled_fact_source where reconciled_fact_id=?", id);
        jdbc.update("delete from reconciled_fact where id=?", id);
    }

    public void insertHistory(long reconId, int version, long seq, String status,
                              String confirmedValue, String ruleCode, String ruleVersion) {
        jdbc.update("""
                insert into reconciled_fact_history(reconciled_fact_id, version, seq, status, confirmed_value, rule_code, rule_version)
                values(?,?,?,?,?,?,?)
                on conflict (reconciled_fact_id, version) do nothing""",
                reconId, version, seq, status, confirmedValue, ruleCode, ruleVersion);
    }

    public List<Map<String, Object>> historyOf(long reconId) {
        return jdbc.queryForList(
                "select version, seq, status, confirmed_value, rule_code, rule_version, created_at from reconciled_fact_history where reconciled_fact_id=? order by version",
                reconId);
    }

    public void replaceSources(long reconId, List<long[]> factRoles, List<String> roles) {
        jdbc.update("delete from reconciled_fact_source where reconciled_fact_id=?", reconId);
        for (int i = 0; i < factRoles.size(); i++) {
            jdbc.update("""
                    insert into reconciled_fact_source(reconciled_fact_id, raw_fact_id, role)
                    values(?,?,?)
                    on conflict (reconciled_fact_id, raw_fact_id) do nothing""",
                    reconId, factRoles.get(i)[0], roles.get(i));
        }
    }

    public void addSource(long reconId, long rawFactId, String role) {
        jdbc.update("""
                insert into reconciled_fact_source(reconciled_fact_id, raw_fact_id, role)
                values(?,?,?)
                on conflict (reconciled_fact_id, raw_fact_id) do nothing""",
                reconId, rawFactId, role);
    }

    public List<Map<String, Object>> sourcesOf(long reconId) {
        return jdbc.queryForList("""
                select s.role, f.id as raw_fact_id, f.source_system, f.source_record_id, f.source_revision,
                       f.source_field_code, f.field_key, f.value_text, f.value_num, f.unit,
                       f.event_time, f.event_date, f.time_precision, f.valid, f.invalid_reason,
                       f.received_at, f.payload_summary,
                       b.id as batch_id, b.batch_label, b.payload_digest, b.received_at as batch_received_at,
                       b.center_id as batch_center_id
                from reconciled_fact_source s
                join raw_fact f on f.id = s.raw_fact_id
                join source_batch b on b.id = f.batch_id
                where s.reconciled_fact_id=?
                order by f.id""", reconId);
    }

    public List<ReconRow> listRecon(String status) {
        if (status == null) {
            return jdbc.query("select * from reconciled_fact order by id", RECON_MAPPER);
        }
        return jdbc.query("select * from reconciled_fact where status=? order by id", RECON_MAPPER, status);
    }

    public List<ReconRow> allRecon() {
        return jdbc.query("select * from reconciled_fact order by id", RECON_MAPPER);
    }

    public Map<String, Long> countByStatus() {
        var rows = jdbc.queryForList("select status, count(*) c from reconciled_fact group by status");
        var out = new java.util.LinkedHashMap<String, Long>();
        for (var r : rows) out.put((String) r.get("status"), ((Number) r.get("c")).longValue());
        return out;
    }

    /** 快照复算：截至某协调序号，每条协调事实的最新历史版本 */
    public List<Map<String, Object>> historyAsOf(long seq) {
        return jdbc.queryForList("""
                select h.reconciled_fact_id, h.version, h.status, h.confirmed_value, h.rule_code
                from reconciled_fact_history h
                join (select reconciled_fact_id, max(version) v from reconciled_fact_history
                      where seq <= ? group by reconciled_fact_id) latest
                  on latest.reconciled_fact_id = h.reconciled_fact_id and latest.v = h.version
                """, seq);
    }
}
