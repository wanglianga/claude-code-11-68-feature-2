package com.example.clinicalrecon.config;

/** 演示用身份上下文：从请求头 X-User / X-Role 提取，用于审计字段（非安全机制）。 */
public final class UserContext {
    private static final ThreadLocal<String> USER = ThreadLocal.withInitial(() -> "system");
    private static final ThreadLocal<String> ROLE = ThreadLocal.withInitial(() -> "ADMIN");

    private UserContext() {}

    public static void set(String user, String role) {
        USER.set(user == null || user.isBlank() ? "system" : user);
        ROLE.set(role == null || role.isBlank() ? "ADMIN" : role);
    }

    public static String user() { return USER.get(); }
    public static String role() { return ROLE.get(); }

    public static void clear() { USER.remove(); ROLE.remove(); }
}
