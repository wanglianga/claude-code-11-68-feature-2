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

/** 关联层：候选关联与已确认关联。 */
@Repository
public class LinkRepo {
    private final JdbcTemplate jdbc;

    public LinkRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CandidateRow(long id, long rawFactId, long subjectId, String visitCode,
                               String confidence, String rationale, String status,
                               String decidedBy, OffsetDateTime decidedAt) {}

    public record FactLinkRow(long rawFactId, long subjectId, String visitCode, Long candidateId, String decidedBy) {}

    public static final RowMapper<CandidateRow> CAND_MAPPER = (rs, i) -> new CandidateRow(
            rs.getLong("id"), rs.getLong("raw_fact_id"), rs.getLong("subject_id"), rs.getString("visit_code"),
            rs.getString("confidence"), rs.getString("rationale"), rs.getString("status"),
            rs.getString("decided_by"),
            rs.getTimestamp("decided_at") == null ? null
                    : rs.getTimestamp("decided_at").toInstant().atOffset(java.time.ZoneOffset.UTC));

    public long insertCandidate(long rawFactId, long subjectId, String visitCode,
                                String confidence, String rationale) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into link_candidate(raw_fact_id, subject_id, visit_code, confidence, rationale)
                    values(?,?,?,?,?)
                    on conflict (raw_fact_id, subject_id, visit_code) do nothing""",
                    new String[]{"id"});
            ps.setLong(1, rawFactId);
            ps.setLong(2, subjectId);
            ps.setString(3, visitCode);
            ps.setString(4, confidence);
            ps.setString(5, rationale);
            return ps;
        }, kh);
        return kh.getKey() == null ? -1 : kh.getKey().longValue();
    }

    public List<CandidateRow> candidatesOfFact(long rawFactId) {
        return jdbc.query("select * from link_candidate where raw_fact_id=? order by id", CAND_MAPPER, rawFactId);
    }

    public List<CandidateRow> listCandidates(String status) {
        if (status == null) {
            return jdbc.query("select * from link_candidate order by id", CAND_MAPPER);
        }
        return jdbc.query("select * from link_candidate where status=? order by id", CAND_MAPPER, status);
    }

    public Optional<CandidateRow> findCandidate(long id) {
        var list = jdbc.query("select * from link_candidate where id=?", CAND_MAPPER, id);
        return list.stream().findFirst();
    }

    public void decideCandidate(long id, String status, String decidedBy) {
        jdbc.update("update link_candidate set status=?, decided_by=?, decided_at=now() where id=?",
                status, decidedBy, id);
    }

    public void insertFactLink(long rawFactId, long subjectId, String visitCode, Long candidateId, String decidedBy) {
        jdbc.update("""
                insert into fact_link(raw_fact_id, subject_id, visit_code, candidate_id, decided_by)
                values(?,?,?,?,?)
                on conflict (raw_fact_id) do nothing""",
                rawFactId, subjectId, visitCode, candidateId, decidedBy);
    }

    public Optional<FactLinkRow> findLink(long rawFactId) {
        var list = jdbc.query("select * from fact_link where raw_fact_id=?",
                (rs, i) -> new FactLinkRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5)), rawFactId);
        return list.stream().findFirst();
    }

    /** 全部已确认关联（含无效/被取代事实，供协调器标注 SUPERSEDED 角色） */
    public List<FactLinkRow> allLinks() {
        return jdbc.query("select * from fact_link",
                (rs, i) -> new FactLinkRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5)));
    }

    public List<Map<String, Object>> listLinksView() {
        return jdbc.queryForList("""
                select fl.raw_fact_id, s.subject_key, fl.visit_code, fl.decided_by, fl.created_at
                from fact_link fl join subject s on s.id=fl.subject_id order by fl.raw_fact_id""");
    }
}
