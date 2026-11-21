-- 临床试验多源事实对账与质疑数据服务 - 初始 schema
-- 分层：主数据(受试者/中心映射/访视窗口/字段目录) -> 受理仓 -> 关联 -> 协调(含历史) -> 质疑 -> 快照

create table subject (
  id            bigserial primary key,
  trial_id      text not null,
  center_id     text not null,
  subject_key   text not null unique,        -- 统一受试者编号, 如 SUBJ-001
  enrolled_date date not null
);

create table center_subject_map (
  id                  bigserial primary key,
  center_id           text not null,
  external_subject_id text not null,          -- 来源系统内的受试者标识
  subject_id          bigint not null references subject(id),
  unique (center_id, external_subject_id)
);

create table visit_schedule (
  id           bigserial primary key,
  trial_id     text not null,
  visit_code   text not null,
  anchor_day   int  not null,                 -- 方案锚点(相对入组天数)
  window_start int  not null,                 -- 窗口起点(相对入组天数, 可负)
  window_end   int  not null,                 -- 窗口终点(相对入组天数)
  unique (trial_id, visit_code)
);

create table field_catalog (
  field_key             text primary key,     -- 统一字段目录键, 如 LAB_ALT
  label                 text not null,
  value_type            text not null,        -- NUMERIC / TEXT
  unit                  text,
  required_for_analysis boolean not null default false,
  source_field_map      jsonb not null        -- {"CRF":["SYSBP"],"LAB":["ALT"]}
);

create table source_batch (
  id             bigserial primary key,
  source_system  text not null,               -- CRF / LAB / IMAGING / MONITORING
  center_id      text not null,
  batch_label    text not null,
  received_at    timestamptz not null default now(),
  payload_digest text not null,               -- 载荷摘要 sha256
  fact_count     int not null default 0,
  status         text not null default 'RECEIVED',
  unique (source_system, center_id, batch_label)
);

create table raw_fact (
  id                  bigserial primary key,
  batch_id            bigint not null references source_batch(id),
  source_system       text not null,
  source_record_id    text not null,          -- 来源记录号(修订按此归并)
  source_revision     int  not null,
  center_id           text not null,
  external_subject_id text not null,
  external_visit_code text,                   -- 来源给出的访视编码(可空)
  source_field_code   text not null,          -- 来源字段编码(保留来源语义)
  field_key           text,                   -- 映射后的统一字段键; null=未映射
  mapping_status      text not null,          -- MAPPED / UNMAPPED
  value_text          text,
  value_num           numeric,
  unit                text,
  event_time          timestamptz,            -- 事件时间(精确到时间)
  event_date          date,                   -- 事件日期(仅日期精度)
  time_precision      text not null,          -- DATETIME / DATE
  payload_summary     text not null,          -- 原始载荷摘要(单条)
  received_at         timestamptz not null default now(),
  valid               boolean not null default true,   -- false=被新修订取代或重复
  invalid_reason      text,
  superseded_by       bigint,
  link_status         text not null default 'UNLINKED' -- UNLINKED / PENDING_DECISION / LINKED
);
create index idx_raw_fact_record on raw_fact (source_system, source_record_id);
create index idx_raw_fact_link on raw_fact (link_status) where valid;

create table link_candidate (
  id          bigserial primary key,
  raw_fact_id bigint not null references raw_fact(id),
  subject_id  bigint not null references subject(id),
  visit_code  text not null,
  confidence  text not null,                  -- HIGH / MEDIUM / LOW
  rationale   text not null,                  -- 置信理由(命中规则说明)
  status      text not null default 'CANDIDATE', -- CANDIDATE / CONFIRMED / REJECTED
  decided_by  text,
  decided_at  timestamptz,
  unique (raw_fact_id, subject_id, visit_code)
);

create table fact_link (
  raw_fact_id  bigint primary key references raw_fact(id),
  subject_id   bigint not null references subject(id),
  visit_code   text not null,
  candidate_id bigint references link_candidate(id),
  decided_by   text not null,
  created_at   timestamptz not null default now()
);

create table reconcile_run (
  seq             bigserial primary key,      -- 全局协调序号, 快照据此复算
  started_at      timestamptz not null default now(),
  trigger_note    text,
  changed_groups  int not null default 0
);

create table reconciled_fact (
  id              bigserial primary key,
  subject_id      bigint references subject(id),
  visit_code      text,
  field_key       text not null,
  status          text not null,              -- CONFIRMED / CONFLICT / MISSING / PENDING
  confirmed_value text,
  rule_code       text not null,              -- SINGLE_SOURCE / MULTI_AGREE / MUTUALLY_EXCLUSIVE / MANUAL_RESOLUTION / NO_SOURCE / UNMAPPED_FIELD / LINK_UNDECIDED / NO_CANDIDATE
  rule_version    text not null,
  override_value  text,                       -- 人工裁定值(源修订签名变化前有效)
  override_sig    text,                       -- 裁定时的来源修订签名
  source_sig      text,                       -- 当前有效来源修订签名 "LAB:L-1001#2;LAB:L-1002#1"
  version         int  not null default 1,
  seq             bigint not null,            -- 最近一次变更所属协调序号
  updated_at      timestamptz not null default now()
);
-- 逻辑唯一键(空安全): 同一受试者/访视/字段只有一条协调记录
create unique index uq_recon_group on reconciled_fact
  (coalesce(subject_id, -1), coalesce(visit_code, ''), field_key);

create table reconciled_fact_source (
  id                 bigserial primary key,
  reconciled_fact_id bigint not null references reconciled_fact(id),
  raw_fact_id        bigint not null references raw_fact(id),
  role               text not null,           -- CONFIRMING / CONFLICTING / SUPERSEDED
  unique (reconciled_fact_id, raw_fact_id)
);

create table reconciled_fact_history (
  id                bigserial primary key,
  -- 不建外键: 待判(PENDING)占位行在关联裁定后可被清理, 但历史必须保留以支撑快照复算
  reconciled_fact_id bigint not null,
  version           int not null,
  seq               bigint not null,
  status            text not null,
  confirmed_value   text,
  rule_code         text not null,
  rule_version      text not null,
  created_at        timestamptz not null default now(),
  unique (reconciled_fact_id, version)
);
create index idx_recon_history_seq on reconciled_fact_history (seq);

create table query_item (
  id                bigserial primary key,
  -- on delete set null: 待判占位行被清理时保留质疑审计痕迹
  reconciled_fact_id bigint references reconciled_fact(id) on delete set null,
  raw_fact_id       bigint references raw_fact(id),
  issue_type        text not null,            -- CONFLICT / UNMAPPED_FIELD / LINK_AMBIGUITY / MONITORING_FINDING / MISSING_DATA
  title             text not null,
  detail            text,
  assignee_role     text not null,            -- 责任角色 DM / CRA / LAB / PI
  status            text not null default 'OPEN', -- OPEN / ANSWERED / CLOSED / REOPENED
  opened_at         timestamptz not null default now(),
  opened_by         text not null default 'system',
  closed_at         timestamptz,
  closed_by         text,
  last_known_fact_version int                 -- 关闭/回复时确认的协调版本, 源再更正据此重开
);
create index idx_query_status on query_item (status);

create table query_response (
  id                bigserial primary key,
  query_id          bigint not null references query_item(id),
  responder         text not null,
  responder_role    text not null,
  response_text     text not null,
  evidence_ref      text,                     -- 回复证据(文件/记录引用)
  fact_version_at_reply int,                  -- 回复对应的协调事实版本
  source_sig_at_reply   text,                 -- 回复对应的来源修订签名
  created_at        timestamptz not null default now()
);

create table analysis_snapshot (
  id          bigserial primary key,
  name        text not null unique,
  created_at  timestamptz not null default now(),
  created_by  text not null,
  gate        jsonb not null,                 -- {"allowedStatuses":["CONFIRMED"],"requiredFields":[...]}
  seq         bigint not null,                -- 快照对应的协调序号(复算基准)
  row_count   int not null default 0,
  excluded    jsonb                           -- 各状态被排除计数 {"CONFLICT":2,...}
);

create table analysis_snapshot_row (
  id                 bigserial primary key,
  snapshot_id        bigint not null references analysis_snapshot(id),
  subject_id         bigint references subject(id),
  visit_code         text,
  field_key          text not null,
  value              text not null,
  reconciled_fact_id bigint not null references reconciled_fact(id),
  reconciled_version int not null,            -- 生成该行时的协调版本(回溯锚点)
  rule_code          text not null,
  unique (snapshot_id, reconciled_fact_id)
);
