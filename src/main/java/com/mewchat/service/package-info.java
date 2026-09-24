/**
 * 业务服务层。
 *
 * <p>职责：承载可复用的业务逻辑，是 {@code api} / {@code agent} / {@code tool} 与
 * {@code dao} 之间的中间层。
 *
 * <p>约定：一个业务实体对应一个 Service；单表操作直接继承 MyBatis-Plus 的
 * {@code IService}，跨表或复杂查询写在对应的 Mapper XML 中。
 */
package com.mewchat.service;
