package com.mdm.platform.service.notice;

/**
 * 维修通知资格四档 + 待核。
 * CERTAIN       必然安装：必要安装区间与目标窗相交，无相反证据
 * POSSIBLE      可能安装：仅有可能区间与目标窗相交，必要区间无法覆盖，稀疏凭证留下较宽可能集
 * INSUFFICIENT  证据不足：无任何与目标窗相关的在装/缺席证据
 * EXCLUDED      可排除：相反证据（独立缺席盘点/已证实拆除）覆盖目标窗，且无在装主张
 * PENDING_REVIEW 待核：在装与缺席证据相互矛盾，平台不做资格裁定
 */
public enum Qualification {
    CERTAIN,
    POSSIBLE,
    INSUFFICIENT,
    EXCLUDED,
    PENDING_REVIEW;

    public String label() {
        return switch (this) {
            case CERTAIN -> "必然安装";
            case POSSIBLE -> "可能安装";
            case INSUFFICIENT -> "证据不足";
            case EXCLUDED -> "可排除";
            case PENDING_REVIEW -> "矛盾待核";
        };
    }
}
