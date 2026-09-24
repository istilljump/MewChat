/**
 * 数据访问层。
 *
 * <p>职责：只做数据存取，不含业务判断。
 *
 * <p>规划的子包：
 * <ul>
 *     <li>{@code mysql} —— MyBatis-Plus Mapper 与数据库实体（{@code entity}）</li>
 *     <li>{@code milvus} —— Milvus 向量库访问封装</li>
 * </ul>
 *
 * <p>约定：单表 CRUD 用 MyBatis-Plus；手写 SQL 一律放
 * {@code src/main/resources/mapper/*.xml}，不写在注解里。
 */
package com.mewchat.dao;
