package com.mdm.platform.service.notice;

import java.time.OffsetDateTime;

/**
 * 单条安装证据锚点：一张凭证对“某实物在某设备某位置、在 [claimFrom, claimTo) 内安装”的断言。
 *
 * certainty 区分：
 *  - NECESSARY：精确装拆签字/出厂基线支撑的确定安装（区间端点是已证实时点）
 *  - POSSIBLE ：盘点区间等只给出边界、内部装拆时点未知的可能安装
 *
 * observed 用于相反盘点（ABSENT 断言“该区间内不在装”）。
 */
public record EvidenceAnchor(
        String nodeCode,
        String nodeType,           // INSTALL_SIGN / REMOVE_SIGN / INVENTORY_COUNT / FACTORY_RECORD（保留原始凭证类型）
        long equipmentId,
        Long mountPositionId,
        long physicalPartId,
        Long partModelId,
        OffsetDateTime claimFrom,
        OffsetDateTime claimTo,
        String certainty,          // NECESSARY / POSSIBLE
        String observed,           // PRESENT / ABSENT
        String sourceTable,
        long sourceId,
        String sourceRef,
        String payloadDigest,
        Double trustScore,
        OffsetDateTime receivedAt,
        String note,
        boolean assemblyDerived    // 是否由嵌套总成层级派生（内部件随父总成，非独立凭证）
) {
    /** 直接凭证（非总成派生）的便捷构造器。 */
    public EvidenceAnchor(String nodeCode, String nodeType, long equipmentId, Long mountPositionId,
                          long physicalPartId, Long partModelId, OffsetDateTime claimFrom, OffsetDateTime claimTo,
                          String certainty, String observed, String sourceTable, long sourceId,
                          String sourceRef, String payloadDigest, Double trustScore,
                          OffsetDateTime receivedAt, String note) {
        this(nodeCode, nodeType, equipmentId, mountPositionId, physicalPartId, partModelId,
                claimFrom, claimTo, certainty, observed, sourceTable, sourceId, sourceRef,
                payloadDigest, trustScore, receivedAt, note, false);
    }

    public TimeInterval interval() {
        return new TimeInterval(claimFrom, claimTo);
    }

    public boolean isAbsentClaim() {
        return "ABSENT".equals(observed);
    }
}
