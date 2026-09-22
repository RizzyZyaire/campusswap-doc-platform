package com.campusswap.common.api;

import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TimeUtil;
import com.campusswap.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 实体型 VO 的公共审计字段基类（API_SPECIFICATION §2.7.1 铁律 1）。
 *
 * <p>{@code UserVo}、{@code UserInfoVo}、{@code RoleVo}、{@code PermissionVo}、{@code DeptVo}、
 * {@code DocumentVo}、{@code CategoryVo}、{@code TagVo}、{@code DocumentVersionVo} 一律继承本类。</p>
 *
 * <p>两条铁律：</p>
 * <ol>
 *   <li>时间字段是 <b>String</b>（{@code yyyy-MM-dd HH:mm:ss}），ID 字段是 <b>String</b>；</li>
 *   <li>本类<b>没有</b> {@code deleted} 字段 —— 软删除是持久层细节，接口层永不暴露。</li>
 * </ol>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class AuditVo {

    /** 创建时间，{@code yyyy-MM-dd HH:mm:ss}。 */
    private String createdAt;

    /** 创建人用户 ID（字符串）。 */
    private String createdBy;

    /** 最后修改时间，{@code yyyy-MM-dd HH:mm:ss}。 */
    private String updatedAt;

    /** 最后修改人用户 ID（字符串）。 */
    private String updatedBy;

    /**
     * 从实体填充四个审计字段（各 VO 的 {@code of(...)} 工厂方法调用）。
     *
     * @param entity 任意继承 {@link BaseEntity} 的实体
     */
    public void fillAudit(BaseEntity entity) {
        if (entity == null) {
            return;
        }
        this.createdAt = TimeUtil.format(entity.getCreatedAt());
        this.createdBy = IdUtil.toStr(entity.getCreatedBy());
        this.updatedAt = TimeUtil.format(entity.getUpdatedAt());
        this.updatedBy = IdUtil.toStr(entity.getUpdatedBy());
    }
}
