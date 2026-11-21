package com.example.clinicalrecon.domain;

/** 领域常量：全部以字符串落库，此处集中定义取值域，避免散落字面量。 */
public final class Enums {
    private Enums() {}

    /** 来源系统 */
    public static final class Source {
        public static final String CRF = "CRF";
        public static final String LAB = "LAB";
        public static final String IMAGING = "IMAGING";
        public static final String MONITORING = "MONITORING";
        public static final java.util.Set<String> ALL = java.util.Set.of(CRF, LAB, IMAGING, MONITORING);
    }

    /** 时间精度 */
    public static final class Precision {
        public static final String DATETIME = "DATETIME";
        public static final String DATE = "DATE";
        public static final java.util.Set<String> ALL = java.util.Set.of(DATETIME, DATE);
    }

    /** 字段映射状态 */
    public static final class Mapping {
        public static final String MAPPED = "MAPPED";
        public static final String UNMAPPED = "UNMAPPED";
    }

    /** 原始事实关联状态 */
    public static final class Link {
        public static final String UNLINKED = "UNLINKED";
        public static final String PENDING_DECISION = "PENDING_DECISION";
        public static final String LINKED = "LINKED";
    }

    /** 候选置信度 */
    public static final class Confidence {
        public static final String HIGH = "HIGH";
        public static final String MEDIUM = "MEDIUM";
        public static final String LOW = "LOW";
    }

    /** 候选状态 */
    public static final class Candidate {
        public static final String CANDIDATE = "CANDIDATE";
        public static final String CONFIRMED = "CONFIRMED";
        public static final String REJECTED = "REJECTED";
    }

    /** 协调状态（对外发布的四态） */
    public static final class Recon {
        public static final String CONFIRMED = "CONFIRMED";
        public static final String CONFLICT = "CONFLICT";
        public static final String MISSING = "MISSING";
        public static final String PENDING = "PENDING";
    }

    /** 协调规则码 */
    public static final class Rule {
        public static final String SINGLE_SOURCE = "SINGLE_SOURCE";
        public static final String MULTI_AGREE = "MULTI_AGREE";
        public static final String MUTUALLY_EXCLUSIVE = "MUTUALLY_EXCLUSIVE";
        public static final String MANUAL_RESOLUTION = "MANUAL_RESOLUTION";
        public static final String NO_SOURCE = "NO_SOURCE";
        public static final String UNMAPPED_FIELD = "UNMAPPED_FIELD";
        public static final String LINK_UNDECIDED = "LINK_UNDECIDED";
        public static final String NO_CANDIDATE = "NO_CANDIDATE";
    }

    /** 协调-来源角色 */
    public static final class SourceRole {
        public static final String CONFIRMING = "CONFIRMING";
        public static final String CONFLICTING = "CONFLICTING";
        public static final String SUPERSEDED = "SUPERSEDED";
        public static final String PENDING_EVIDENCE = "PENDING_EVIDENCE";
    }

    /** 质疑状态 */
    public static final class Query {
        public static final String OPEN = "OPEN";
        public static final String ANSWERED = "ANSWERED";
        public static final String CLOSED = "CLOSED";
        public static final String REOPENED = "REOPENED";
    }

    /** 质疑类型 */
    public static final class Issue {
        public static final String CONFLICT = "CONFLICT";
        public static final String UNMAPPED_FIELD = "UNMAPPED_FIELD";
        public static final String LINK_AMBIGUITY = "LINK_AMBIGUITY";
        public static final String MONITORING_FINDING = "MONITORING_FINDING";
        public static final String MISSING_DATA = "MISSING_DATA";
    }

    /** 责任角色 */
    public static final class Role {
        public static final String DM = "DM";       // 数据管理
        public static final String CRA = "CRA";     // 临床监查
        public static final String LAB = "LAB";     // 中心实验室
        public static final String PI = "PI";       // 主要研究者
    }

    /** 访视活动类型（窗口符合性） */
    public static final class ActivityType {
        public static final String ASSESSMENT = "ASSESSMENT";   // 问诊
        public static final String SAMPLING = "SAMPLING";       // 采样
        public static final String IMAGING = "IMAGING";         // 影像采集
        public static final String DOSING = "DOSING";           // 给药
        public static final java.util.Set<String> ALL = java.util.Set.of(ASSESSMENT, SAMPLING, IMAGING, DOSING);
    }

    /** 活动级窗口判定状态 */
    public static final class Judgment {
        public static final String EARLY = "EARLY";                       // 提前
        public static final String ON_WINDOW = "ON_WINDOW";               // 按窗
        public static final String LATE = "LATE";                         // 延迟
        public static final String NOT_DONE = "NOT_DONE";                 // 未完成
        public static final String INDETERMINATE = "INDETERMINATE";       // 无法判定
        public static final String NOT_APPLICABLE = "NOT_APPLICABLE";     // 不适用(给药中断/人群不适用)
    }

    /** 访视级汇总状态 */
    public static final class VisitStatus {
        public static final String ON_WINDOW = "ON_WINDOW";               // 全部按窗
        public static final String DEVIATED = "DEVIATED";                 // 已完成但含提前/延迟
        public static final String INCOMPLETE = "INCOMPLETE";             // 含未完成
        public static final String INDETERMINATE = "INDETERMINATE";       // 含无法判定
        public static final String NOT_APPLICABLE = "NOT_APPLICABLE";     // 不适用(非漏访)
    }

    /** 活动事件关联状态（事件整理器） */
    public static final class EventLink {
        public static final String LINKED = "LINKED";           // 已定位访视实例
        public static final String AMBIGUOUS = "AMBIGUOUS";     // 多候选保留, 不擅自分配
        public static final String UNLINKED = "UNLINKED";       // 无法定位(映射缺失/无时间/远离窗口)
        public static final String MARKER = "MARKER";           // 给药中断标记事件, 不作活动证据
    }
}
