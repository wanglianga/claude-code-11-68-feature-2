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

/** 质疑数据集：质疑项与回复证据。 */
@Repository
public class QueryRepo {
    private final JdbcTemplate jdbc;

    public QueryRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record QueryRow(long id, Long reconciledFactId, Long rawFactId, String issueType,
                           String title, String detail, String assigneeRole, String status,
                           OffsetDateTime openedAt, String openedBy,
                           OffsetDateTime closedAt, String closedBy, Integer lastKnownFactVersion) {}

    public static final RowMapper<QueryRow> QUERY_MAPPER = (rs, i) -> new QueryRow(
            rs.getLong("id"),
            rs.getObject("reconciled_fact_id") == null ? null : rs.getLong("reconciled_fact_id"),
            rs.getObject("raw_fact_id") == null ? null : rs.getLong("raw_fact_id"),
            rs.getString("issue_type"), rs.getString("title"), rs.getString("detail"),
            rs.getString("assignee_role"), rs.getString("status"),
            rs.getTimestamp("opened_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getString("opened_by"),
            rs.getTimestamp("closed_at") == null ? null
                    : rs.getTimestamp("closed_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getString("closed_by"),
            rs.getObject("last_known_fact_version") == null ? null : rs.getInt("last_known_fact_version"));

    public long insertQuery(Long reconFactId, Long rawFactId, String issueType, String title,
                            String detail, String assigneeRole, String openedBy, Integer lastKnownFactVersion) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into query_item(reconciled_fact_id, raw_fact_id, issue_type, title, detail,
                        assignee_role, opened_by, last_known_fact_version)
                    values(?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setObject(1, reconFactId);
            ps.setObject(2, rawFactId);
            ps.setString(3, issueType);
            ps.setString(4, title);
            ps.setString(5, detail);
            ps.setString(6, assigneeRole);
            ps.setString(7, openedBy);
            ps.setObject(8, lastKnownFactVersion);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<QueryRow> findById(long id) {
        var list = jdbc.query("select * from query_item where id=?", QUERY_MAPPER, id);
        return list.stream().findFirst();
    }

    public List<QueryRow> listQueries(String status) {
        if (status == null) return jdbc.query("select * from query_item order by id", QUERY_MAPPER);
        return jdbc.query("select * from query_item where status=? order by id", QUERY_MAPPER, status);
    }

    /** 是否已有同一对象的未关闭质疑（OPEN/ANSWERED/REOPENED 视为活跃） */
    public boolean hasActiveQueryForRecon(long reconFactId, String issueType) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from query_item
                  where reconciled_fact_id=? and issue_type=? and status in ('OPEN','ANSWERED','REOPENED'))""",
                Boolean.class, reconFactId, issueType));
    }

    public boolean hasActiveQueryForFact(long rawFactId, String issueType) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from query_item
                  where raw_fact_id=? and issue_type=? and status in ('OPEN','ANSWERED','REOPENED'))""",
                Boolean.class, rawFactId, issueType));
    }

    public List<QueryRow> activeQueriesForFact(long rawFactId, String issueType) {
        return jdbc.query("""
                select * from query_item where raw_fact_id=? and issue_type=?
                and status in ('OPEN','ANSWERED','REOPENED') order by id""",
                QUERY_MAPPER, rawFactId, issueType);
    }

    public List<QueryRow> activeQueriesForRecon(long reconFactId) {
        return jdbc.query("""
                select * from query_item where reconciled_fact_id=?
                and status in ('OPEN','ANSWERED','REOPENED') order by id""",
                QUERY_MAPPER, reconFactId);
    }

    public void setStatus(long id, String status) {
        jdbc.update("update query_item set status=? where id=?", status, id);
    }

    public void markAnswered(long id, int factVersion) {
        jdbc.update("update query_item set status='ANSWERED', last_known_fact_version=? where id=?",
                factVersion, id);
    }

    public void close(long id, String closedBy, Integer factVersion) {
        jdbc.update("""
                update query_item set status='CLOSED', closed_at=now(), closed_by=?, last_known_fact_version=?
                where id=?""", closedBy, factVersion, id);
    }

    /** 协调版本超过基线的已关闭/已回复质疑 -> 重新打开 */
    public List<QueryRow> findReopenable(long reconFactId, int newVersion) {
        return jdbc.query("""
                select * from query_item
                where reconciled_fact_id=? and status in ('CLOSED','ANSWERED')
                  and last_known_fact_version is not null and last_known_fact_version < ?""",
                QUERY_MAPPER, reconFactId, newVersion);
    }

    public long insertResponse(long queryId, String responder, String role, String text,
                               String evidenceRef, Integer factVersion, String sourceSig) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into query_response(query_id, responder, responder_role, response_text,
                        evidence_ref, fact_version_at_reply, source_sig_at_reply)
                    values(?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, queryId);
            ps.setString(2, responder);
            ps.setString(3, role);
            ps.setString(4, text);
            ps.setString(5, evidenceRef);
            ps.setObject(6, factVersion);
            ps.setString(7, sourceSig);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<Map<String, Object>> responsesOf(long queryId) {
        return jdbc.queryForList("select * from query_response where query_id=? order by id", queryId);
    }

    public List<Map<String, Object>> queriesOfRecon(long reconFactId) {
        return jdbc.queryForList("select * from query_item where reconciled_fact_id=? order by id", reconFactId);
    }
}
