package com.mewchat.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建知识文档的请求体。
 *
 * <p><b>为什么不直接复用 {@code rag.document.DocumentIngestRequest}</b>：
 * 那个值对象是 {@code @Builder} + final 字段、没有 setter，
 * Jackson 从请求体反序列化时会因为找不到构造方式而失败（除非额外标注）。
 * 与其为它加上一套只为"能反序列化"存在的注解，不如在接口层单独定义一个请求体 ——
 * 顺带还能挂上参数校验注解，而那个值对象属于入库流程内部，不该被接口注解污染。
 *
 * <p><b>只接受正文文本，不处理文件上传</b>：PDF/Word 的正文抽取需要额外的解析库
 * （这是本项目尚未引入的依赖），因此当前只支持"把文本粘贴进来"。
 * 字段里保留了文件名/类型的位置（见 {@code DocumentIngestRequest}），
 * 将来接文件上传时不必改接口形状。
 *
 * @param title    文档标题，检索结果里的"出处"就是它
 * @param content  文档正文
 * @param category 知识分类，可为空
 * @author MewChat
 */
public record CreateDocumentRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 500_000, message = "正文长度不能超过 50 万字符")
        String content,

        @Size(max = 64, message = "分类长度不能超过 64 个字符")
        String category) {
}
