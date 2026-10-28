package com.mewchat.service;

import com.mewchat.dao.mysql.entity.Logistics;
import com.mewchat.dao.mysql.entity.Order;
import com.mewchat.dao.mysql.entity.Product;
import com.mewchat.dao.mysql.entity.RefundPolicy;

import java.util.List;

/**
 * 业务数据查询服务：四个客服工具（订单 / 物流 / 退款政策 / 商品）的统一取数入口。
 *
 * <p><b>为什么四个工具共用一个服务</b>：AGENTS.md 分层约定是"tool 通过 service 取数"，
 * 四个工具的查询都是单表读，各自开一个服务只会得到四个只有一两个方法的接口；
 * 而"物流由订单号换算运单号"这类跨工具依赖（阶段 6 起）也要求订单查询能被
 * 物流工具复用 —— 收敛在一处，映射关系才有唯一的权威来源。
 * 工具之间原本的依赖（{@code LogisticsTool → OrderTool}）也随本服务出现而理顺：
 * 工具依赖服务，而不是工具依赖工具。
 *
 * <p><b>只有查询、没有写入</b>：工具当前只回答"是什么"，不改变任何业务状态
 * （下单、退款申请这类动作属于交易系统的职责，客服系统不应越过边界直接写）。
 * 接口因此刻意不继承 {@code IService} —— 那会把整套路由到写入的方法暴露给工具层。
 *
 * <p><b>数据与演示值</b>：表结构与种子数据见 {@code sql/08_business_tables.sql} 与
 * {@code sql/09_business_seed.sql}，数据值与迁移前的进程内模拟数据逐字一致，
 * 评测集与演示脚本依赖这些具体值。真实接入时各查询换数据来源（交易系统 / 物流聚合服务），
 * 方法签名不变。
 *
 * @author MewChat
 */
public interface BusinessDataService {

    /**
     * 按订单号查订单。
     *
     * @param orderNo 订单号，允许为空或空白
     * @return 订单；订单号为空或查不到时返回 null
     */
    Order findOrderByNo(String orderNo);

    /**
     * 列出归属某用户的全部订单，供"缺订单号"时的澄清候选使用。
     *
     * <p><b>调用方必须传当前登录用户的ID</b>：候选订单按归属过滤是安全红线
     * （列出别人的订单是泄露交易信息）。本方法只负责按条件取数，
     * "拿不到归属就返回空列表、宁可少列不能多列"的决策在调用方（工具层）。
     *
     * @param ownerUserId 归属用户ID，为空时返回空列表
     * @return 该用户的订单，按下单时间倒序
     */
    List<Order> listOrdersByOwner(Long ownerUserId);

    /**
     * 按运单号查物流详情。
     *
     * @param trackingNo 运单号，允许为空或空白
     * @return 物流详情；运单号为空或查不到时返回 null
     */
    Logistics findLogisticsByTrackingNo(String trackingNo);

    /**
     * 按商品名精确查商品。
     *
     * @param productName 商品名，允许为空或空白
     * @return 商品；商品名为空或查不到时返回 null
     */
    Product findProductByName(String productName);

    /**
     * 列出全部商品，供工具侧做"关键词包含"匹配与在售过滤。
     *
     * <p>刻意返回全量而不是按关键词 LIKE：匹配规则（关键词长度降序、
     * 不放单字词等）是工具与大模型交互的一部分，放在工具层并有单测钉住；
     * service 只提供数据。演示规模（个位数商品）下全量返回没有性能问题，
     * 商品量大后应把匹配下推为 SQL 检索并给本方法加条件参数。
     *
     * @return 全部商品，按主键升序
     */
    List<Product> listAllProducts();

    /**
     * 列出全部退款政策，供工具侧做类目别名匹配与"通用"兜底。
     *
     * <p><b>每次调用现读、不做任何缓存</b>：政策变更必须即时生效 ——
     * 政策表被缓存住而运营改了规则，客服就会照旧规则答复用户，
     * 这是会产生客诉的错误。演示规模下每次一次全表读可接受。
     *
     * @return 全部政策，按主键升序
     */
    List<RefundPolicy> listAllRefundPolicies();
}
