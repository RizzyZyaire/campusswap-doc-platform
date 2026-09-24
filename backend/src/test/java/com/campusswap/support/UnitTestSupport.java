package com.campusswap.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.RequiresPermission;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.config.JpaAuditConfig;
import com.campusswap.entity.BaseEntity;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Service 层纯单元测试（M6 的 24 条 BDD 断言）共用的小工具。
 *
 * <p>本类<b>不是</b>测试类（没有 {@code @Test}，类名也不匹配 surefire 的默认 includes），
 * 只服务于 {@code src/test/java/com/campusswap/{document,system}/Ac*Test.java}。</p>
 *
 * <p>四个能力：</p>
 * <ol>
 *   <li>{@link #businessFailure(ThrowingCallable)}：断言"这条规则必须由 {@link BusinessException} 承载"
 *       并把它取出来，避免每个测试各写一遍 {@code assertThatThrownBy(...)}；</li>
 *   <li>{@link #emulateInsert}/{@link #emulateUpdate}：<b>模拟</b> Spring Data JPA 的
 *       {@code AuditingEntityListener}（实体上的 {@code @CreatedBy/@LastModifiedBy/@CreatedDate}）。
 *       纯单元测试没有 Hibernate、{@code saveAndFlush} 是 mock，审计列不会自动落值；
 *       这里用生产代码 {@link JpaAuditConfig#auditorAware()} 真实解析出的操作人来补，
 *       保证 "createdBy = 当前登录用户" 这类断言对的是生产口径，而不是测试自造的口径；</li>
 *   <li>{@link #validator()} + {@link #asArgumentNotValid(Class, String, Class, Object, Set)}：
 *       把 Controller 参数校验层的违规翻译成 {@code MethodArgumentNotValidException}，
 *       再交给生产的 {@code GlobalExceptionHandler}，断言"400 + 中文提示"这条链路；</li>
 *   <li>{@link #requiresPermissionOn(Class, String, Class[])}：取 Controller 方法上的真实权限注解。</li>
 * </ol>
 *
 * @author Zyaire
 */
public final class UnitTestSupport {

    /** Jakarta Validation 的默认 Validator（Hibernate Validator，由 spring-boot-starter-validation 提供）。 */
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private UnitTestSupport() {
    }

    /**
     * 断言调用抛 {@link BusinessException} 并把它取出来（用于继续断言错误码与中文提示）。
     *
     * @param call 待执行的调用
     * @return 抛出的业务异常
     */
    public static BusinessException businessFailure(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
                .as("该规则必须由 BusinessException 承载（由 GlobalExceptionHandler 翻成对应 HTTP 状态码），实际抛出 %s", thrown)
                .isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    /**
     * 取当前审计操作人 —— 直接调用生产代码 {@link JpaAuditConfig#auditorAware()}。
     *
     * @return 当前登录用户 ID；未登录时为 0（系统）
     */
    public static Long currentAuditor() {
        return new JpaAuditConfig().auditorAware().getCurrentAuditor().orElse(0L);
    }

    /**
     * 模拟插入时的审计填充（{@code @CreatedDate/@CreatedBy} + {@code @LastModified*}）。
     *
     * @param entity 被保存的实体
     * @param id     自增主键（mock 环境需手工给）
     * @param <T>    实体类型
     * @return 同一个实体
     */
    public static <T extends BaseEntity> T emulateInsert(T entity, Long id) {
        LocalDateTime now = LocalDateTime.now();
        entity.setId(id);
        entity.setCreatedAt(now);
        entity.setCreatedBy(currentAuditor());
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(currentAuditor());
        if (entity.getDeleted() == null) {
            entity.setDeleted(0);
        }
        return entity;
    }

    /**
     * 模拟更新时的审计填充（{@code @LastModifiedDate/@LastModifiedBy}）。
     *
     * @param entity 被保存的实体
     * @param <T>    实体类型
     * @return 同一个实体
     */
    public static <T extends BaseEntity> T emulateUpdate(T entity) {
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(currentAuditor());
        return entity;
    }

    /**
     * 取 Jakarta Validation 的 Validator。
     *
     * @return Validator
     */
    public static Validator validator() {
        return VALIDATOR;
    }

    /**
     * 把「DTO 校验违规」组装成 Spring MVC 真实会抛的 {@link MethodArgumentNotValidException}。
     *
     * <p>各字段错误都按"非绑定失败 + {@code defaultMessage} = 注解里的中文提示"构造，
     * 与 {@code @Valid @RequestBody} 校验失败时的行为一致，因此可以直接喂给
     * {@code GlobalExceptionHandler#handleMethodArgumentNotValid} 断言 400 与提示文案。</p>
     *
     * @param method         Controller 端点方法（{@code getMethod(...)} 取得）
     * @param parameterIndex 请求体参数在方法形参里的下标
     * @param target         被校验的请求体对象
     * @param violations     校验违规集合
     * @param <T>            请求体类型
     * @return 统一的参数校验异常
     */
    public static <T> MethodArgumentNotValidException asArgumentNotValid(Method method, int parameterIndex,
                                                                        T target,
                                                                        Set<ConstraintViolation<T>> violations) {
        Class<?> parameterType = method.getParameterTypes()[parameterIndex];
        String objectName = Character.toLowerCase(parameterType.getSimpleName().charAt(0))
                + parameterType.getSimpleName().substring(1);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, objectName);
        for (ConstraintViolation<T> violation : violations) {
            binding.addError(new FieldError(objectName, violation.getPropertyPath().toString(),
                    violation.getInvalidValue(), false, null, null, violation.getMessage()));
        }
        return new MethodArgumentNotValidException(new MethodParameter(method, parameterIndex), binding);
    }

    /**
     * 取类（含全部父类）声明的字段名集合 —— 用于断言 VO 里"不存在某个字段"这类结构约束。
     *
     * @param types 待扫描的类
     * @return 字段名集合
     */
    public static Set<String> fieldNames(Class<?>... types) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type : types) {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }

    /**
     * 取 Controller 方法上的 {@code @RequiresPermission} 注解本体。
     *
     * <p>权限切面 {@code @Before("@annotation(requiresPermission)")} 拿到的就是这个对象，
     * 因此切面层测试必须用生产代码里的真实注解，而不是测试里 new 一个假的。</p>
     *
     * @param controller     控制器类
     * @param methodName     方法名
     * @param parameterTypes 方法形参类型
     * @return 权限注解
     */
    public static RequiresPermission requiresPermissionOn(Class<?> controller, String methodName,
                                                          Class<?>... parameterTypes) {
        Method method;
        try {
            method = controller.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("找不到被测端点方法：" + controller.getName() + "#" + methodName, ex);
        }
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertThat(annotation)
                .as("端点 %s#%s 必须标注 @RequiresPermission，否则鉴权关卡③形同虚设",
                        controller.getSimpleName(), methodName)
                .isNotNull();
        return annotation;
    }

    /**
     * 清理 ThreadLocal 登录上下文（每个测试的出口都调一次，防止线程复用造成的串味）。
     */
    public static void clearSecurityContext() {
        SecurityContext.clear();
    }
}
