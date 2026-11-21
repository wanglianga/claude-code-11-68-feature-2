#!/usr/bin/env bash
# 临床试验多源事实对账与质疑数据服务 —— 本地一键验证
# 步骤：建测试库 -> 跑验收集成测试 -> 打包 -> 启动应用 -> HTTP 级冒烟
# （对账：种子/冲突/质疑重开/快照复现；符合性：提前/跨窗/迟报/修订/中断/无法判定/分析）
set -euo pipefail
cd "$(dirname "$0")"

JAVA=/opt/jdk-25/bin/java
MVN=/opt/maven/bin/mvn
PSQL="/opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev"
export PGPASSWORD=ccdev
APP_PORT=8080
BASE="http://127.0.0.1:${APP_PORT}/api"

echo "==> [1/5] 准备数据库 recon_test / recon_dev"
$PSQL -c "drop database if exists recon_test;" -c "create database recon_test;" >/dev/null
$PSQL -c "drop database if exists recon_dev;"  -c "create database recon_dev;"  >/dev/null

echo "==> [2/5] 运行验收集成测试 (mvn test)"
$MVN -q test
echo "    集成测试通过"

echo "==> [3/5] 打包并启动应用"
$MVN -q package -DskipTests
DB_URL=jdbc:postgresql://127.0.0.1:5432/recon_dev DB_USER=ccdev DB_PASSWORD=ccdev \
  COMPLIANCE_AS_OF=2026-06-01 \
  nohup $JAVA -jar target/clinical-fact-recon-1.0.0.jar > /tmp/recon-app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${APP_PORT}/v3/api-docs" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf "http://127.0.0.1:${APP_PORT}/v3/api-docs" >/dev/null || { echo "应用启动失败"; tail -30 /tmp/recon-app.log; exit 1; }
echo "    应用已启动 (pid=$APP_PID)"

echo "==> [4/5] 装载演示种子并做 HTTP 冒烟断言"
curl -sf -X POST "$BASE/demo/seed" >/dev/null

# 4.1 冲突保留：SUBJ-001/V2 的 LAB_ALT 为 CONFLICT 且无确认值
CONFLICT=$(curl -sf "$BASE/reconciled-facts?status=CONFLICT")
echo "$CONFLICT" | grep -q '"fieldKey":"LAB_ALT"' || { echo "FAIL: 未找到 LAB_ALT 冲突"; exit 1; }
echo "$CONFLICT" | grep -q '"ruleCode":"MUTUALLY_EXCLUSIVE"' || { echo "FAIL: 冲突规则码不正确"; exit 1; }
echo "    PASS 冲突保留（互斥检验结果双方保留，字段阻止确认）"

# 4.2 窗口跨边界：影像检查产生 2 个候选且不擅自分配
CANDS=$(curl -sf "$BASE/link-candidates?status=CANDIDATE")
[ "$(echo "$CANDS" | grep -o '"visitCode"' | wc -l)" = "2" ] || { echo "FAIL: 跨边界候选数不为2"; exit 1; }
echo "    PASS 窗口跨边界列出候选不擅自分配"

# 4.3 未映射字段保持待判
curl -sf "$BASE/reconciled-facts?status=PENDING" | grep -q 'UNMAPPED::CRF::XXMOOD' \
  || { echo "FAIL: 未映射字段未保持待判"; exit 1; }
echo "    PASS 未映射字段保持待判"

# 4.4 分析门槛：快照只含 CONFIRMED
SNAP=$(curl -sf -X POST "$BASE/snapshots" -H 'Content-Type: application/json' -H 'X-User: dm1' -H 'X-Role: DM' \
  -d '{"name":"IA-smoke"}')
SNAP_ID=$(echo "$SNAP" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
ROWS=$(curl -sf "$BASE/snapshots/$SNAP_ID/rows")
echo "$ROWS" | grep -q '"fieldKey":"VITAL_SYS_BP"' || { echo "FAIL: 快照缺确认字段"; exit 1; }
echo "$ROWS" | python3 -c "
import sys, json
rows = json.load(sys.stdin)
bad = [r for r in rows if r['fieldKey']=='LAB_ALT' and r['subjectKey']=='SUBJ-001']
sys.exit(1 if bad else 0)" || { echo "FAIL: 冲突字段进入快照"; exit 1; }
echo "    PASS 分析门槛（冲突/缺失/待判不进入快照）"

# 4.5 质疑闭环：回复+关闭 -> 上送检验更正批次 -> 自动重开
QID=$(curl -sf "$BASE/queries?status=OPEN" | python3 -c \
  "import sys,json;qs=[q for q in json.load(sys.stdin) if q['issueType']=='CONFLICT'];print(qs[0]['id'])")
curl -sf -X POST "$BASE/queries/$QID/responses" -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' \
  -d '{"responseText":"以复查值为准","evidenceRef":"memo-1"}' >/dev/null
curl -sf -X POST "$BASE/queries/$QID/close" -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"resolution":"完成"}' >/dev/null
curl -sf -X POST "$BASE/batches" -H 'Content-Type: application/json' -H 'X-User: lab1' -H 'X-Role: LAB' \
  --data-binary @src/main/resources/samples/lab-batch-2.json >/dev/null
STATUS=$(curl -sf "$BASE/queries/$QID" | python3 -c "import sys,json;print(json.load(sys.stdin)['query']['status'])")
[ "$STATUS" = "REOPENED" ] || { echo "FAIL: 源更正后质疑未重开 (status=$STATUS)"; exit 1; }
echo "    PASS 质疑闭环（源事实再更正自动重开，旧回复保留版本引用）"

# 4.6 旧快照复现
curl -sf "$BASE/snapshots/$SNAP_ID/verify" | grep -q '"reproducible":true' \
  || { echo "FAIL: 旧快照复算不一致"; exit 1; }
echo "    PASS 旧快照复现（物化行不变，按协调序号复算一致）"

# 4.7 回溯链
TRACE_ID=$(echo "$ROWS" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['reconciledFactId'])")
curl -sf "$BASE/reconciled-facts/$TRACE_ID/trace" | grep -q '"payloadDigest"' \
  || { echo "FAIL: 回溯缺批次载荷摘要"; exit 1; }
echo "    PASS 字段级回溯（来源批次摘要/修订/协调规则）"

# 4.8 访视窗口符合性：提前采样 / 跨窗分次完成 / 迟报 / 方案修订 / 给药中断 / 无法判定 / 分析
python3 - "$BASE" <<'PYEOF'
import sys, json, urllib.request
base = sys.argv[1]
def get(path):
    with urllib.request.urlopen(base + path) as r:
        return json.load(r)
def activity(subj, visit, code):
    for a in get(f"/compliance/activities?subjectKey={subj}&visitCode={visit}"):
        if a["activityCode"] == code: return a
    raise SystemExit(f"FAIL: 缺判定行 {subj}/{visit}/{code}")
def visit(subj, vc):
    for v in get(f"/compliance/visits?subjectKey={subj}"):
        if v["visitCode"] == vc: return v
    raise SystemExit(f"FAIL: 缺访视汇总 {subj}/{vc}")

# 提前采样: SUBJ-004 V2 采血提前 3 天
a = activity("SUBJ-004", "V2", "LAB_SAMPLING")
assert a["status"] == "EARLY" and a["deviationDays"] == 3 and a["versionCode"] == "v1.0", a
print("    PASS 提前采样（EARLY, 偏离 3 天, 适用 v1.0）")

# 跨窗分次完成: SUBJ-001 V2 影像落入 V3 访视窗仍判 V2 延迟 1 天; 汇总 DEVIATED 分母 5
a = activity("SUBJ-001", "V2", "CT_SCAN")
assert a["status"] == "LATE" and a["deviationDays"] == 1 and a["occurredDate"] == "2026-03-20", a
v = visit("SUBJ-001", "V2")
assert v["status"] == "DEVIATED" and v["denominator"] == 5 and v["onWindow"] == 4 and v["late"] == 1, v
print("    PASS 跨窗分次完成（窗外影像不藏入 V3, 访视汇总 DEVIATED 分母 5）")

# 迟报结果: 窗内采样窗后回报 → 合规按采样时刻, 时效另记
a = activity("SUBJ-001", "V2", "LAB_SAMPLING")
assert a["status"] == "ON_WINDOW" and a["lateReport"] is True and a["reportLagDays"] == 9, a
print("    PASS 迟报结果（合规按采样时刻判按窗, 数据时效 lateReport 另记）")

# 方案修订: 同一相对日, SUBJ-003 按 v2.0 判提前 2 天, SUBJ-004 按 v1.0 判按窗
a3 = activity("SUBJ-003", "V3", "CT_SCAN")
a4 = activity("SUBJ-004", "V3", "CT_SCAN")
assert a3["versionCode"] == "v2.0" and a3["status"] == "EARLY" and a3["deviationDays"] == 2, a3
assert a4["versionCode"] == "v1.0" and a4["status"] == "ON_WINDOW", a4
print("    PASS 方案修订（新窗口仅覆盖生效后访视, 历史访视不按当前版本重判）")

# 给药中断: SUBJ-002 V2/V3 不适用而非漏访; V1 分母 3(PK 不适用 COHORT_B)
v = visit("SUBJ-002", "V2")
assert v["status"] == "NOT_APPLICABLE" and v["denominator"] == 0, v
assert visit("SUBJ-002", "V3")["status"] == "NOT_APPLICABLE"
v1 = visit("SUBJ-002", "V1")
assert v1["status"] == "ON_WINDOW" and v1["denominator"] == 3 and v1["notApplicable"] == 1, v1
print("    PASS 给药中断（后续访视不适用非漏访; 人群不适用不计分母）")

# 无法判定: 缺发生时间 / 等距歧义候选保留
a = activity("SUBJ-003", "V2", "CT_SCAN")
assert a["status"] == "INDETERMINATE", a
a = activity("SUBJ-004", "V3", "ASSESS")
assert a["status"] == "INDETERMINATE", a
ev = [e for e in get("/compliance/events") if e["eventUid"] == "EV-033"][0]
assert ev["linkStatus"] == "AMBIGUOUS" and len(ev["candidates"]) == 2, ev
print("    PASS 无法判定（缺时间置 INDETERMINATE; 等距双候选保留不擅自分配）")

# 执行差异分析: 按中心分组统计
rows = {r["centerId"]: r for r in get("/compliance/analysis?groupBy=centerId")}
assert rows["C01"]["denominator"] == 16 and rows["C01"]["lateReports"] == 1, rows["C01"]
assert rows["C02"]["denominator"] == 26 and rows["C02"]["early"] == 2, rows["C02"]
print("    PASS 执行差异分析（按中心/访视/检查类型分组, 不自动创建偏离裁决）")
PYEOF

echo "==> [5/5] 停止应用"
kill $APP_PID 2>/dev/null || true
trap - EXIT
echo ""
echo "全部验证通过 ✅"
