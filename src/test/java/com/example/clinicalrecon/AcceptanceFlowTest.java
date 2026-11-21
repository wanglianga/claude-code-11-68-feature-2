package com.example.clinicalrecon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验收流程：事实匹配、冲突保留、质疑闭环、分析门槛、旧快照复现、字段级回溯。
 * 需要本地 PostgreSQL（127.0.0.1:5432, ccdev/ccdev）且已建库 recon_test。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AcceptanceFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private JsonNode get(String url) throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode post(String url, String body, String user, String role) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        if (user != null) req.header("X-User", user);
        if (role != null) req.header("X-Role", role);
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private static List<JsonNode> toList(JsonNode arr) {
        var out = new ArrayList<JsonNode>();
        arr.forEach(out::add);
        return out;
    }

    private JsonNode findRecon(String status, String fieldKey, String subjectKey, String visitCode) throws Exception {
        for (JsonNode n : get("/api/reconciled-facts?status=" + status)) {
            boolean ok = n.get("fieldKey").asText().equals(fieldKey)
                    && (subjectKey == null || (n.hasNonNull("subjectKey") && n.get("subjectKey").asText().equals(subjectKey)))
                    && (visitCode == null ? !n.hasNonNull("visitCode")
                        : n.hasNonNull("visitCode") && n.get("visitCode").asText().equals(visitCode));
            if (ok) return n;
        }
        return null;
    }

    @Test
    @Order(1)
    void acceptanceFlow() throws Exception {
        // ---------- 种子 ----------
        post("/api/demo/seed", null, "admin", "ADMIN");

        // ---------- 1. 事实匹配：显式访视/日期精度单窗口自动关联 ----------
        JsonNode links = get("/api/links");
        assertTrue(toList(links).size() >= 6, "自动关联应覆盖无歧义事实");

        // 影像检查仅日期精度且窗口跨边界 → 两个候选，不擅自分配
        JsonNode candidates = get("/api/link-candidates?status=CANDIDATE");
        List<JsonNode> imagingCands = toList(candidates);
        assertEquals(2, imagingCands.size(), "跨边界影像检查应产生2个候选访视");
        var visits = imagingCands.stream().map(c -> c.get("visitCode").asText()).sorted().toList();
        assertEquals(List.of("V2", "V3"), visits);
        assertTrue(imagingCands.stream().allMatch(c -> c.get("confidence").asText().equals("MEDIUM")));
        assertTrue(imagingCands.get(0).get("rationale").asText().contains("边界"));
        // 该事实未被擅自分配
        JsonNode rawFacts = get("/api/raw-facts?sourceSystem=IMAGING");
        assertEquals("PENDING_DECISION", rawFacts.get(0).get("linkStatus").asText());

        // ---------- 2. 冲突保留：同一访视两条互斥检验结果 ----------
        JsonNode conflict = findRecon("CONFLICT", "LAB_ALT", "SUBJ-001", "V2");
        assertNotNull(conflict, "35 vs 52 应置 CONFLICT");
        assertFalse(conflict.hasNonNull("confirmedValue"), "冲突字段不得给出确认值");
        assertEquals("MUTUALLY_EXCLUSIVE", conflict.get("ruleCode").asText());
        long conflictId = conflict.get("id").asLong();

        JsonNode trace = get("/api/reconciled-facts/" + conflictId + "/trace");
        List<JsonNode> srcs = toList(trace.get("sources"));
        assertEquals(2, srcs.size(), "互斥双方修订关系都应保留");
        assertTrue(srcs.stream().allMatch(s -> s.get("role").asText().equals("CONFLICTING")));
        assertTrue(srcs.stream().allMatch(s -> s.get("valid").asBoolean()));
        assertTrue(srcs.stream().allMatch(s -> s.get("payloadDigest") != null), "来源应含批次载荷摘要");

        // 冲突自动开质疑，责任角色 DM
        JsonNode queries = get("/api/queries?status=OPEN");
        JsonNode conflictQuery = toList(queries).stream()
                .filter(q -> q.get("issueType").asText().equals("CONFLICT")).findFirst().orElseThrow();
        assertEquals("DM", conflictQuery.get("assigneeRole").asText());
        long conflictQueryId = conflictQuery.get("id").asLong();

        // ---------- 3. 未映射保持待判 + 缺失发布 ----------
        assertNotNull(findRecon("PENDING", "UNMAPPED::CRF::XXMOOD", "SUBJ-001", "V2"), "目录外字段应保持待判");
        assertNotNull(findRecon("PENDING", "TUMOR_LD_SUM", "SUBJ-001", null), "未裁定关联应保持待判");
        assertNotNull(findRecon("MISSING", "VITAL_DIA_BP", "SUBJ-001", "V2"), "必需字段无来源应发布缺失");

        // ---------- 4. 分析门槛：快照只消费 CONFIRMED ----------
        JsonNode snap1 = post("/api/snapshots", "{\"name\":\"IA-1\"}", "dm1", "DM");
        long snap1Id = snap1.get("id").asLong();
        JsonNode snap1Rows = get("/api/snapshots/" + snap1Id + "/rows");
        assertTrue(toList(snap1Rows).size() > 0);
        assertTrue(toList(snap1Rows).stream().noneMatch(r -> r.get("fieldKey").asText().equals("LAB_ALT")
                        && r.get("subjectKey").asText().equals("SUBJ-001")),
                "冲突字段不得进入分析快照");
        assertTrue(toList(snap1Rows).stream().anyMatch(r -> r.get("fieldKey").asText().equals("VITAL_SYS_BP")
                && r.get("value").asText().startsWith("120")));
        JsonNode snap1Meta = get("/api/snapshots");
        assertNotNull(snap1Meta);

        // ---------- 5. 人工裁定 → 质疑回复/关闭 → 源再更正 → 自动重开 ----------
        post("/api/reconciled-facts/" + conflictId + "/resolve",
                "{\"value\":\"36 U/L\",\"rationale\":\"以中心实验室复查值为准\"}", "dm1", "DM");
        JsonNode resolved = findRecon("CONFIRMED", "LAB_ALT", "SUBJ-001", "V2");
        assertNotNull(resolved);
        assertEquals("MANUAL_RESOLUTION", resolved.get("ruleCode").asText());
        int versionAtClose = resolved.get("version").asInt();

        post("/api/queries/" + conflictQueryId + "/responses",
                "{\"responseText\":\"已按复查值36裁定\",\"evidenceRef\":\"lab-memo-2026-031\"}", "dm1", "DM");
        post("/api/queries/" + conflictQueryId + "/close", "{\"resolution\":\"裁定完成\"}", "dm1", "DM");
        JsonNode closedQuery = get("/api/queries/" + conflictQueryId).get("query");
        assertEquals("CLOSED", closedQuery.get("status").asText());
        assertEquals(versionAtClose, closedQuery.get("lastKnownFactVersion").asInt());

        // 检验更正批次（rev2: 两条均更正为36）→ 质疑应重开，旧回复保留版本引用
        String labBatch2 = new String(new ClassPathResource("samples/lab-batch-2.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        post("/api/batches", labBatch2, "lab1", "LAB");

        JsonNode reopened = get("/api/queries/" + conflictQueryId).get("query");
        assertEquals("REOPENED", reopened.get("status").asText(), "源事实再更正后质疑应重新打开");
        JsonNode responses = get("/api/queries/" + conflictQueryId).get("responses");
        List<JsonNode> respList = toList(responses);
        assertTrue(respList.size() >= 3, "应含回复、关闭结论与系统重开说明");
        // 旧回复仍引用其对应版本
        JsonNode firstResp = respList.get(0);
        assertEquals(versionAtClose, firstResp.get("factVersionAtReply").asInt());
        assertNotNull(firstResp.get("sourceSigAtReply").asText());

        // 协调结果：裁定失效回到证据，双方一致 36 → CONFIRMED MULTI_AGREE
        JsonNode after = findRecon("CONFIRMED", "LAB_ALT", "SUBJ-001", "V2");
        assertNotNull(after);
        assertEquals("MULTI_AGREE", after.get("ruleCode").asText());
        assertEquals("36 U/L", after.get("confirmedValue").asText());
        assertTrue(after.get("version").asInt() > versionAtClose);

        // 旧修订保留为 SUPERSEDED
        JsonNode trace2 = get("/api/reconciled-facts/" + conflictId + "/trace");
        List<JsonNode> srcs2 = toList(trace2.get("sources"));
        assertEquals(4, srcs2.size(), "两条记录各两个修订都应可溯");
        assertEquals(2, srcs2.stream().filter(s -> s.get("role").asText().equals("SUPERSEDED")).count());
        assertEquals(2, srcs2.stream().filter(s -> s.get("role").asText().equals("CONFIRMING")).count());

        // ---------- 6. 关联裁定闭环：影像候选确认 ----------
        JsonNode cands2 = get("/api/link-candidates?status=CANDIDATE");
        JsonNode v2Cand = toList(cands2).stream()
                .filter(c -> c.get("visitCode").asText().equals("V2")).findFirst().orElseThrow();
        post("/api/link-candidates/" + v2Cand.get("id").asLong() + "/confirm", null, "cra1", "CRA");
        JsonNode tumor = findRecon("CONFIRMED", "TUMOR_LD_SUM", "SUBJ-001", "V2");
        assertNotNull(tumor, "裁定后影像值应确认到V2");
        assertEquals("82 mm", tumor.get("confirmedValue").asText());
        assertNull(findRecon("PENDING", "TUMOR_LD_SUM", "SUBJ-001", null), "裁定后待判占位应清理");
        assertEquals(0, toList(get("/api/link-candidates?status=CANDIDATE")).size(), "候选应全部裁定完毕");

        // ---------- 7. 旧快照复现 + 新快照反映新数据 ----------
        JsonNode snap1RowsAfter = get("/api/snapshots/" + snap1Id + "/rows");
        assertEquals(toList(snap1Rows).size(), toList(snap1RowsAfter).size(), "物化行不可变");
        assertTrue(toList(snap1RowsAfter).stream().noneMatch(r -> r.get("fieldKey").asText().equals("TUMOR_LD_SUM")),
                "旧快照不应出现裁定后才确认的字段");
        JsonNode verify = get("/api/snapshots/" + snap1Id + "/verify");
        assertTrue(verify.get("reproducible").asBoolean(), "旧快照应可复算复现");

        JsonNode snap2 = post("/api/snapshots", "{\"name\":\"IA-2\"}", "dm1", "DM");
        JsonNode snap2Rows = get("/api/snapshots/" + snap2.get("id").asLong() + "/rows");
        assertTrue(toList(snap2Rows).stream().anyMatch(r -> r.get("fieldKey").asText().equals("LAB_ALT")
                && r.get("value").asText().equals("36 U/L")), "新快照应含更正后一致值");
        assertTrue(toList(snap2Rows).stream().anyMatch(r -> r.get("fieldKey").asText().equals("TUMOR_LD_SUM")));

        // ---------- 8. 统计字段回溯链完整 ----------
        JsonNode anyRow = toList(snap2Rows).stream()
                .filter(r -> r.get("fieldKey").asText().equals("LAB_ALT")
                        && r.get("subjectKey").asText().equals("SUBJ-001")).findFirst().orElseThrow();
        JsonNode rowTrace = get("/api/reconciled-facts/" + anyRow.get("reconciledFactId").asLong() + "/trace");
        assertFalse(toList(rowTrace.get("history")).isEmpty(), "协调历史应可查");
        assertTrue(toList(rowTrace.get("sources")).stream()
                .allMatch(s -> s.get("payloadDigest") != null && s.get("batchReceivedAt") != null),
                "每个来源应可溯到批次摘要与接收时间");
    }

    @Test
    @Order(2)
    void batchIdempotentAndStaleRevision() throws Exception {
        // 批次幂等：重复上送同一批次 → 409
        String crf1 = new String(new ClassPathResource("samples/crf-batch-1.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(crf1))
                .andExpect(status().isConflict());

        // 旧修订重送：不覆盖现行有效修订，标记 STALE
        String stale = """
                {"sourceSystem":"LAB","centerId":"C01","batchLabel":"lab-batch-3",
                 "facts":[{"sourceRecordId":"L-2001","sourceRevision":1,"externalSubjectId":"S-002",
                   "sourceFieldCode":"ALT","valueNum":99,"unit":"U/L","eventDate":"2026-03-24",
                   "timePrecision":"DATE"}]}""";
        post("/api/batches", stale, "lab1", "LAB");
        JsonNode facts = get("/api/raw-facts?sourceSystem=LAB");
        JsonNode staleFact = toList(facts).stream()
                .filter(f -> f.get("sourceRecordId").asText().equals("L-2001")
                        && f.get("sourceRevision").asInt() == 1
                        && f.get("valueNum").asInt() == 99).findFirst().orElseThrow();
        assertFalse(staleFact.get("valid").asBoolean(), "旧修订重送应标记无效");
        assertTrue(staleFact.get("invalidReason").asText().contains("STALE"));
        // 现行值不受影响
        JsonNode alt2 = findRecon("CONFIRMED", "LAB_ALT", "SUBJ-002", "V2");
        assertNotNull(alt2);
        assertEquals("41 U/L", alt2.get("confirmedValue").asText());
    }
}
