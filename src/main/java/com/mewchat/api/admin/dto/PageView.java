package com.mewchat.api.admin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。
 *
 * <p><b>为什么不让接口直接返回 MyBatis-Plus 的 {@code Page}</b>：它带着
 * {@code orders}、{@code optimizeCountSql}、{@code searchCount} 等一堆内部字段，
 * 直接序列化会把实现细节暴露给前端，且这些字段随版本变化 ——
 * 前端一旦"顺手用上了"，升级 MyBatis-Plus 就会把后台打挂。
 * 这里只暴露翻页真正需要的四个值。
 *
 * @param page    当前页码，从 1 开始
 * @param size    每页条数
 * @param total   总条数
 * @param records 当前页数据
 * @param <T>     记录类型
 * @author MewChat
 */
public record PageView<T>(int page, int size, long total, List<T> records) {

    /**
     * 由 MyBatis-Plus 的分页结果构造，并顺带完成"实体 → 对外视图"的映射。
     *
     * <p>把映射收进工厂方法，是为了让每个接口都写成一行：
     * 逐个字段搬运散落在各个 Controller 里，改一个字段就要记得改三处。
     *
     * @param source 分页结果
     * @param mapper 单条记录的映射函数
     * @param <S>    源记录类型
     * @param <T>    对外视图类型
     * @return 分页视图
     */
    public static <S, T> PageView<T> of(Page<S> source, Function<S, T> mapper) {
        List<T> records = source.getRecords() == null
                ? List.of()
                : source.getRecords().stream().map(mapper).toList();
        return new PageView<>((int) source.getCurrent(), (int) source.getSize(), source.getTotal(), records);
    }
}
