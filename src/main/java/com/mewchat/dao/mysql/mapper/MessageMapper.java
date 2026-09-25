package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.Message;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息表 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；需手写 SQL 时放
 * {@code src/main/resources/mapper/MessageMapper.xml}。
 *
 * <p>本表是全库写入量最大的表，后续若出现慢查询（如按时间范围统计 token 消耗），
 * 建议在 XML 中用显式列名而非 {@code SELECT *}。
 *
 * @author MewChat
 */
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 批量取多个会话的"首个用户提问"，用于给没有标题的历史会话兜底显示。
     *
     * <p>为什么值得一条专门 SQL：会话列表里那些在本功能之前建的会话没有标题
     * （当时还没有"首句即标题"），侧边栏只能显示"（无标题）"。
     * 逐个会话查一次会变成 N+1；一条按 {@code session_id IN (...)} 分组取最小ID的查询即可。
     *
     * <p>返回的 {@link Message} 只填了 {@code sessionId} 与 {@code content} 两列 ——
     * 这个查询只服务于"显示预览"，不需要整行。
     *
     * @param sessionIds 会话业务ID集合，不能为空
     * @param role       取哪个角色的消息（用常量传入，避免 SQL 里写死字符串）
     * @return 每个会话一条记录，未命中该角色的会话不会出现在结果里
     */
    List<Message> firstMessagesBySessionIds(@Param("sessionIds") List<String> sessionIds,
                                            @Param("role") String role);
}
