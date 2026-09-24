package com.mewchat.common.security;

/**
 * 已认证用户 —— 放进 SecurityContext 的 principal。
 *
 * <p>只带用户ID、用户名与用户类型，不带密码等敏感字段：
 * 接口层需要的就这几样（用户ID用于会话归属，类型用于接口权限），
 * 把实体或令牌整体塞进 principal 只会让"能不能信这个对象"变得模糊。
 *
 * <p>它是不可变记录，因此可以安全地在一次请求内被多个组件读取。
 *
 * <p><b>权限来自令牌里的类型，而不是每次查库</b>：换来的是每个请求零次数据库查询，
 * 代价是类型变更不会立即生效（要等令牌过期）。这个取舍与
 * {@code TokenCodec} 里"不校验账号是否被禁用"是同一类。
 *
 * @param userId   用户ID，来自令牌
 * @param username 用户名，仅供展示与日志
 * @param userType 用户类型：1客户 2客服 3管理员，取值见 {@link #TYPE_CUSTOMER} 等
 */
public record AuthenticatedUser(long userId, String username, int userType) {

    /** 用户类型：客户 */
    public static final int TYPE_CUSTOMER = 1;

    /** 用户类型：客服 */
    public static final int TYPE_AGENT = 2;

    /** 用户类型：管理员 */
    public static final int TYPE_ADMIN = 3;

    /** Spring Security 权限名：客户 */
    public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";

    /** Spring Security 权限名：客服 */
    public static final String ROLE_AGENT = "ROLE_AGENT";

    /** Spring Security 权限名：管理员 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * 是否管理员。
     *
     * @return true 表示用户类型为管理员
     */
    public boolean isAdmin() {
        return userType == TYPE_ADMIN;
    }

    /**
     * 换算成 Spring Security 的权限名。
     *
     * <p>未知类型一律按最小权限（客户）处理，而不是落到管理员 ——
     * 类型字段将来若新增取值（比如"外部审计"），漏改这里的后果必须是"权限变小"，
     * 不能是"权限变大"。
     *
     * @return 权限名
     */
    public String authority() {
        if (userType == TYPE_ADMIN) {
            return ROLE_ADMIN;
        }
        if (userType == TYPE_AGENT) {
            return ROLE_AGENT;
        }
        return ROLE_CUSTOMER;
    }
}
