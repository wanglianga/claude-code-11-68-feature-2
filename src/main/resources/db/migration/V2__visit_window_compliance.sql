-- V2: 方案访视窗口符合性数据集
-- 分层：计划目录(方案版本/名义访视日/各项检查允许窗口/适用人群)
--   -> 事件整理器(问诊/采样/影像/给药实际发生时间 -> 受试者+访视实例, 歧义保留候选)
--   -> 窗口计算器(逐项判定 提前/按窗/延迟/未完成/无法判定/不适用 + 访视级汇总)
-- 判定结果为派生数据：recalculate 全量重建，可复算；不自动创建偏离裁决。

alter table subject add column cohort text not null default 'ALL';   -- 适用人群归属(队列)

-- ---------- 计划目录 ----------
create table protocol_version (
  id             bigserial primary key,
  trial_id       text not null,
  version_code   text not null,
  effective_from date not null,             -- 生效日期: 名义访视日 >= 生效日的访视实例适用本版本
  applies_to     text not null default 'ALL', -- 适用人群(ALL 或队列编码)
  note           text,
  unique (trial_id, version_code)
);

create table plan_visit (
  id          bigserial primary key,
  version_id  bigint not null references protocol_version(id),
  visit_code  text not null,
  nominal_day int  not null,                -- 名义访视日(相对入组天数)
  unique (version_id, visit_code)
);

create table plan_activity (
  id             bigserial primary key,
  plan_visit_id  bigint not null references plan_visit(id),
  activity_type  text not null,             -- ASSESSMENT(问诊) / SAMPLING(采样) / IMAGING(影像采集) / DOSING(给药)
  activity_code  text not null,             -- 活动编码, 如 VITAL_SIGNS / HEMATOLOGY / CT_SCAN / STUDY_DRUG / PK_SAMPLING
  nominal_offset int  not null default 0,   -- 活动名义日相对访视名义日的偏移
  offset_start   int  not null,             -- 允许窗口起点(相对访视名义日, 可负)
  offset_end     int  not null,             -- 允许窗口终点
  required       boolean not null default true,  -- 必要活动计入分母
  applies_to     text not null default 'ALL',    -- 适用人群(ALL 或队列编码)
  unique (plan_visit_id, activity_code)
);

-- ---------- 事件整理器 ----------
create table activity_event (
  id                   bigserial primary key,
  event_uid            text not null unique,     -- 事件幂等键(重复上送跳过)
  center_id            text not null,
  external_subject_id  text not null,
  subject_id           bigint references subject(id),  -- null=中心映射缺失
  activity_type        text not null,
  activity_code        text not null,
  occurred_at          timestamptz,              -- 实际发生时间(精确到时间)
  occurred_date        date,                     -- 实际发生日期(仅日期精度)
  time_precision       text not null default 'DATE',
  reported_at          timestamptz,              -- 结果回报时间(采样类; 数据时效判定用, 不影响合规)
  explicit_visit_code  text,                     -- 来源显式访视编码(可空)
  dosing_interrupted   boolean not null default false, -- 给药中断标记事件(仅驱动不适用, 不作活动证据)
  source_ref           text,
  received_at          timestamptz not null default now(),
  linked_visit_code    text,                     -- 整理器判定归属的访视实例
  link_status          text,                     -- LINKED / AMBIGUOUS / UNLINKED / MARKER
  link_reason          text
);
create index idx_activity_event_subject on activity_event (subject_id);

create table event_link_candidate (              -- 关联歧义保留(等距多候选), 不擅自分配
  id         bigserial primary key,
  event_id   bigint not null references activity_event(id),
  visit_code text not null,
  rationale  text not null,
  unique (event_id, visit_code)
);

-- ---------- 窗口计算器(派生, 全量重建) ----------
create table activity_judgment (
  id              bigserial primary key,
  subject_id      bigint not null references subject(id),
  visit_code      text not null,
  activity_code   text not null,
  activity_type   text not null,
  required        boolean not null default true,
  status          text not null,              -- EARLY / ON_WINDOW / LATE / NOT_DONE / INDETERMINATE / NOT_APPLICABLE
  deviation_days  int,                        -- 提前/延迟天数(按窗为0)
  overdue_days    int,                        -- 未完成且窗口已截止时的逾期天数
  late_report     boolean not null default false, -- 数据时效: 窗后回报(合规仍按采样时刻)
  report_lag_days int,                        -- 回报滞后天数(回报-发生)
  version_code    text,                       -- 判定适用的方案版本
  nominal_date    date,
  window_start    date,
  window_end      date,
  event_id        bigint references activity_event(id),
  occurred_date   date,
  reason          text,
  rule_version    text not null,
  unique (subject_id, visit_code, activity_code)
);
create index idx_judgment_status on activity_judgment (status);

create table visit_summary (
  id                 bigserial primary key,
  subject_id         bigint not null references subject(id),
  visit_code         text not null,
  version_code       text,                    -- 本访视实例适用的方案版本
  nominal_date       date,
  status             text not null,           -- ON_WINDOW / DEVIATED / INCOMPLETE / INDETERMINATE / NOT_APPLICABLE
  denominator        int not null default 0,  -- 分母: 适用的必要活动数(不含不适用)
  on_window          int not null default 0,
  early              int not null default 0,
  late               int not null default 0,
  not_done           int not null default 0,
  indeterminate      int not null default 0,
  not_applicable     int not null default 0,
  max_deviation_days int not null default 0,  -- 提前/延迟的最大偏离天数
  rule_version       text not null,
  unique (subject_id, visit_code)
);
