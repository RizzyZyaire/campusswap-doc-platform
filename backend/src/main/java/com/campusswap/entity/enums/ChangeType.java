package com.campusswap.entity.enums;

/**
 * 文档版本留痕的变更类型（对应 doc_version.change_type，VARCHAR(32)）。
 *
 * <p>每次正文写入或状态流转都会往 doc_version 追加快照，change_type 说明“这次是因为什么变的”。</p>
 *
 * @author Zyaire
 */
public enum ChangeType {

    /** 新建草稿。 */
    CREATE("新建"),

    /** 编辑正文。 */
    EDIT("编辑"),

    /** 提交发布。 */
    PUBLISH("发布"),

    /** 审核通过（归档等治理动作）。 */
    AUDIT("审核通过"),

    /** 驳回。 */
    REJECT("驳回"),

    /** 归档。 */
    ARCHIVE("归档"),

    /** 恢复（回收站恢复 / 归档恢复上架）。 */
    RESTORE("恢复"),

    /** 删除进回收站。 */
    DELETE("删除"),

    /** 派生新建（血缘：derived_from_id）。 */
    DERIVE("派生");

    private final String label;

    ChangeType(String label) {
        this.label = label;
    }

    /**
     * 获取中文名称。
     *
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }
}
