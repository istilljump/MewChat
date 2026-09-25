package com.mewchat.common.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>把各类异常统一收敛成 {@link Result}，保证接口返回结构一致，
 * 同时让 Controller 层不必写 try-catch。
 *
 * <p>处理策略：
 * <ul>
 *     <li>业务异常 —— 可预期，返回 200 + 业务错误码，不打堆栈（避免日志噪音）</li>
 *     <li>参数校验异常 —— 汇总所有字段错误一次性返回，减少前端来回试错</li>
 *     <li>兜底异常 —— 返回 500，打完整堆栈，但**不把内部细节透给前端**</li>
 * </ul>
 *
 * @author MewChat
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常。
     *
     * @param e 业务异常
     * @return 统一失败响应，携带业务错误码
     */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理 {@code @RequestBody} + {@code @Valid} 触发的参数校验失败。
     *
     * @param e 参数校验异常
     * @return 统一失败响应，message 为 "字段: 原因" 的拼接串
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return Result.error(ResultCode.PARAM_INVALID, detail);
    }

    /**
     * 处理请求体无法解析（JSON 语法错、编码不是 UTF-8、字段类型对不上等）。
     *
     * <p><b>为什么不能落到兜底的 500</b>：这类失败的原因是<b>客户端发来的数据有问题</b>，
     * 不是服务端故障。落到 500 会有两个后果：调用方看到"系统繁忙"以为要重试，
     * 而真正该做的是检查请求体；服务端的错误率与告警也被这些本可避免的
     * 500 污染，真正的故障反而被淹掉。因此与参数校验失败同等对待：
     * HTTP 200 + {@code 10001}，按项目约定由业务码表达"这次请求本身不合法"。
     *
     * <p>最常见的实际成因是<b>编码不是 UTF-8</b>（例如 Windows 终端按 GBK 发出中文），
     * 因此提示里点明编码，而不是只回一句"格式错误"让调用方去猜。
     * 具体解析细节只写日志 —— 它含类名与位置信息，不适合直接返回给调用方。
     *
     * @param e 请求体解析异常
     * @return 统一失败响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());

        // 区分两种成因："JSON 本身有问题"与"JSON 没问题、只是某个字段的取值不合法"。
        // 后者的典型是枚举字段传了未知值（{"vote":"maybe"}）：统一回"请求体格式不正确"
        // 会让调用方去检查 JSON 与编码，方向完全错了。
        // 取值不合法的提示来自我们自己的 @JsonCreator（例如"反馈值只能是 up 或 down"），
        // 这里把它取出来回给调用方 —— 只取一行、截断长度，避免把任意内容原样回显。
        String fieldMessage = extractValueMessage(e);
        if (fieldMessage != null) {
            return Result.error(ResultCode.PARAM_INVALID, fieldMessage);
        }
        return Result.error(ResultCode.PARAM_INVALID, "请求体格式不正确，请确认是合法 JSON 且按 UTF-8 编码");
    }

    /**
     * 从解析异常的因果链里取出"字段取值不合法"的说明。
     *
     * <p>Jackson 会把 {@code @JsonCreator} 抛出的异常层层包装，因此这里沿因果链找
     * 第一个带消息的取值类异常；找不到（说明确实是 JSON 语法/编码问题）时返回 null，
     * 由调用方给出通用提示。
     *
     * @param e 请求体解析异常
     * @return 面向调用方的说明；不是"取值不合法"时返回 null
     */
    private String extractValueMessage(HttpMessageNotReadableException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            // 只认 JsonMappingException（字段映射/取值问题），不认 JsonParseException ——
            // 后者是 JSON 语法或编码问题，那条路径的提示由调用方给出
            if (cause instanceof JsonMappingException mapping) {
                String raw = mapping.getOriginalMessage();
                if (StringUtils.hasText(raw)) {
                    String oneLine = raw.replaceAll("\\s+", " ").trim();
                    return "参数取值不合法：" + (oneLine.length() > 120 ? oneLine.substring(0, 120) : oneLine);
                }
            }
        }
        return null;
    }

    /**
     * 处理表单绑定（非 {@code @RequestBody}）触发的参数校验失败。
     *
     * @param e 绑定异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBindException(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", detail);
        return Result.error(ResultCode.PARAM_INVALID, detail);
    }

    /**
     * 处理"请求参数本身有问题"：类型不匹配、缺少必需参数。
     *
     * <p>这几类都是<b>调用方的问题，不是服务端故障</b>：`?limit=abc`（类型错）、
     * 少传必需参数，若落到兜底处理器就会被报成 500"系统繁忙"，
     * 调用方会以为该重试，服务端的错误率与告警也被这些本可避免的 500 污染。
     * 这与阶段 11 修过的"请求体格式错被报成 500"是同一类问题，只是入口从请求体换成了查询参数。
     *
     * <p><b>刻意不回显参数的原值</b>：原值来自请求串、内容完全由调用方控制，
     * 回显进响应与日志既没必要（知道是哪个参数、期望什么类型就够了），
     * 又给了"把任意文本塞进日志"的机会。
     *
     * @param e 参数相关异常
     * @return 200 + 10001，并点明是哪个参数的问题
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRequestParameterError(Exception e) {
        String detail;
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            detail = "参数 " + mismatch.getName() + " 的取值类型不正确";
        } else {
            detail = "缺少必需的参数：" + ((MissingServletRequestParameterException) e).getParameterName();
        }
        log.warn("请求参数错误: {}（{}）", detail, e.getClass().getSimpleName());
        return Result.error(ResultCode.PARAM_INVALID, detail);
    }

    /**
     * 处理"请求方法不支持"，例如对着只接受 POST 的接口发 GET。
     *
     * <p>同样属于调用方问题：兜底成 500 会让"接口用错了方法"看起来像"服务挂了"。
     * 这里返回 405 语义（真正的 HTTP 状态码，与 Security 的 401/403 同一处理方式）。
     *
     * @param e 方法不支持异常
     * @return 405 + 统一失败响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}（支持：{}）", e.getMethod(), e.getSupportedHttpMethods());
        return Result.error(ResultCode.PARAM_INVALID,
                "请求方法 " + e.getMethod() + " 不被支持，该接口支持：" + e.getSupportedHttpMethods());
    }

    /**
     * 处理"路径不存在"。
     *
     * <p>Spring 6.1+ 对未匹配到处理器的请求会抛 {@link NoResourceFoundException}。
     * 必须单独处理：否则会被下方的兜底处理器捕获，把本该是 404 的"接口写错了"
     * 伪装成 500"系统繁忙"，排查问题时极具误导性。
     *
     * @param e 资源未找到异常
     * @return 404 统一失败响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        // getResourcePath() 返回的路径不带前导斜杠，补上以符合直觉便于排查
        String path = e.getResourcePath();
        String displayPath = path.startsWith("/") ? path : "/" + path;
        log.warn("请求路径不存在: {}", displayPath);
        return Result.error(ResultCode.NOT_FOUND, "请求路径不存在: " + displayPath);
    }

    /**
     * 处理"无匹配处理器"，语义同 {@link #handleNoResourceFound}。
     *
     * <p>当开启 {@code spring.mvc.throw-exception-if-no-handler-found} 时抛出此异常。
     * 两种异常都保留处理，避免因配置调整而漏掉 404 语义。
     *
     * @param e 无处理器异常
     * @return 404 统一失败响应
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("未找到请求处理器: {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(ResultCode.NOT_FOUND, "请求路径不存在: " + e.getRequestURL());
    }

    /**
     * 兜底异常处理。
     *
     * <p>这里返回统一的"系统繁忙"，不暴露异常堆栈或 SQL 片段，
     * 详细信息只进服务端日志，防止信息泄露。
     *
     * @param e 未预期的异常
     * @return 统一失败响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }

    /**
     * 把字段校验错误格式化成 "字段名: 错误原因"。
     *
     * @param fieldError 字段错误
     * @return 可读的错误描述
     */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
