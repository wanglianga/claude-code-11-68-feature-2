package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.Dtos;
import com.example.clinicalrecon.repo.QueryRepo;
import com.example.clinicalrecon.service.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/queries")
@Tag(name = "质疑", description = "质疑数据集：责任角色、回复证据、按事实版本关闭与自动重开")
public class QueryController {
    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "质疑列表", description = "可按状态过滤 OPEN/ANSWERED/CLOSED/REOPENED")
    public List<QueryRepo.QueryRow> list(@RequestParam(required = false) String status) {
        return queryService.list(status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "质疑详情", description = "含全部回复；每条回复记录其对应的协调事实版本与来源修订签名")
    @SuppressWarnings("unchecked")
    public Map<String, Object> detail(@PathVariable long id) {
        var detail = queryService.detail(id);
        return Map.of("query", detail.get("query"),
                "responses", JsonMaps.camel((List<Map<String, Object>>) detail.get("responses")));
    }

    @PostMapping("/{id}/responses")
    @Operation(summary = "回复质疑", description = "回复人/角色取自 X-User/X-Role 头；记录回复对应的事实版本")
    public Map<String, Object> respond(@PathVariable long id, @Valid @RequestBody Dtos.ResponseIn in) {
        long rid = queryService.respond(id, in);
        return Map.of("responseId", rid, "message", "已记录回复");
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭质疑", description = "锚定当前协调版本为关闭基线；此后源事实再更正将自动重开该质疑")
    public Map<String, Object> close(@PathVariable long id, @RequestBody(required = false) Dtos.CloseIn in) {
        queryService.close(id, in);
        return Map.of("message", "质疑已关闭");
    }
}
