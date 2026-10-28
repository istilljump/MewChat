package com.mewchat.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.dao.mysql.entity.RefreshToken;
import com.mewchat.dao.mysql.mapper.RefreshTokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 刷新令牌服务：签发、旋转、吊销。
 *
 * <p><b>与访问令牌的分工</b>：访问令牌自包含、无状态、不可吊销（换密钥全体失效）；
 * 刷新令牌落库、可吊销，是"续期""登出"的执行点。刷新令牌本身不进任何业务接口 ——
 * 它只在认证接口（登录/刷新/登出）之间流转。
 *
 * <p><b>三条安全约定</b>：
 * <ul>
 *     <li><b>只存 SHA-256 摘要</b>：拖库换不出可用凭据（原文只存在于用户端）。
 *     校验时对来令做同样的摘要再按索引查，无时序比较问题（摘要表无密钥可旁路）</li>
 *     <li><b>旋转用条件更新</b>：{@code deleted=0} 的行才允许置 1，受影响行数为 0 即失败
     * —— 同一个旧令牌被并发使用两次时只有一次成功（旋转的防复用语义），
 *     check-then-act 的写法在这里会放过复用</li>
 *     <li><b>吊销幂等</b>：登出对已失效令牌同样返回成功 ——
 *     登出的目标是"确保它不再可用"，重复登出不该报错</li>
 * </ul>
 *
 * @author MewChat
 */
@Service
public class RefreshTokenService extends ServiceImpl<RefreshTokenMapper, RefreshToken> {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 令牌随机部分的字节数（256 位），Base64URL 编码后约 43 字符 */
    private static final int TOKEN_BYTES = 32;

    private static final String NOT_VALID_MESSAGE = "登录状态已过期，请重新登录";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 签发一条新刷新令牌。
     *
     * @param userId     归属用户
     * @param expireTime 过期时间
     * @return 令牌原文（只此一次机会拿到，服务端只存摘要）
     */
    public String issue(Long userId, LocalDateTime expireTime) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        // Base64URL 而不是 Base64：令牌会出现在请求体里，+ / 这类字符不值得再转义
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RefreshToken entity = RefreshToken.builder()
                .userId(userId)
                .tokenHash(sha256(token))
                .expireTime(expireTime)
                .build();
        save(entity);
        return token;
    }

    /**
     * 旋转：旧令牌失效、签发新令牌，返回归属用户ID。
     *
     * <p>一次调用内完成"校验 → 失效旧值 → 签发新值"。失效用条件更新完成，
     * 并发复用同一旧令牌时只有一次能成功 —— 第二次会拿到 0 行受影响而失败，
     * 这是"刷新令牌不得复用"的落地方式。归属查询放在失效<b>之前</b>：
     * 逻辑删除后行会被 {@code @TableLogic} 过滤，反着写永远查不到。
     *
     * @param token           旧刷新令牌原文
     * @param newExpireTime   新令牌的过期时间
     * @return 归属用户ID（供上层重新签发访问令牌）
     * @throws BizException 令牌无效、已旋转、已登出或已过期时抛出
     */
    public Long rotate(String token, LocalDateTime newExpireTime) {
        if (!StringUtils.hasText(token)) {
            throw new BizException(ResultCode.UNAUTHORIZED, NOT_VALID_MESSAGE);
        }
        String hash = sha256(token);

        // 归属查询只按摘要找未删除的行；"是否过期"交给下面的条件更新统一判定
        Long userId = lambdaQuery()
                .eq(RefreshToken::getTokenHash, hash)
                .oneOpt()
                .map(RefreshToken::getUserId)
                .orElse(null);

        // 条件失效：并发复用同一旧令牌时只有一次能成功（受影响行数为 1）
        boolean revoked = revokeIfActive(hash);
        if (!revoked) {
            // 无效/已旋转/已登出/已过期在此同归一路：对客户端统一说"重新登录"，
            // 不区分具体原因（区分只会帮攻击者判断令牌状态）
            log.info("刷新令牌旋转被拒（无效/已复用/已过期）");
            throw new BizException(ResultCode.UNAUTHORIZED, NOT_VALID_MESSAGE);
        }

        if (userId == null) {
            // 条件更新成功了却查不到归属：数据被外部动过，按无效处理
            throw new BizException(ResultCode.UNAUTHORIZED, NOT_VALID_MESSAGE);
        }

        issue(userId, newExpireTime);
        return userId;
    }

    /**
     * 吊销令牌（登出）。幂等：令牌本就无效时同样返回成功。
     *
     * @param token 刷新令牌原文，允许为空（没有可吊销的东西就当登出完成）
     * @return true 表示本次调用真正把一个有效令牌置为失效
     */
    public boolean revoke(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return revokeIfActive(sha256(token));
    }

    /**
     * 条件失效：只有"未删除且未过期"的行才会被置 deleted=1。
     *
     * @param hash 令牌摘要
     * @return true 表示确实失效了一个有效令牌
     */
    private boolean revokeIfActive(String hash) {
        LambdaUpdateWrapper<RefreshToken> wrapper = Wrappers.<RefreshToken>lambdaUpdate()
                .eq(RefreshToken::getTokenHash, hash)
                .eq(RefreshToken::getDeleted, 0)
                .gt(RefreshToken::getExpireTime, LocalDateTime.now())
                .set(RefreshToken::getDeleted, 1);
        return baseMapper.update(null, wrapper) > 0;
    }

    /**
     * 计算令牌的 SHA-256 十六进制摘要。
     *
     * @param token 令牌原文
     * @return 64 字符的十六进制摘要
     */
    private static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，这里只是编译器要求的受检异常
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
