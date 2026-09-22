package com.campusswap.common.exception;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.ResponseResult;
import jakarta.validation.ConstraintViolationException;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 全局异常处理器：把各类异常统一翻译成 {@link ResponseResult}，前端只认一套结构。
 *
 * <p>铁律：响应体绝不暴露堆栈、SQL 与内部类名；500 只在日志里打完整堆栈。
 * 对应 docs/02-design/ARCHITECTURE.md §9.1。</p>
 *
 * @author Zyaire
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（Service 层主动抛出）。
     *
     * @param ex 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseResult<Void>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        log.warn("业务异常 code={} message={}", code.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.valueOf(code.getCode()))
                .body(ResponseResult.fail(code, ex.getMessage()));
    }

    /**
     * {@code @Valid} 校验失败：取第一条字段提示（注解里写的就是中文，可直接回显）。
     *
     * @param ex 参数校验异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseResult<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = null;
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // @Valid @ModelAttribute 的类型转换失败也走这里（Spring 6.1+ 统一抛本异常）：
            // 默认 message 是 Spring 英文原文且带内部类名，必须自己翻译。
            if (error.isBindingFailure()) {
                message = describeFieldType(error, ex.getBindingResult().getTarget());
                break;
            }
        }
        if (message == null) {
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage())
                    .findFirst()
                    .orElse(ErrorCode.BAD_REQUEST.getMessage());
        }
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * Query 参数对象（{@code @ModelAttribute}）绑定或校验失败。
     *
     * <p>{@code MethodArgumentNotValidException} 在 Spring 6+ 是 {@code BindException} 的子类，
     * 因此这里处理的是"非 @RequestBody 的绑定失败"（如 {@code pageSize=abc}）。</p>
     *
     * @param ex 绑定异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResponseResult<Void>> handleBind(BindException ex) {
        String message = null;
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // 类型转换失败（如 status=WRONG）走这条：默认 message 是 Spring 的英文原文，
            // 里面还带内部类名（com.campusswap.entity.enums.UserStatus），绝不能直接回显。
            if (error.isBindingFailure()) {
                message = describeFieldType(error, ex.getBindingResult().getTarget());
                break;
            }
        }
        if (message == null) {
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getDefaultMessage() == null
                            ? error.getField() + "参数不合法" : error.getDefaultMessage())
                    .findFirst()
                    .orElse(ErrorCode.BAD_REQUEST.getMessage());
        }
        log.warn("查询参数绑定失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * Controller 方法参数上的约束校验失败（Spring 7 的 {@code HandlerMethodValidationException}）。
     *
     * <p>Spring 7 把「全部校验结果」拆成了方法参数结果与跨参数结果两个入口，这里都取。</p>
     *
     * @param ex 方法参数校验异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ResponseResult<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String message = java.util.stream.Stream.concat(
                        ex.getParameterValidationResults().stream()
                                .flatMap(result -> result.getResolvableErrors().stream()),
                        ex.getCrossParameterValidationResults().stream())                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        log.warn("方法参数校验失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 方法参数（@RequestParam / @PathVariable）校验失败。
     *
     * @param ex 约束校验异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseResult<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getMessage() == null ? "参数不合法" : violation.getMessage())
                .findFirst()
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        log.warn("参数约束失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 路径/查询参数类型不匹配（例如把 "abc" 传给 Long 型的 id）。
     *
     * <p>枚举参数给出可选值清单，避免前端拿到"参数格式不正确：status"这种没有下一步信息的提示。</p>
     *
     * @param ex 类型不匹配异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ResponseResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "参数格式不正确：" + ex.getName();
        Class<?> requiredType = ex.getRequiredType();
        if (requiredType != null && requiredType.isEnum()) {
            message = "参数取值非法：" + ex.getName() + "（可选值 " + enumValues(requiredType) + "）";
        }
        log.warn(message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 把 Query 参数对象的绑定失败翻译成中文提示（枚举列出可选值，其余只说字段格式不对）。
     *
     * @param error  字段错误
     * @param target 绑定目标对象（DTO 实例）
     * @return 中文提示
     */
    private String describeFieldType(FieldError error, Object target) {
        Class<?> fieldType = resolveFieldType(target, error.getField());
        if (fieldType != null && fieldType.isEnum()) {
            return "参数取值非法：" + error.getField() + "（可选值 " + enumValues(fieldType) + "）";
        }
        return "参数格式不正确：" + error.getField();
    }

    /**
     * 反射取 DTO 字段类型（含父类字段，如 {@code UserPageDtoReq extends PageDtoReq}）。
     *
     * @param target 目标对象
     * @param field  字段名（支持 {@code a.b} 形式时取最后一段）
     * @return 字段类型；取不到时返回 null
     */
    private Class<?> resolveFieldType(Object target, String field) {
        if (target == null || field == null || field.isEmpty()) {
            return null;
        }
        String name = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                return type.getDeclaredField(name).getType();
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 枚举可选值清单（用 " / " 连接）。
     *
     * @param enumType 枚举类型
     * @return 可选值
     */
    private String enumValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(String::valueOf).collect(Collectors.joining(" / "));
    }

    /**
     * 在异常因果链里查找指定类型的异常（Jackson 的异常常被 Spring 包一层）。
     *
     * @param throwable 原始异常
     * @param type      目标异常类型
     * @param <T>       目标类型
     * @return 命中的异常；未命中返回 null
     */
    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 取 JSON 路径的最后一段字段名。
     *
     * @param ex Jackson 类型不匹配异常
     * @return 字段名；取不到时返回空串
     */
    private String lastFieldName(MismatchedInputException ex) {
        if (ex.getPath() == null || ex.getPath().isEmpty()) {
            return "";
        }
        // Jackson 3 把 Jackson 2 的 getFieldName() 改名为 getPropertyName()
        String fieldName = ex.getPath().get(ex.getPath().size() - 1).getPropertyName();
        return fieldName == null ? "" : fieldName;
    }

    /**
     * 请求体 JSON 解析失败。
     *
     * <p>枚举/数字字段取值非法时，Jackson 会抛 {@code InvalidFormatException}，Spring 包成
     * {@code HttpMessageNotReadableException}。这里把它翻译成带可选值的中文提示，
     * <b>不</b>回显 Jackson/Spring 的英文原文（那会泄露内部类名）。</p>
     *
     * @param ex 报文不可读异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseResult<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        UnrecognizedPropertyException unrecognized = findCause(ex, UnrecognizedPropertyException.class);
        if (unrecognized != null) {
            String property = unrecognized.getPropertyName();
            // API_SPECIFICATION §4.2.4：编辑用户接口不接受 username / password，要明确拒绝
            String message = ("username".equals(property) || "password".equals(property))
                    ? "登录名与密码不可通过本接口修改"
                    : "请求体出现不支持的字段：" + property;
            log.warn("请求体出现未知字段: {}", property);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
        }

        MismatchedInputException mismatch = findCause(ex, MismatchedInputException.class);
        String message = "请求体格式不正确";
        if (mismatch != null) {
            String field = lastFieldName(mismatch);
            Class<?> targetType = mismatch.getTargetType();
            if (targetType != null && targetType.isEnum()) {
                message = "参数取值非法：" + field + "（可选值 " + enumValues(targetType) + "）";
            } else if (!field.isEmpty()) {
                message = "参数格式不正确：" + field;
            }
        }
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, message));
    }

    /**
     * 上传文件超过限制（BR-15：单文件 ≤ 5MB）。
     *
     * @param ex 上传超限异常
     * @return 400 + 中文提示
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseResult<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过大小限制");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseResult.fail(ErrorCode.BAD_REQUEST, "图片大小不能超过 5MB"));
    }

    /**
     * 静态资源/未知路径 404。
     *
     * @param ex 资源未找到异常
     * @return 404 + 中文提示
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResponseResult<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResponseResult.fail(ErrorCode.NOT_FOUND));
    }

    /**
     * 数据库完整性约束冲突（唯一索引、非空等）。
     *
     * <p>Service 层一般已做前置校验（存在性 + 唯一性），这里兜住<b>并发窗口</b>：
     * 两个请求同时通过前置校验时，后提交的那个会撞唯一索引 —— 对外应是 409，而不是 500。</p>
     *
     * @param ex 数据完整性异常
     * @return 409 + 中文提示
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ResponseResult<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据完整性冲突: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ResponseResult.fail(ErrorCode.CONFLICT_STATUS, "数据已存在或状态冲突，请刷新后重试"));
    }

    /**
     * 兜底：未预期异常。对外只给通用提示，完整堆栈只进日志。
     *
     * @param ex 任意异常
     * @return 500 + 通用中文提示
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseResult<Void>> handleUnexpected(Exception ex) {
        log.error("未预期的服务器异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseResult.fail(ErrorCode.SERVER_ERROR));
    }
}
