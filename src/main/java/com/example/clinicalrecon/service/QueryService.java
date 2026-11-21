package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.repo.QueryRepo;
import com.example.clinicalrecon.repo.ReconRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 质疑闭环：回复（记录对应事实版本）→ 关闭（锚定版本基线）→ 源再更正自动重开。 */
@Service
public class QueryService {
    private final QueryRepo queryRepo;
    private final ReconRepo reconRepo;

    public QueryService(QueryRepo queryRepo, ReconRepo reconRepo) {
        this.queryRepo = queryRepo;
        this.reconRepo = reconRepo;
    }

    public List<QueryRepo.QueryRow> list(String status) {
        return queryRepo.listQueries(status);
    }

    public Map<String, Object> detail(long id) {
        var q = queryRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("质疑不存在: " + id));
        return Map.of("query", q, "responses", queryRepo.responsesOf(id));
    }

    /** 回复质疑：记录回复对应的协调事实版本与来源修订签名 */
    @Transactional
    public long respond(long queryId, Dtos.ResponseIn in) {
        var q = queryRepo.findById(queryId)
                .orElseThrow(() -> new NotFoundException("质疑不存在: " + queryId));
        if (Enums.Query.CLOSED.equals(q.status())) {
            throw new BadRequestException("质疑已关闭，不能回复；如需请等待重开或新建");
        }
        Integer version = null;
        String sig = null;
        if (q.reconciledFactId() != null) {
            var row = reconRepo.findById(q.reconciledFactId()).orElse(null);
            if (row != null) { version = row.version(); sig = row.sourceSig(); }
        }
        long rid = queryRepo.insertResponse(queryId, UserContext.user(), UserContext.role(),
                in.responseText(), in.evidenceRef(), version, sig);
        if (version != null) {
            queryRepo.markAnswered(queryId, version);
        }
        return rid;
    }

    /** 关闭质疑：锚定当前协调版本为基线；此后源事实再更正将自动重开 */
    @Transactional
    public void close(long queryId, Dtos.CloseIn in) {
        var q = queryRepo.findById(queryId)
                .orElseThrow(() -> new NotFoundException("质疑不存在: " + queryId));
        if (Enums.Query.CLOSED.equals(q.status())) {
            throw new BadRequestException("质疑已是关闭状态");
        }
        Integer version = null;
        String sig = null;
        if (q.reconciledFactId() != null) {
            var row = reconRepo.findById(q.reconciledFactId()).orElse(null);
            if (row != null) { version = row.version(); sig = row.sourceSig(); }
        }
        if (in != null && in.resolution() != null && !in.resolution().isBlank()) {
            queryRepo.insertResponse(queryId, UserContext.user(), UserContext.role(),
                    "关闭结论: " + in.resolution(), null, version, sig);
        }
        queryRepo.close(queryId, UserContext.user(), version);
    }
}
