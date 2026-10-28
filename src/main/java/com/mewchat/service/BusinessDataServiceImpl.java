package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mewchat.dao.mysql.entity.Logistics;
import com.mewchat.dao.mysql.entity.Order;
import com.mewchat.dao.mysql.entity.Product;
import com.mewchat.dao.mysql.entity.RefundPolicy;
import com.mewchat.dao.mysql.mapper.LogisticsMapper;
import com.mewchat.dao.mysql.mapper.OrderMapper;
import com.mewchat.dao.mysql.mapper.ProductMapper;
import com.mewchat.dao.mysql.mapper.RefundPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 业务数据查询服务实现。
 *
 * <p>四个 Mapper 注入在同一个实现里，而不是四个小服务各持一个：
 * 见 {@link BusinessDataService} 类注释 —— 这四类查询的服务对象就是同一条
 * "工具取数"路径，拆开只会多三个文件、不改变任何依赖方向。
 *
 * <p>全部是单表读、无事务（只读单条不需要事务边界）；逻辑删除由
 * MyBatis-Plus 的 {@code @TableLogic} 自动过滤，这里不写 deleted 条件。
 *
 * @author MewChat
 */
@Service
public class BusinessDataServiceImpl implements BusinessDataService {

    private final OrderMapper orderMapper;
    private final LogisticsMapper logisticsMapper;
    private final ProductMapper productMapper;
    private final RefundPolicyMapper refundPolicyMapper;

    public BusinessDataServiceImpl(OrderMapper orderMapper,
                                   LogisticsMapper logisticsMapper,
                                   ProductMapper productMapper,
                                   RefundPolicyMapper refundPolicyMapper) {
        this.orderMapper = orderMapper;
        this.logisticsMapper = logisticsMapper;
        this.productMapper = productMapper;
        this.refundPolicyMapper = refundPolicyMapper;
    }

    @Override
    public Order findOrderByNo(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            return null;
        }
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOrderNo, orderNo.trim()));
    }

    @Override
    public List<Order> listOrdersByOwner(Long ownerUserId) {
        if (ownerUserId == null) {
            return List.of();
        }
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getOwnerUserId, ownerUserId)
                // 按主键升序（=种子录入顺序）：候选列表的顺序是对外可见的行为，
                // 澄清话术里的"第 1 项"必须可预期，评测与演示都依赖它稳定。
                // 想改成"最近下单排前面"要连同评测与演示脚本一起调整，不能只改这里
                .orderByAsc(Order::getId));
    }

    @Override
    public Logistics findLogisticsByTrackingNo(String trackingNo) {
        if (!StringUtils.hasText(trackingNo)) {
            return null;
        }
        return logisticsMapper.selectOne(Wrappers.<Logistics>lambdaQuery()
                .eq(Logistics::getTrackingNo, trackingNo.trim()));
    }

    @Override
    public Product findProductByName(String productName) {
        if (!StringUtils.hasText(productName)) {
            return null;
        }
        return productMapper.selectOne(Wrappers.<Product>lambdaQuery()
                .eq(Product::getProductName, productName.trim()));
    }

    @Override
    public List<Product> listAllProducts() {
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .orderByAsc(Product::getId));
    }

    @Override
    public List<RefundPolicy> listAllRefundPolicies() {
        return refundPolicyMapper.selectList(Wrappers.<RefundPolicy>lambdaQuery()
                .orderByAsc(RefundPolicy::getId));
    }
}
