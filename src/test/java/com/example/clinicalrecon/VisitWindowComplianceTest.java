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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 方案访视窗口符合性验收：提前采样、跨窗分次完成、迟报结果、方案修订、给药中断、
 * 无法判定(缺时间/关联歧义)、分母与适用版本核对、执行差异分析、不自动创建偏离裁决。
 * 判定基准日固定为 2026-06-01（application-test.yml）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VisitWindowComplianceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private JsonNode get(String url) throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode post(String url, String body) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private static List<JsonNode> toList(JsonNode arr) {
        var out = new ArrayList<JsonNode>();
        arr.forEach(out::add);
        return out;
    }

    private JsonNode findActivity(String subjectKey, String visitCode, String activityCode) throws Exception {
        for (JsonNode n : get("/api/compliance/activities?subjectKey=" + subjectKey + "&visitCode=" + visitCode)) {
            if (n.get("activityCode").asText().equals(activityCode)) return n;
        }
        return null;
    }

    private JsonNode findVisit(String subjectKey, String visitCode) throws Exception {
        for (JsonNode n : get("/api/compliance/visits?subjectKey=" + subjectKey)) {
            if (n.get("visitCode").asText().equals(visitCode)) return n;
        }
        return null;
    }

    @Test
    @Order(1)
    void complianceAcceptance() throws Exception {
        // ---------- 种子 ----------
        post("/api/demo/seed", null);

        // ---------- 计划目录：两版方案 + 窗口与适用人群 ----------
        JsonNode plan = get("/api/compliance/plan");
        assertEquals(2, plan.size(), "计划目录应含 v1.0 与 v2.0 两个方案版本");
        JsonNode v2 = toList(plan).stream()
                .filter(v -> v.get("versionCode").asText().equals("v2.0")).findFirst().orElseThrow();
        assertEquals("2026-04-15", v2.get("effectiveFrom").asText());
        JsonNode v2V3 = toList(v2.get("visits")).stream()
                .filter(v -> v.get("visitCode").asText().equals("V3")).findFirst().orElseThrow();
        JsonNode v2V3Ct = toList(v2V3.get("activities")).stream()
                .filter(a -> a.get("activityCode").asText().equals("CT_SCAN")).findFirst().orElseThrow();
        assertEquals(-4, v2V3Ct.get("offsetStart").asInt(), "修订案 V3 影像窗口起点 -4");
        assertEquals(5, v2V3Ct.get("offsetEnd").asInt());
        JsonNode v1 = toList(plan).stream()
                .filter(v -> v.get("versionCode").asText().equals("v1.0")).findFirst().orElseThrow();
        JsonNode v1V3 = toList(v1.get("visits")).stream()
                .filter(v -> v.get("visitCode").asText().equals("V3")).findFirst().orElseThrow();
        JsonNode v1V3Ct = toList(v1V3.get("activities")).stream()
                .filter(a -> a.get("activityCode").asText().equals("CT_SCAN")).findFirst().orElseThrow();
        assertEquals(-10, v1V3Ct.get("offsetStart").asInt(), "v1.0 V3 影像窗口起点 -10");
        JsonNode v1V1 = toList(v1.get("visits")).stream()
                .filter(v -> v.get("visitCode").asText().equals("V1")).findFirst().orElseThrow();
        JsonNode pk = toList(v1V1.get("activities")).stream()
                .filter(a -> a.get("activityCode").asText().equals("PK_SAMPLING")).findFirst().orElseThrow();
        assertEquals("COHORT_A", pk.get("appliesTo").asText(), "PK采血仅适用 COHORT_A 人群");

        // ---------- 1. 提前采样：SUBJ-004 V2 采血 2026-03-11 早于窗口起点 2026-03-14 ----------
        JsonNode early = findActivity("SUBJ-004", "V2", "LAB_SAMPLING");
        assertNotNull(early);
        assertEquals("EARLY", early.get("status").asText());
        assertEquals(3, early.get("deviationDays").asInt(), "提前 3 天");
        assertEquals("v1.0", early.get("versionCode").asText());
        assertEquals("2026-03-14", early.get("windowStart").asText());
        assertEquals("2026-03-11", early.get("occurredDate").asText());

        // ---------- 2. 跨窗分次完成：SUBJ-001 V2 分 4 次完成, 影像 2026-03-20 落入 V3 访视窗仍判 V2 延迟 ----------
        JsonNode lateCt = findActivity("SUBJ-001", "V2", "CT_SCAN");
        assertEquals("LATE", lateCt.get("status").asText(), "影像落在 V3 访视窗内也不得藏入 V3, 应判 V2 延迟");
        assertEquals(1, lateCt.get("deviationDays").asInt());
        assertEquals("2026-03-19", lateCt.get("windowEnd").asText());
        assertEquals("2026-03-20", lateCt.get("occurredDate").asText());
        assertEquals("ON_WINDOW", findActivity("SUBJ-001", "V2", "ASSESS").get("status").asText());
        assertEquals("ON_WINDOW", findActivity("SUBJ-001", "V2", "STUDY_DRUG").get("status").asText());
        assertEquals("ON_WINDOW", findActivity("SUBJ-001", "V2", "PK_SAMPLING").get("status").asText());
        JsonNode s1v2 = findVisit("SUBJ-001", "V2");
        assertEquals("DEVIATED", s1v2.get("status").asText(), "含 1 项延迟 → 访视有偏离");
        assertEquals(5, s1v2.get("denominator").asInt(), "V2 分母=5(问诊/采血/影像/给药/PK)");
        assertEquals(4, s1v2.get("onWindow").asInt());
        assertEquals(1, s1v2.get("late").asInt());
        assertEquals(1, s1v2.get("maxDeviationDays").asInt());

        // ---------- 3. 迟报结果：窗内采样窗后回报 → 合规按采样时刻, 时效另记 ----------
        JsonNode lateReport = findActivity("SUBJ-001", "V2", "LAB_SAMPLING");
        assertEquals("ON_WINDOW", lateReport.get("status").asText(), "采样在窗内, 迟报不影响合规判定");
        assertTrue(lateReport.get("lateReport").asBoolean(), "窗后回报应作数据时效标记");
        assertEquals(9, lateReport.get("reportLagDays").asInt(), "03-16 采样 03-25 回报, 滞后 9 天");

        // ---------- 4. 方案修订：同一相对日, 适用版本不同 → 判定不同; 不按当前版本重判历史 ----------
        JsonNode s3v3ct = findActivity("SUBJ-003", "V3", "CT_SCAN");
        assertEquals("v2.0", s3v3ct.get("versionCode").asText(), "SUBJ-003 V3 名义日 2026-04-29 ≥ 修订生效日");
        assertEquals("EARLY", s3v3ct.get("status").asText(), "按 v2.0 窗 [04-25,05-04] 判提前");
        assertEquals(2, s3v3ct.get("deviationDays").asInt());
        assertEquals("2026-04-25", s3v3ct.get("windowStart").asText());
        JsonNode s4v3ct = findActivity("SUBJ-004", "V3", "CT_SCAN");
        assertEquals("v1.0", s4v3ct.get("versionCode").asText(), "SUBJ-004 V3 名义日 2026-03-29 < 修订生效日, 仍按 v1.0");
        assertEquals("ON_WINDOW", s4v3ct.get("status").asText(), "历史访视不按当前版本重判: v1.0 窗内按窗");
        // 版本按访视实例选择: SUBJ-003 V1 仍 v1.0, V2 起 v2.0
        assertEquals("v1.0", findVisit("SUBJ-003", "V1").get("versionCode").asText());
        assertEquals("v2.0", findVisit("SUBJ-003", "V2").get("versionCode").asText());

        // ---------- 5. 给药中断：后续访视不适用而非漏访 ----------
        JsonNode s2v2 = findVisit("SUBJ-002", "V2");
        assertEquals("NOT_APPLICABLE", s2v2.get("status").asText(), "给药中断后访视不适用, 非漏访");
        assertEquals(0, s2v2.get("denominator").asInt(), "不适用不计入分母");
        assertEquals("NOT_APPLICABLE", findVisit("SUBJ-002", "V3").get("status").asText());
        JsonNode s2v2drug = findActivity("SUBJ-002", "V2", "STUDY_DRUG");
        assertEquals("NOT_APPLICABLE", s2v2drug.get("status").asText());
        assertTrue(s2v2drug.get("reason").asText().contains("给药中断"));
        // V1 在中断前已完成: 按窗; PK 采血对 COHORT_B 不适用 → 分母 3
        JsonNode s2v1 = findVisit("SUBJ-002", "V1");
        assertEquals("ON_WINDOW", s2v1.get("status").asText());
        assertEquals(3, s2v1.get("denominator").asInt(), "PK 采血不适用 COHORT_B, 分母=3");
        assertEquals(1, s2v1.get("notApplicable").asInt());
        assertEquals("NOT_APPLICABLE", findActivity("SUBJ-002", "V1", "PK_SAMPLING").get("status").asText());

        // ---------- 6. 无法判定：缺发生时间 / 关联歧义(等距候选保留) ----------
        JsonNode s3v2ct = findActivity("SUBJ-003", "V2", "CT_SCAN");
        assertEquals("INDETERMINATE", s3v2ct.get("status").asText(), "事件缺发生时间 → 无法判定");
        assertEquals("INDETERMINATE", findVisit("SUBJ-003", "V2").get("status").asText());
        JsonNode s4v3assess = findActivity("SUBJ-004", "V3", "ASSESS");
        assertEquals("INDETERMINATE", s4v3assess.get("status").asText(), "歧义事件在候选中 → 无法判定而非漏访");
        // 歧义事件本身: 保留双候选, 不擅自分配
        JsonNode events = get("/api/compliance/events");
        assertEquals(34, events.size(), "34 条种子事件全部保留");
        JsonNode ambiguous = toList(events).stream()
                .filter(e -> e.get("eventUid").asText().equals("EV-033")).findFirst().orElseThrow();
        assertEquals("AMBIGUOUS", ambiguous.get("linkStatus").asText());
        assertEquals(2, ambiguous.get("candidates").size(), "等距双候选保留");
        JsonNode noDate = toList(events).stream()
                .filter(e -> e.get("eventUid").asText().equals("EV-021")).findFirst().orElseThrow();
        assertEquals("LINKED", noDate.get("linkStatus").asText());
        assertEquals("V2", noDate.get("linkedVisitCode").asText(), "显式访视编码定位 V2");
        JsonNode marker = toList(events).stream()
                .filter(e -> e.get("eventUid").asText().equals("EV-013")).findFirst().orElseThrow();
        assertEquals("MARKER", marker.get("linkStatus").asText(), "给药中断为标记事件, 不作活动证据");

        // ---------- 7. 未完成与逾期天数（基准日 2026-06-01） ----------
        JsonNode s1v3lab = findActivity("SUBJ-001", "V3", "LAB_SAMPLING");
        assertEquals("NOT_DONE", s1v3lab.get("status").asText());
        assertEquals(61, s1v3lab.get("overdueDays").asInt(), "窗口 2026-04-01 截止, 逾期 61 天");
        JsonNode s1v3 = findVisit("SUBJ-001", "V3");
        assertEquals("INCOMPLETE", s1v3.get("status").asText());
        assertEquals(4, s1v3.get("denominator").asInt());
        assertEquals(4, s1v3.get("notDone").asInt());

        // ---------- 8. 执行差异分析：按中心/访视/检查类型 ----------
        JsonNode byCenter = get("/api/compliance/analysis?groupBy=centerId");
        JsonNode c01 = toList(byCenter).stream()
                .filter(r -> r.get("centerId").asText().equals("C01")).findFirst().orElseThrow();
        assertEquals(16, c01.get("denominator").asInt());
        assertEquals(11, c01.get("onWindow").asInt());
        assertEquals(1, c01.get("late").asInt());
        assertEquals(4, c01.get("notDone").asInt());
        assertEquals(10, c01.get("notApplicable").asInt(), "SUBJ-002 中断+人群不适用计 10 项");
        assertEquals(1, c01.get("lateReports").asInt());
        JsonNode c02 = toList(byCenter).stream()
                .filter(r -> r.get("centerId").asText().equals("C02")).findFirst().orElseThrow();
        assertEquals(26, c02.get("denominator").asInt());
        assertEquals(17, c02.get("onWindow").asInt());
        assertEquals(2, c02.get("early").asInt());
        assertEquals(2, c02.get("indeterminate").asInt());
        assertEquals(5, c02.get("notDone").asInt());
        assertEquals(3, c02.get("maxDeviationDays").asInt());

        JsonNode byVisit = get("/api/compliance/analysis?groupBy=visitCode");
        JsonNode v1Row = toList(byVisit).stream()
                .filter(r -> r.get("visitCode").asText().equals("V1")).findFirst().orElseThrow();
        assertEquals(15, v1Row.get("denominator").asInt());
        assertEquals(15, v1Row.get("onWindow").asInt(), "V1 全部按窗");
        JsonNode v3Row = toList(byVisit).stream()
                .filter(r -> r.get("visitCode").asText().equals("V3")).findFirst().orElseThrow();
        assertEquals(12, v3Row.get("denominator").asInt());
        assertEquals(9, v3Row.get("notDone").asInt());

        JsonNode byType = get("/api/compliance/analysis?groupBy=activityType");
        JsonNode imaging = toList(byType).stream()
                .filter(r -> r.get("activityType").asText().equals("IMAGING")).findFirst().orElseThrow();
        assertEquals(6, imaging.get("denominator").asInt());
        assertEquals(2, imaging.get("onWindow").asInt());
        assertEquals(1, imaging.get("early").asInt());
        assertEquals(1, imaging.get("late").asInt());
        assertEquals(1, imaging.get("indeterminate").asInt());
        assertEquals(2, imaging.get("maxDeviationDays").asInt());

        // ---------- 9. 不自动创建偏离裁决：重算前后质疑总数不变 ----------
        int queriesBefore = toList(get("/api/queries")).size();
        post("/api/compliance/run", null);
        int queriesAfter = toList(get("/api/queries")).size();
        assertEquals(queriesBefore, queriesAfter, "窗口判定只发布状态, 不自动创建偏离裁决/质疑");
        // 重算幂等: 判定结果不变
        assertEquals("EARLY", findActivity("SUBJ-004", "V2", "LAB_SAMPLING").get("status").asText());
    }

    @Test
    @Order(2)
    void eventIngestIdempotentAndRecalc() throws Exception {
        // 重复 eventUid 幂等跳过; 新事件触发重算, 原未完成活动转为按窗
        String body = """
                {"events":[
                  {"eventUid":"EV-001","centerId":"C01","externalSubjectId":"S-001",
                   "activityType":"ASSESSMENT","activityCode":"ASSESS","occurredDate":"2026-03-01"},
                  {"eventUid":"EV-900","centerId":"C01","externalSubjectId":"S-001",
                   "activityType":"SAMPLING","activityCode":"LAB_SAMPLING","occurredDate":"2026-03-25"}
                ]}""";
        JsonNode out = post("/api/compliance/events", body);
        assertEquals(1, out.get("inserted").asInt());
        assertEquals(1, out.get("skipped").asInt(), "重复 eventUid 幂等跳过");
        JsonNode s1v3lab = findActivity("SUBJ-001", "V3", "LAB_SAMPLING");
        assertEquals("ON_WINDOW", s1v3lab.get("status").asText(), "新事件到达后重算: V3 采血按窗");
        assertEquals("v1.0", s1v3lab.get("versionCode").asText());
        // 再次全量重送仍幂等
        JsonNode out2 = post("/api/compliance/events", body);
        assertEquals(0, out2.get("inserted").asInt());
        assertEquals(2, out2.get("skipped").asInt());
    }
}
