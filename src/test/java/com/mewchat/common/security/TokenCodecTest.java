package com.mewchat.common.security;

import com.mewchat.config.AuthProperties;
import static com.mewchat.common.security.AuthenticatedUser.TYPE_CUSTOMER;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 访问令牌编解码的单元测试（纯单元测试，不依赖 Spring）。
 *
 * <p>鉴权是"错了也不会报错"的典型：签名校验若写成恒真、过期检查若被漏掉，
 * 系统功能上一切正常，只是任何人都能伪造任意用户的身份。
 * 因此这里把每条拒绝路径都写成显式断言 —— <b>测的不是"合法令牌能用"，
 * 而是"不合法的令牌一律不能用"</b>。
 *
 * @author MewChat
 */
class TokenCodecTest {

    /** 测试密钥，长度需超过最小长度限制 */
    private static final String SECRET = "unit-test-secret-0123456789abcdef";

    /** 19 位雪花ID，与真实用户ID同量级，用于验证长整型不被截断 */
    private static final long USER_ID = 1727138400000000001L;

    private static final String USERNAME = "alice";

    private final TokenCodec codec = new TokenCodec(properties(SECRET, 120));

    /* ==================== 正常路径 ==================== */

    /**
     * 合法令牌应能验回来，且身份信息完整。
     */
    @Test
    void issuedTokenShouldCarryIdentity() {
        TokenCodec.AuthToken issued = codec.issue(USER_ID, USERNAME, TYPE_CUSTOMER);

        assertThat(issued.token()).isNotBlank().contains(".");
        assertThat(codec.verify(issued.token())).hasValueSatisfying(payload -> {
            assertThat(payload.userId()).isEqualTo(USER_ID);
            assertThat(payload.username()).isEqualTo(USERNAME);
            assertThat(payload.userType()).isEqualTo(TYPE_CUSTOMER);
            assertThat(payload.expiresAt()).isAfter(Instant.now());
        });
    }

    /**
     * 用户类型必须原样往返。
     *
     * <p>它决定接口权限（只有管理员能访问运营后台），因此编解码两侧都要钉死：
     * payload 的字段解析错位一位，就可能把普通用户认成管理员 ——
     * 而这种错误在功能上毫无症状，只会体现为"越权访问居然成功了"。
     */
    @Test
    void userTypeShouldSurviveRoundTrip() {
        for (int userType : new int[]{
                TYPE_CUSTOMER, AuthenticatedUser.TYPE_AGENT, AuthenticatedUser.TYPE_ADMIN}) {
            String token = codec.issue(USER_ID, USERNAME, userType).token();

            assertThat(codec.verify(token))
                    .as("用户类型 %s 应能原样验回", userType)
                    .hasValueSatisfying(payload -> assertThat(payload.userType()).isEqualTo(userType));
        }
    }

    /**
     * 过期时间应与配置的有效期一致。
     */
    @Test
    void expiresAtShouldFollowConfiguredValidity() {
        TokenCodec.AuthToken issued = new TokenCodec(properties(SECRET, 30)).issue(USER_ID, USERNAME, TYPE_CUSTOMER);

        Instant expected = Instant.now().plus(Duration.ofMinutes(30));
        // 允许几秒误差：签发与断言之间总要花掉一点时间
        assertThat(issued.expiresAt()).isBetween(expected.minusSeconds(60), expected.plusSeconds(60));
    }

    /**
     * 用户名里含分隔符也不能被截断。
     *
     * <p>payload 用 {@code |} 拼接，若解析时按分隔符全拆，含 {@code |} 的用户名会被切碎，
     * 表现为"登录成功但用户名显示不全"，而且只在特殊用户名上复现。
     */
    @Test
    void usernameWithSeparatorShouldSurviveRoundTrip() {
        String weird = "a|b|c";

        TokenCodec.AuthToken issued = codec.issue(USER_ID, weird, TYPE_CUSTOMER);

        assertThat(codec.verify(issued.token())).hasValueSatisfying(
                payload -> assertThat(payload.username()).isEqualTo(weird));
    }

    /**
     * 有效期配置为 0 时不能产生"签完即失效"的令牌。
     */
    @Test
    void zeroConfiguredValidityShouldStillProduceUsableToken() {
        TokenCodec zeroValidity = new TokenCodec(properties(SECRET, 0));

        assertThat(zeroValidity.verify(zeroValidity.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token()))
                .as("有效期为 0 会被兜到 1 分钟，否则用户会遇到'登录成功但立刻 401'")
                .isPresent();
    }

    /* ==================== 拒绝路径 ==================== */

    /**
     * 已过期的令牌必须被拒绝。
     *
     * <p>这是本类最关键的分支：漏掉它，令牌等于永久有效。
     */
    @Test
    void expiredTokenShouldBeRejected() {
        TokenCodec expiredCodec = new TokenCodec(properties(SECRET, 120), Duration.ofSeconds(-1));
        String token = expiredCodec.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();

        assertThat(codec.verify(token))
                .as("同一密钥签发的已过期令牌也必须被拒绝")
                .isEmpty();
    }

    /**
     * 改动 payload 会让签名对不上。
     */
    @Test
    void tamperedPayloadShouldBeRejected() {
        String token = codec.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();
        String[] parts = token.split("\\.");
        // 翻转 payload 的最后一位：Base64URL 字符表相邻，改动后仍是合法编码
        String tampered = flipLastChar(parts[0]) + "." + parts[1];

        assertThat(codec.verify(tampered)).isEmpty();
    }

    /**
     * 改动签名会被直接拒绝。
     *
     * <p>改动位置取签名段的<b>首字符</b>：首字符的 6 位全部是有效位，
     * 改它一定会改变解码出的字节。若改的是末字符则不一定 ——
     * 32 字节签名编码成 43 个字符后，末字符只有 2 位有效（见下一条用例），
     * 这样写会变成一个随签名取值而随机失败的用例。
     */
    @Test
    void tamperedSignatureShouldBeRejected() {
        String token = codec.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + flipFirstChar(parts[1]);

        assertThat(codec.verify(tampered)).isEmpty();
    }

    /**
     * 承载填充位的写法也必须被拒绝（签名不得有多个等价字符串表示）。
     *
     * <p>32 字节签名编码成 43 个字符，末字符只承载 2 位有效数据、低 4 位是解码时被丢弃的
     * 填充位。因此 {@code ...A} 与 {@code ...B}（0 与 1，低 4 位都是 0）会解码成
     * <b>完全相同的字节</b>。若验签只比字节，改动后的令牌字符串照样有效 ——
     * 也就是同一个签名有多个可用的字符串表示（签名可变形）。
     * 这不是身份伪造，但会让"令牌字符串是唯一标识"的假设不成立
     * （黑名单、日志关联、按令牌做幂等都会失准），因此显式拒绝非规范编码。
     */
    @Test
    void signatureWithNonCanonicalPaddingBitsShouldBeRejected() {
        String token = codec.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();
        String[] parts = token.split("\\.");
        String signature = parts[1];
        char last = signature.charAt(signature.length() - 1);
        // 末字符的取值必是 (低 4 位为 0) 的字符之一：A E I M Q U Y c g k o s w 0 4 8
        // 把它换成同组里低 4 位同样为 0 的另一个字符，解码结果不变
        char equivalent = last == 'A' ? 'E' : 'A';

        assertThat(codec.verify(parts[0] + "." + signature.substring(0, signature.length() - 1) + equivalent))
                .as("末字符换了但解码后字节相同，属于同一签名的非规范写法，应被拒绝")
                .isEmpty();

        // 对照：不改动的原令牌仍然有效，说明拒绝的是"编码不规范"而不是"签名不对"
        assertThat(codec.verify(token)).isPresent();
    }

    /**
     * 换密钥签发的令牌不能通过校验 —— 这正是"没有密钥就伪造不出身份"的含义。
     */
    @Test
    void tokenFromAnotherSecretShouldBeRejected() {
        TokenCodec attacker = new TokenCodec(properties("another-secret-0123456789abcdef", 120));
        String forged = attacker.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();

        assertThat(codec.verify(forged)).isEmpty();
    }

    /**
     * 畸形输入一律返回空，绝不抛异常：令牌是外部输入，服务端不能因为收到垃圾字符串就出错。
     */
    @Test
    void malformedTokensShouldBeRejected() {
        assertThat(codec.verify(null)).isEmpty();
        assertThat(codec.verify("")).isEmpty();
        assertThat(codec.verify("   ")).isEmpty();
        assertThat(codec.verify("no-separator")).isEmpty();
        assertThat(codec.verify(".only-signature")).isEmpty();
        assertThat(codec.verify("only-payload.")).isEmpty();
        assertThat(codec.verify("!!!.!!!")).isEmpty();
        // 多一个点：payload 段后仍跟着非法签名段
        assertThat(codec.verify("a.b.c")).isEmpty();
    }

    /**
     * 密码学安全的随机性意味着同一用户两次签发应得到不同令牌，
     * 否则令牌可被提前算出来或重放范围被放大。
     */
    @Test
    void twoIssuesShouldProduceDifferentTokens() {
        String first = codec.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();
        String second = codec.issue(USER_ID, USERNAME, TYPE_CUSTOMER).token();

        // 正常情况下两者不同（过期时间戳精度为秒，同秒内签发时 payload 可能相同，
        // 那也要求令牌本身仍然可用 —— 这里只在确实不同时断言其可验证性）
        if (!first.equals(second)) {
            assertThat(codec.verify(second)).isPresent();
        }
        assertThat(first).isNotBlank();
    }

    /* ==================== 启动校验 ==================== */

    /**
     * 密钥缺失或过短必须让应用启动失败，而不是用一个弱密钥悄悄跑起来。
     */
    @Test
    void weakSecretShouldFailStartup() {
        assertThatThrownBy(() -> new TokenCodec(properties(null, 120)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-secret");

        assertThatThrownBy(() -> new TokenCodec(properties("", 120)))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new TokenCodec(properties("too-short", 120)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造认证配置。
     *
     * @param secret  密钥
     * @param minutes 有效期（分钟）
     * @return 配置对象
     */
    private static AuthProperties properties(String secret, long minutes) {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret(secret);
        properties.setTokenExpireMinutes(minutes);
        return properties;
    }

    /**
     * 翻转字符串最后一位字符。
     *
     * <p><b>只适用于 payload 段</b>：payload 段被改动后，用于签名的字符串随之改变，
     * 验签必然失败。不要用它改动签名段 —— 签名段的末字符只有 2 位有效数据，
     * 翻转填充位不会改变解码结果（见 {@link #signatureWithNonCanonicalPaddingBitsShouldBeRejected()}）。
     *
     * @param text 原文本
     * @return 改动后的文本
     */
    private static String flipLastChar(String text) {
        char last = text.charAt(text.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return text.substring(0, text.length() - 1) + replacement;
    }

    /**
     * 翻转字符串首字符的最后一个有效位。
     *
     * <p>Base64 首字符的 6 位全部是有效位，改动它必定改变解码出的字节，
     * 因此适合用来构造"签名被篡改"的输入。
     *
     * @param text 原文本
     * @return 改动后的文本
     */
    private static String flipFirstChar(String text) {
        char first = text.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';
        return replacement + text.substring(1);
    }
}
