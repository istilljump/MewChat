package com.mewchat.common.constant;

/**
 * 全局通用常量。
 *
 * <p>只放"与具体业务无关、且在多处复用"的值。业务专属常量放各自的
 * {@code *Constants}，不要往这里堆。
 *
 * @author MewChat
 */
public final class CommonConstants {

    /**
     * 工具类，禁止实例化。
     */
    private CommonConstants() {
    }

    /* ==================== 编码与格式 ==================== */

    /** UTF-8 编码名 */
    public static final String UTF8 = "UTF-8";

    /** 日期格式：yyyy-MM-dd */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 日期时间格式：yyyy-MM-dd HH:mm:ss */
    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 时区：上海 */
    public static final String TIME_ZONE_SHANGHAI = "Asia/Shanghai";

    /* ==================== 逻辑删除标记 ==================== */

    /** 未删除 */
    public static final int NOT_DELETED = 0;

    /** 已删除 */
    public static final int DELETED = 1;

    /* ==================== 分页 ==================== */

    /** 默认页码，从 1 开始 */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页条数上限，防止一次拉取过多数据拖垮数据库 */
    public static final int MAX_PAGE_SIZE = 100;

    /* ==================== 请求头 ==================== */

    /** 链路追踪 ID，用于把一次请求的各层日志串起来 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 携带 API Key 的请求头名 */
    public static final String HEADER_API_KEY = "X-Api-Key";
}
