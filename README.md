# 临床试验多源事实对账与质疑数据服务

面向肿瘤药物多中心试验：eCRF、中心实验室、影像评估、监查四类来源各自上送事实，
本服务完成 **受理仓 → 受试者/访视关联 → 事实协调 → 质疑闭环 → 分析快照** 的全链路对账，
并新增 **方案访视窗口符合性数据集**（计划目录 → 事件整理 → 窗口判定 → 访视汇总 → 执行差异分析），
产出可复算、可回溯的数据集，避免未经核对的字段进入中期分析、避免正常迟报被误判为方案偏离。

技术栈：Java 25 · Spring Boot 3.5 · PostgreSQL 18 · Flyway · springdoc-openapi（Swagger UI）。

## 一、对账链路与核心语义

```
来源批次(CRF/LAB/IMAGING/MONITORING)
  └─ 受理仓 source_batch / raw_fact      载荷摘要sha256、事件时间、接收时间、来源修订；批次幂等
       └─ 关联器 link_candidate/fact_link 中心映射+方案窗口 → 候选+置信理由；多候选不擅自分配
            └─ 协调器 reconciled_fact(+history) 统一字段四态：CONFIRMED/CONFLICT/MISSING/PENDING
                 ├─ 质疑 query_item/query_response 责任角色、回复证据、按事实版本关闭、源再更正自动重开
                 └─ 快照 analysis_snapshot(+rows)  只消费过质量门槛的协调结果；物化可复现
```

核心业务规则（与验收一一对应）：

| 规则 | 实现 |
| --- | --- |
| 互斥检验结果不按接收先后覆盖 | 同一(受试者,访视,字段)存在多个有效互斥值 → `CONFLICT`，双方修订关系保留（`reconciled_fact_source` 角色 CONFLICTING），确认值为空，阻止进入分析 |
| 仅日期精度且窗口跨边界 | 关联器为每个命中窗口生成候选（MEDIUM 置信+理由），状态 `PENDING_DECISION`，**不擅自分配**，待人工 `/api/link-candidates/{id}/confirm` 裁定 |
| 质疑回复后源事实再更正 | 新修订取代旧修订（保留 SUPERSEDED 关系）→ 协调版本跃迁 → 已关闭/已回复质疑自动 `REOPENED`；旧回复仍引用其 `factVersionAtReply` / `sourceSigAtReply` |
| 未映射事实保持待判 | 字段目录外的来源字段不丢弃不猜测：`UNMAPPED` → 协调数据集发布 `PENDING`（`UNMAPPED::来源::字段`），并自动开质疑 |
| 人工裁定的失效 | `MANUAL_RESOLUTION` 裁定值只在来源修订签名不变时有效；源再更正后自动失效，回到证据重算 |
| 分析门槛 | 快照按 `allowedStatuses`（默认仅 CONFIRMED）物化行；CONFLICT/MISSING/PENDING 计入排除统计 |
| 旧快照复现 | 协调历史追加式存储并带全局协调序号；`/api/snapshots/{id}/verify` 以快照序号为界重放同一门槛与物化行比对 |
| 字段级回溯 | `/api/reconciled-facts/{id}/trace` 给出：协调规则码/版本、全部历史版本、来源批次（载荷摘要、接收时间、修订、SUPERSEDED 链）、关联质疑 |

## 二、建模策略取舍（按来源种类与变更频率选择）

本题两种路线都能产出有来源依据的对账数据：

- **专用适配器 → 共同事件模型（本服务采用）**：每类来源按自己的字段编码上送
  （`sourceFieldCode` 保留来源语义），受理时经**字段目录**映射到统一字段键。
  优点：保留来源语义、协调/快照查询直接、运行快；代价：每类来源一个适配契约，
  来源改版时需逐个升级（本服务中体现为更新 `field_catalog.source_field_map` 映射，
  目录外字段自动落入待判，不会静默错配）。
- **宽泛统一事件表 + 查询时按字段目录解释**：新增来源容易，但中心差异（编码、精度、
  窗口）被推到每次判读时处理，歧义与误判风险高。

当前来源仅 4 类且字段稳定、变更频率低，专用适配器的维护成本可控，换来协调规则的
确定性与可审计性；若未来接入高频改版的中心自有系统，可对该来源单独采用宽表落地、
再由目录解释接入同一协调器。**无论哪条路线，未能映射的事实必须保持待判**——这一点
由协调器统一保证，与适配策略无关。

## 三、方案访视窗口符合性数据集

受试者常分次完成问诊、采血、影像与给药，结果回报又晚于实际采样：按数据入库日判断会把
正常迟报算成方案偏离，也可能把窗外采样藏进一次"访视已完成"。本数据集把**计划目录 →
事件整理 → 窗口判定 → 访视汇总**固化为可复算流水线：

```
计划目录 protocol_version / plan_visit / plan_activity
         方案版本、名义访视日、各项检查允许窗口、适用人群(队列)
  └─ 事件整理器 activity_event / event_link_candidate
         问诊/采样/影像采集/给药的实际发生时间 → 受试者+访视实例；歧义保留候选不擅自分配
       └─ 窗口计算器 activity_judgment / visit_summary
              逐项判定 + 访视级汇总（派生数据，recalculate 全量重建，可复算）
            └─ 执行差异分析 /api/compliance/analysis
              按中心/访视/检查类型分组统计；只发布状态与偏离天数，不自动创建偏离裁决
```

### 建模取舍（锚点优先 vs 活动优先）

两种路线都逐项判定同一组必要活动并产出相同的访视汇总，应按锚点可用性与分次完成频率选择：

- **活动优先匹配**：每项活动独立按其计划日、活动窗口与候选访视匹配，再把已判定活动组合
  为访视汇总。锚点缺失或分次完成时活动事实保留完整，但跨窗候选较多时需处理关联歧义。
- **锚点优先（本服务采用）**：先由方案锚点（入组日 + 名义访视日）定位访视实例，再在该实例
  内依每项活动自己的偏移与窗口逐项判定。关联关系稳定、便于按中心/访视/检查类型批量查询。

选择依据：本数据集锚点（入组日）始终可用，锚点优先换来关联稳定性；分次完成由"每项活动
独立判定"完整保留（同一访视的问诊/采血/影像/给药各自判定、互不影响，影像延迟不会被
"问诊已完成"掩盖）。其固有风险按题意兜底：**锚点缺失/等距歧义时保留候选或置无法判定，
绝不把其余活动直接算完成**（见下表）。

### 判定规则（与验收一一对应）

| 规则 | 实现 |
| --- | --- |
| 逐项判定五态+不适用 | 每项必要活动：`EARLY`提前 / `ON_WINDOW`按窗 / `LATE`延迟 / `NOT_DONE`未完成 / `INDETERMINATE`无法判定；`NOT_APPLICABLE`不适用不计分母 |
| 窗口与偏离天数 | 窗口 = [名义访视日+offsetStart, 名义访视日+offsetEnd]（相对入组日）；偏离天数 = 实际日超出窗口的天数 |
| 事件定位（锚点优先） | 显式访视编码优先；否则按研究日锚定**活动名义日最近**的访视实例；等距多候选 → `AMBIGUOUS` 保留候选不擅自分配；距任何活动名义日 >45 天 → 不关联（事实保留） |
| 分次完成 | 同一访视每项活动独立判定；同一活动多次完成以首次判定并注明重复次数 |
| 迟报结果 | 结果回报晚于窗口终点 → `lateReport=true` + 滞后天数；**合规按采样/发生时刻**，时效单独标记 |
| 方案修订 | 版本按访视实例名义日选择：`effective_from ≤ 名义访视日` 的最新适用人群版本；**不按当前版本重判历史访视** |
| 给药中断 | 中断日之后的访视实例整体 `NOT_APPLICABLE`（非漏访）；中断事件为 MARKER 不作活动证据 |
| 无法判定 | 事件缺发生时间 / 关联歧义未裁定 / 无适用方案版本 → `INDETERMINATE`，候选与原因保留 |
| 分母 | 访视汇总 `denominator` = 适用的必要活动数（不含人群不适用与中断不适用） |
| 不自动裁决 | 只发布状态与偏离天数；不创建质疑/偏离裁决，研究团队经分析端点解读 |

访视级汇总状态：`ON_WINDOW`（全部按窗）→ `DEVIATED`（含提前/延迟）→ `INCOMPLETE`（含未完成）
→ `INDETERMINATE`（含无法判定）→ `NOT_APPLICABLE`（不适用），并给出各状态计数与最大偏离天数。

### 演示场景（34 条种子事件，判定基准日可用 `COMPLIANCE_AS_OF` 固定）

| 场景 | 数据与期望 |
| --- | --- |
| 提前采样 | SUBJ-004 V2 采血 2026-03-11（窗口起点 03-14）→ `EARLY` 3 天，v1.0 |
| 跨窗分次完成 | SUBJ-001 V2 分 4 次完成；影像 2026-03-20 落在 V3 访视窗内，仍锚定 V2 判 `LATE` 1 天；汇总 `DEVIATED` 分母 5 |
| 迟报结果 | SUBJ-001 V2 采血 03-16（窗内）、03-25 回报 → `ON_WINDOW` + `lateReport`（滞后 9 天） |
| 方案修订 | v2.0（2026-04-15 生效）将 V3 影像窗 [-10,+3] 收窄为 [-4,+5]：SUBJ-003 V3 影像 04-23 → v2.0 `EARLY` 2 天；SUBJ-004 同一相对日 → v1.0 `ON_WINDOW` |
| 给药中断 | SUBJ-002 2026-03-20 中断 → V2/V3 `NOT_APPLICABLE`（非漏访）；V1 已完成按窗 |
| 无法判定 | SUBJ-003 V2 影像缺发生时间 → `INDETERMINATE`；SUBJ-004 问诊事件等距 V2/V3 → 双候选保留 |
| 适用人群/分母 | PK 采血仅 COHORT_A；SUBJ-002(COHORT_B) V1 分母 = 3 |

## 四、一键启动（Docker Compose）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html （OpenAPI 3.x：/v3/api-docs）
- 数据库：PostgreSQL 18（compose 内 `db` 服务，库/账号/密码均为 `recon`），Flyway 自动建表

装载演示数据：

```bash
curl -X POST http://localhost:8080/api/demo/seed
```

## 五、本地开发

预装运行时：JDK 25（`/opt/jdk-25`）、Maven 3.9（`/opt/maven`）、PostgreSQL 18（`127.0.0.1:5432`，库/用户/密码 `ccdev`）。

```bash
# 建库（首次）
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c "create database recon_dev;"

# 启动（Flyway 自动建表）
DB_URL=jdbc:postgresql://127.0.0.1:5432/recon_dev DB_USER=ccdev DB_PASSWORD=ccdev \
  /opt/maven/bin/mvn spring-boot:run

# 装载演示种子（幂等，可重复）
curl -X POST http://localhost:8080/api/demo/seed
```

可选配置：`COMPLIANCE_AS_OF=2026-06-01` 固定窗口判定基准日（默认当天；演示数据窗口均在
2026-03~05，固定基准日可让"未完成逾期天数"等输出确定）。

## 六、测试账号（演示身份头，非安全机制）

请求头 `X-User` / `X-Role` 标识操作人与责任角色，写入审计字段（裁定人、回复人、关闭人）：

| 账号 | 角色 | 职责 |
| --- | --- | --- |
| `dm1` | DM | 数据管理：冲突裁定、未映射字段处理、快照制作 |
| `cra1` | CRA | 临床监查：访视关联裁定、监查发现与缺失跟进 |
| `lab1` | LAB | 中心实验室：检验结果与更正批次上送 |
| `pi1` | PI | 主要研究者：质疑复核 |
| `admin` | ADMIN | 默认/管理 |

质疑责任角色自动分派：冲突/未映射 → DM；缺失/关联歧义/监查发现 → CRA。
窗口符合性数据集为只读分析性质，不消费身份头也不自动开质疑。

## 七、验证方式（以下均已实际执行通过）

```bash
./verify.sh
```

脚本依次执行并断言（全部 PASS）：

1. `mvn test` —— 两个验收集成测试类（真实 PostgreSQL `recon_test` 库）：
   - `AcceptanceFlowTest`（2 用例）：事实匹配（显式访视/单窗口自动关联；跨边界影像 V2/V3 双候选不擅自分配）；
     冲突保留（35 与 52 互斥 ALT 双方保留、CONFLICT、自动开质疑）；质疑闭环（回复→关闭→更正批次→自动 REOPENED）；
     人工裁定与失效（36=36 MULTI_AGREE）；分析门槛（快照仅 CONFIRMED）；旧快照复现；字段级回溯；
     批次幂等（重复上送 409）与旧修订重送（STALE 不覆盖）。
   - `VisitWindowComplianceTest`（2 用例）：提前采样（EARLY 3 天）；跨窗分次完成（窗外影像判 V2
     LATE 不藏入 V3，汇总 DEVIATED 分母 5）；迟报（窗内采样 ON_WINDOW + lateReport 滞后 9 天）；
     方案修订（SUBJ-003 按 v2.0 EARLY 2 天 vs SUBJ-004 同相对日按 v1.0 ON_WINDOW，版本按访视实例选择）；
     给药中断（V2/V3 NOT_APPLICABLE 分母 0，V1 分母 3 含人群不适用）；无法判定（缺时间/等距歧义双候选）；
     未完成逾期天数（基准日 2026-06-01）；按中心/访视/检查类型分析计数；重算前后质疑总数不变
     （不自动创建偏离裁决）；事件幂等（重复 eventUid 跳过）与新事件触发重算。
2. 打包并真实启动应用（`recon_dev` 库，`COMPLIANCE_AS_OF=2026-06-01`），HTTP 冒烟 15 项断言：
   对账 7 项（冲突保留/跨边界候选/未映射待判/分析门槛/质疑重开/旧快照复现/字段级回溯）
   + 符合性 8 项（提前采样/跨窗分次/迟报/方案修订/给药中断/无法判定/差异分析）。

> 说明：Docker 镜像构建未在本开发容器内实测（容器无 docker 守护进程）；
> Dockerfile 使用与本地完全相同的 Maven 3.9 + JDK 25 构建链与启动命令，本地启动与全部验证均按上文实际执行通过。

## 八、演示数据与手工验收流程

样例批次（`src/main/resources/samples/`，可直接 POST 到 `/api/batches`）：

| 文件 | 来源 | 场景 |
| --- | --- | --- |
| crf-batch-1.json | CRF | S-001/V2 收缩压120；S-002/V1 血压两项（显式访视，自动关联） |
| lab-batch-1.json | 检验 | S-001 两条互斥 ALT：35（仅日期，单窗口自动关联）与 52（显式 V2）→ 冲突；S-002 ALT 41 |
| imaging-batch-1.json | 影像 | 检查日期 2026-03-19 仅日期精度，落在 V2[13,18] 与 V3[18,31] 边界 → 双候选待裁定 |
| monitoring-batch-1.json | 监查 | SDV 不一致发现 → 自动开质疑（CRA） |
| crf-batch-2-unmapped.json | CRF | 目录外字段 XXMOOD → 未映射待判 |
| lab-batch-2.json | 检验 | 两条 ALT 均更正为 36（rev2）→ 演示修订取代与质疑重开 |

主数据：试验 ONCO-2026-01；受试者 SUBJ-001/002（C01）、SUBJ-003/004（C02），队列
COHORT_A（001/003/004）与 COHORT_B（002）；访视窗口 V1[-2,2]、V2[13,18]、V3[18,31]；
字段目录 6 项（4 项分析必需）；符合性计划目录 v1.0/v2.0 两版（修订改 V3 影像窗），
必要活动：问诊/采血/影像/给药 + PK 采血（仅 COHORT_A）。

手工验收（应用运行中）：

```bash
curl -X POST http://localhost:8080/api/demo/seed          # 重置+装载
# —— 对账链路 ——
curl 'http://localhost:8080/api/reconciled-facts?status=CONFLICT'      # 冲突保留
curl 'http://localhost:8080/api/link-candidates?status=CANDIDATE'      # 跨边界双候选
curl 'http://localhost:8080/api/reconciled-facts?status=PENDING'       # 待判（未映射+未裁定）
curl 'http://localhost:8080/api/reconciled-facts?status=MISSING'       # 缺失
curl -X POST http://localhost:8080/api/snapshots -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"name":"IA-1"}'                # 质量门槛快照
curl http://localhost:8080/api/snapshots/1/rows                        # 快照行（仅CONFIRMED）
# 质疑闭环：回复→关闭→上送更正批次→观察 REOPENED
QID=$(curl -s 'http://localhost:8080/api/queries?status=OPEN' | python3 -c "import sys,json;print([q['id'] for q in json.load(sys.stdin) if q['issueType']=='CONFLICT'][0])")
curl -X POST http://localhost:8080/api/queries/$QID/responses -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"responseText":"以复查值为准","evidenceRef":"memo-1"}'
curl -X POST http://localhost:8080/api/queries/$QID/close -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"resolution":"完成"}'
curl -X POST http://localhost:8080/api/batches -H 'Content-Type: application/json' \
  -H 'X-User: lab1' -H 'X-Role: LAB' --data-binary @src/main/resources/samples/lab-batch-2.json
curl http://localhost:8080/api/queries/$QID                            # status=REOPENED
curl http://localhost:8080/api/snapshots/1/verify                      # reproducible=true
curl http://localhost:8080/api/reconciled-facts/1/trace                # 字段级回溯
# —— 访视窗口符合性 ——
curl http://localhost:8080/api/compliance/plan                         # 计划目录(v1.0/v2.0)
curl 'http://localhost:8080/api/compliance/activities?subjectKey=SUBJ-004&visitCode=V2'   # 提前采样 EARLY
curl 'http://localhost:8080/api/compliance/activities?subjectKey=SUBJ-001&visitCode=V2'   # 跨窗分次+迟报
curl 'http://localhost:8080/api/compliance/activities?subjectKey=SUBJ-003&visitCode=V3'   # 方案修订 v2.0
curl 'http://localhost:8080/api/compliance/visits?subjectKey=SUBJ-002'                    # 给药中断不适用
curl 'http://localhost:8080/api/compliance/visits?status=INDETERMINATE'                   # 无法判定汇总
curl http://localhost:8080/api/compliance/events                       # 事件与歧义候选
curl 'http://localhost:8080/api/compliance/analysis?groupBy=centerId'                    # 按中心
curl 'http://localhost:8080/api/compliance/analysis?groupBy=visitCode'                   # 按访视
curl 'http://localhost:8080/api/compliance/analysis?groupBy=activityType'                # 按检查类型
curl -X POST http://localhost:8080/api/compliance/run                  # 全量重算(幂等)
```

## 九、API 一览

| 域 | 端点 |
| --- | --- |
| 受理仓 | `POST /api/batches`、`GET /api/batches`、`GET /api/raw-facts` |
| 关联器 | `POST /api/linking/run`、`GET /api/link-candidates`、`POST /api/link-candidates/{id}/confirm|reject`、`GET /api/links` |
| 协调器 | `POST /api/reconcile/run`、`GET /api/reconciled-facts`、`GET /api/reconciled-facts/{id}/trace`、`POST /api/reconciled-facts/{id}/resolve`、`GET /api/reconcile/status` |
| 质疑 | `GET /api/queries`、`GET /api/queries/{id}`、`POST /api/queries/{id}/responses`、`POST /api/queries/{id}/close` |
| 快照 | `POST /api/snapshots`、`GET /api/snapshots`、`GET /api/snapshots/{id}/rows`、`GET /api/snapshots/{id}/verify` |
| 符合性 | `GET /api/compliance/plan`、`POST /api/compliance/events`、`GET /api/compliance/events`、`POST /api/compliance/run`、`GET /api/compliance/activities`、`GET /api/compliance/visits`、`GET /api/compliance/analysis` |
| 主数据 | `GET /api/subjects`、`GET /api/center-map`、`GET /api/visit-schedule`、`GET /api/field-catalog` |
| 演示 | `POST /api/demo/seed`、`POST /api/demo/reset` |

## 十、工程结构

```
├── Dockerfile / docker-compose.yml / docker/maven-settings.xml
├── verify.sh                         # 一键验证（测试+真实启动+HTTP断言）
├── src/main/resources/db/migration/
│   ├── V1__init.sql                  # 对账链路表结构（Flyway）
│   └── V2__visit_window_compliance.sql  # 符合性：计划目录/事件/判定/汇总
├── src/main/resources/samples/*.json              # 四类来源样例批次
├── src/main/java/com/example/clinicalrecon/
│   ├── api/        # 8 个 REST 控制器（含 ComplianceController）+ camelCase 输出统一
│   ├── service/    # Intake / Linking / Reconcile / Query / Snapshot / Compliance / DemoSeed
│   ├── repo/       # JdbcTemplate 数据访问（7 个聚合，含 ComplianceRepo）
│   ├── domain/     # 枚举常量与 DTO
│   └── config/     # OpenAPI、身份头过滤器、异常映射
└── src/test/java/.../AcceptanceFlowTest.java        # 对账验收集成测试
    src/test/java/.../VisitWindowComplianceTest.java # 窗口符合性验收集成测试
```
