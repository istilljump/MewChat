package com.mewchat.common.security;

import com.mewchat.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 访问令牌的签发与校验（HMAC-SHA256 签名的自包含令牌）。
 *
 * <p>令牌形如 {@code base64url(payload).base64url(HMAC-SHA256(payload))}，
 * payload 为 {@code userId|过期时间戳|用户名}。这是 JWS 的最小可用子集：
 * 与服务端不共享密钥就无法伪造出合法签名。
 *
 * <p><b>为什么用 JDK 自带的 {@link Mac} 而不是手写"加密算法"</b>：
 * 这里没有任何自创密码学 —— 算法是标准的 HMAC-SHA256，密钥是标准的
 * {@link SecretKeySpec}，比较是 JDK 提供的常数时间比较。自研的是<b>令牌格式</b>
 * （怎么拼 payload、拼几个字段），不是密码学原语。
 * 之所以不引入 JJWT 之类的库：本项目只需要"签一个短令牌再验回来"这一件事，
 * 为此增加一个依赖并不划算；将来若要用 RS256、密钥轮转、JWKS，
 * 应当换成成熟库而不是继续在这里加。
 *
 * <p><b>已知的取舍（部署前必须知道）</b>：
 * <ul>
 *     <li><b>无法撤销单个令牌</b>：令牌自包含，服务端不存状态。要强制下线只能换密钥，
 *         那会让所有人一起失效。若需要"踢掉某个用户"，得引入黑名单或改用会话表</li>
 *     <li><b>不校验用户是否仍存在/被禁用</b>：验签只看签名与有效期，不查库。
 *         这是无状态令牌的通病，代价是换来"每个请求零次数据库查询"。
 *         账号被禁用后，其已签发的令牌在有效期内仍可用</li>
 *     <li>payload 只是 Base64 编码、<b>不是加密</b>，任何人可读。因此不放敏感信息，
 *         这里只有用户ID与用户名</li>
 * </ul>
 *
 * @author MewChat
 */
@Component
public class TokenCodec {

    private static final Logger log = LoggerFactory.getLogger(TokenCodec.class);

    /** 签名算法。选 HMAC-SHA256：对称密钥场景下强度足够，且 JDK 必备 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** payload 字段分隔符 */
    private static final String FIELD_SEPARATOR = "|";

    /** 密钥最小长度，低于此值直接拒绝启动 */
    private static final int MIN_SECRET_LENGTH = 16;

    /** 令牌中 payload 与签名的分隔符 */
    private static final char TOKEN_SEPARATOR = '.';

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    private final Duration validity;

    /**
     * 构造令牌编解码器。
     *
     * <p><b>{@code @Autowired} 不能省</b>：本类有两个构造器（另一个供测试指定有效期），
     * Spring 遇到多个构造器且都未标注时会退回去找无参构造器，找不到就直接报
     * "No default constructor found" 导致启动失败。标注之后由 Spring 用这个。
     *
     * @param properties 认证配置
     */
    @Autowired
    public TokenCodec(AuthProperties properties) {
        this(properties, resolveValidity(properties));
    }

    /**
     * 用指定的有效期构造。
     *
     * <p>单独留这个入口，是为了让"令牌已过期必须被拒绝"这条分支可测：
     * 走 {@link AuthProperties} 时有效期下限是 1 分钟，测试没法在同一秒内等它过期，
     * 而漏掉过期校验的后果是令牌等于永久有效 —— 这是本类最该被验证的安全属性之一。
     * 传入一个已经过去（或极短）的有效期就能直接验证。
     *
     * @param properties 认证配置
     * @param validity   令牌有效期
     */
    TokenCodec(AuthProperties properties, Duration validity) {
        String configured = properties.getTokenSecret();
        if (!StringUtils.hasText(configured) || configured.length() < MIN_SECRET_LENGTH) {
            // 启动即失败，而不是退化成"没有签名"或"用默认密钥"：
            // 一个弱密钥等于所有人都能伪造身份，而这种系统在功能上看起来完全正常
            throw new IllegalStateException("mewchat.auth.token-secret 未配置或长度不足 "
                    + MIN_SECRET_LENGTH + " 位，请通过环境变量 MEWCHAT_TOKEN_SECRET 提供足够随机的密钥");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        this.validity = validity;
    }

    /**
     * 解析配置中的有效期。
     *
     * <p>下限取 1 分钟：配成 0 或负数会让刚签发的令牌立刻失效，
     * 表现为"登录成功但立刻 401"，排查起来很费时间。
     *
     * @param properties 认证配置
     * @return 有效期
     */
    private static Duration resolveValidity(AuthProperties properties) {
        return Duration.ofMinutes(Math.max(1L, properties.getTokenExpireMinutes()));
    }

    /**
     * 签发令牌。
     *
     * <p><b>把用户类型放进令牌带来的取舍</b>：接口权限直接由令牌里的类型决定，
     * 好处是每个请求零次数据库查询；代价是<b>类型变更（提权或降权）不会立即生效</b>，
     * 要等令牌过期（默认 2 小时）或换密钥（会让所有人一起失效）。
     * 这与"不校验账号是否被禁用"是同一类取舍 —— 无状态换零查询。
     * 若需要"改权限立即生效"，就得在每个请求上查一次库，或者引入黑名单/版本号。
     *
     * @param userId   用户ID
     * @param username 用户名，仅用于日志与展示
     * @param userType 用户类型：1客户 2客服 3管理员，决定接口权限
     * @return 令牌与过期时间
     */
    public AuthToken issue(long userId, String username, int userType) {
        Instant expiresAt = Instant.now().plus(validity);
        // 用户名放最后一个字段，这样即使用户名里含有分隔符，
        // 按 limit=4 切分也能正确还原（前三个字段都是数字，不可能含分隔符）
        String payload = userId + FIELD_SEPARATOR + expiresAt.getEpochSecond()
                + FIELD_SEPARATOR + userType
                + FIELD_SEPARATOR + (username == null ? "" : username);
        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(sign(encodedPayload));
        return new AuthToken(encodedPayload + TOKEN_SEPARATOR + signature, expiresAt);
    }

    /**
     * 校验令牌并取出其中的身份信息。
     *
     * <p>任何不合法（格式错、签名错、已过期）都返回 {@link Optional#empty()}，
     * <b>不抛异常</b>：令牌是外部输入，服务端不能因为收到一个畸形字符串就出错。
     *
     * @param token 令牌，可为 null
     * @return 身份信息；不合法或已过期时为空
     */
    public Optional<TokenPayload> verify(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }

        int separatorIndex = token.indexOf(TOKEN_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == token.length() - 1) {
            log.debug("令牌格式不合法：缺少签名段");
            return Optional.empty();
        }
        String encodedPayload = token.substring(0, separatorIndex);
        String encodedSignature = token.substring(separatorIndex + 1);

        byte[] actualSignature;
        try {
            actualSignature = DECODER.decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            log.debug("令牌签名段不是合法的 Base64URL");
            return Optional.empty();
        }

        // 拒绝非规范编码：Base64 最后一个字符里可能含解码时被丢弃的填充位
        // （32 字节签名编码为 43 个字符，末字符只有 2 位有效）。若不校验，
        // 同一个签名会有多个不同的字符串表示，而它们都能通过下面的字节比较 ——
        // 表现为"改动签名的第末位字符后令牌依然有效"。这不是身份伪造，
        // 但让"改动过的令牌"与"原令牌"都会被接受，属于签名可变形。
        // 重新编码后比对字符串即可，成本可忽略且不涉及密钥，无时序侧信道。
        if (!ENCODER.encodeToString(actualSignature).equals(encodedSignature)) {
            log.debug("令牌签名段不是规范的 Base64URL 编码");
            return Optional.empty();
        }

        // 必须用常数时间比较：逐字节比较会在第一个不同的字节处提前返回，
        // 攻击者据此可以一个字节一个字节地试出正确签名。
        // 先验签再看内容，也避免了对未信任数据做解析
        if (!MessageDigest.isEqual(sign(encodedPayload), actualSignature)) {
            log.debug("令牌签名校验失败");
            return Optional.empty();
        }

        return parsePayload(encodedPayload);
    }

    /**
     * 解析已通过验签的 payload。
     *
     * @param encodedPayload Base64URL 编码的 payload
     * @return 身份信息；格式不合法或已过期时为空
     */
    private Optional<TokenPayload> parsePayload(String encodedPayload) {
        String payload;
        try {
            payload = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String[] fields = payload.split(Pattern.quote(FIELD_SEPARATOR), 4);
        if (fields.length != 4) {
            log.debug("令牌 payload 字段数不正确");
            return Optional.empty();
        }

        try {
            long userId = Long.parseLong(fields[0]);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[1]));
            int userType = Integer.parseInt(fields[2]);
            if (expiresAt.isBefore(Instant.now())) {
                log.debug("令牌已过期：userId={} 过期于 {}", userId, expiresAt);
                return Optional.empty();
            }
            return Optional.of(new TokenPayload(userId, fields[3], userType, expiresAt));
        } catch (NumberFormatException e) {
            log.debug("令牌 payload 中的数字字段格式不正确");
            return Optional.empty();
        }
    }

    /**
     * 对数据做 HMAC-SHA256 签名。
     *
     * @param data 待签名数据
     * @return 签名字节
     */
    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 是 JDK 必备算法、密钥也一定合法（构造时已校验），走不到这里
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    /**
     * 新签发的令牌。
     *
     * @param token     令牌字符串
     * @param expiresAt 过期时间
     */
    public record AuthToken(String token, Instant expiresAt) {
    }

    /**
     * 令牌承载的身份信息。
     *
     * @param userId    用户ID
     * @param username  用户名，仅供展示与日志
     * @param userType  用户类型：1客户 2客服 3管理员，接口权限由它决定
     * @param expiresAt 过期时间
     */
    public record TokenPayload(long userId, String username, int userType, Instant expiresAt) {
    }
}
