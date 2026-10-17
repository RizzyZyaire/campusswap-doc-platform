package com.campusswap.common.log;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解（M14）：标在控制器方法上，补充"人读"的模块名与动作名。
 *
 * <p>不标也能被记录 —— {@link OperationLogAspect} 会兜住所有写接口，并先按路径关键词推断模块与动作
 * （例如 {@code /publish} → 「文档 · 发布」）；标注用于覆盖推断结果或补充更精确的中文动作名。</p>
 *
 * @author Zyaire
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 模块名（中文，如"文档""角色"）。 */
    String module();

    /** 动作名（中文，如"发布""审核通过"）。 */
    String action();

    /**
     * 是否记录入参摘要。
     *
     * <p>默认 true；涉及大文本（Markdown 正文、文件上传）的接口可关掉，避免日志表被正文塞满。</p>
     *
     * @return true = 记录
     */
    boolean params() default true;
}
