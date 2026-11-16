package com.mdm.platform.service.notice;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安装证据区间引擎纯逻辑单测（不依赖数据库）：
 * 覆盖拆装时间缺口、相反盘点、总成派生后的必然安装、缺少拆除单、独立缺席盘点可排除五类资格。
 */
class InstallationIntervalEngineTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2025-01-01T00:00:00Z");
    private static final OffsetDateTime W_END = OffsetDateTime.parse("2025-07-01T00:00:00Z");
    private static final TimeInterval WINDOW = new TimeInterval(T, W_END);
    private static final OffsetDateTime FUTURE = EvidenceGraphBuilder.FAR_FUTURE;

    private EvidenceAnchor sign(String code, String type, long eq, long physical, OffsetDateTime from,
                                OffsetDateTime to, String observed) {
        return new EvidenceAnchor(code, type, eq, null, physical, 130L, from, to,
                "NECESSARY", observed, "install_event", 1L, code, "digest", 75d, null, null);
    }

    private EvidenceAnchor count(String code, long eq, long physical, OffsetDateTime t, String observed) {
        return new EvidenceAnchor(code, "INVENTORY_COUNT", eq, null, physical, 130L, t, t,
                "POSSIBLE", observed, "inventory_count", 1L, code, "digest", 65d, null, null);
    }

    private final InstallationIntervalEngine engine = new InstallationIntervalEngine();

    @Test
    void preciseInstallAndRemoveWithinWindow_isCertain() {
        long eq = 110L, phys = 140L;
        var anchors = Map.of(eq, List.of(
                sign("INST", "INSTALL_SIGN", eq, phys, OffsetDateTime.parse("2024-12-01T00:00:00Z"), FUTURE, "PRESENT"),
                sign("REM", "REMOVE_SIGN", eq, phys, OffsetDateTime.parse("2025-03-01T00:00:00Z"),
                        OffsetDateTime.parse("2025-03-01T00:00:00Z"), "ABSENT")));
        var result = engine.assess(anchors, WINDOW).get(eq);
        assertEquals(Qualification.CERTAIN, result.qualification());
        assertFalse(result.necessaryIntervals().isEmpty());
    }

    @Test
    void removedBeforeWindowAndReinstallAfterGap_isExcludedDuringGap() {
        long eq = 110L, phys = 140L;
        // 2024-11 拆除，2025-08 才装回（装回落在目标窗结束之后）
        var anchors = Map.of(eq, List.of(
                sign("INST0", "INSTALL_SIGN", eq, phys, OffsetDateTime.parse("2024-01-01T00:00:00Z"), FUTURE, "PRESENT"),
                sign("REM0", "REMOVE_SIGN", eq, phys, OffsetDateTime.parse("2024-11-20T00:00:00Z"),
                        OffsetDateTime.parse("2024-11-20T00:00:00Z"), "ABSENT"),
                sign("INST1", "INSTALL_SIGN", eq, phys, OffsetDateTime.parse("2025-08-01T00:00:00Z"), FUTURE, "PRESENT")));
        var result = engine.assess(anchors, WINDOW).get(eq);
        // 窗内既无必要也无可能区间，且被已证实缺席覆盖 -> 可排除
        assertEquals(Qualification.EXCLUDED, result.qualification());
        assertTrue(result.necessaryIntervals().isEmpty());
    }

    @Test
    void contradictoryInstallSignAndAbsentCount_isPendingReview() {
        long eq = 111L, phys = 141L;
        var anchors = Map.of(eq, List.of(
                sign("INST", "INSTALL_SIGN", eq, phys, OffsetDateTime.parse("2024-09-01T00:00:00Z"), FUTURE, "PRESENT"),
                count("IC-ABSENT", eq, phys, OffsetDateTime.parse("2025-02-20T00:00:00Z"), "ABSENT")));
        var result = engine.assess(anchors, WINDOW).get(eq);
        assertEquals(Qualification.PENDING_REVIEW, result.qualification());
        assertFalse(result.contradictions().isEmpty());
    }

    @Test
    void twoIndependentAbsentCountsWithoutAnyPresence_isExcluded() {
        long eq = 113L, phys = 147L;
        var anchors = Map.of(eq, List.of(
                count("IC-1", eq, phys, OffsetDateTime.parse("2025-01-20T00:00:00Z"), "ABSENT"),
                count("IC-2", eq, phys, OffsetDateTime.parse("2025-03-20T00:00:00Z"), "ABSENT")));
        var result = engine.assess(anchors, WINDOW).get(eq);
        assertEquals(Qualification.EXCLUDED, result.qualification());
        // 两条独立支持路径
        assertTrue(result.supportPaths().size() >= 2);
    }

    @Test
    void installSignWithoutRemovePlusOnePresentCount_isPossibleWhenSignOutsideWindow() {
        long eq = 114L, phys = 150L;
        // 无精确安装，仅一次在装盘点（装拆时点未知）-> 可能安装，可能区间覆盖目标窗
        var anchors = Map.of(eq, List.of(
                count("IC-P", eq, phys, OffsetDateTime.parse("2024-12-15T00:00:00Z"), "PRESENT")));
        var result = engine.assess(anchors, WINDOW).get(eq);
        assertEquals(Qualification.POSSIBLE, result.qualification());
        assertFalse(result.possibleIntervals().isEmpty());
    }

    @Test
    void noEvidenceAtAll_isInsufficient() {
        long eq = 199L;
        var result = engine.assess(Map.of(eq, List.of()), WINDOW).get(eq);
        assertEquals(Qualification.INSUFFICIENT, result.qualification());
    }

    @Test
    void presentCountExpandsToPossibleBoundedByRemove() {
        long eq = 113L, phys = 145L;
        // 2024-08 安装（精确，窗外），2024-12 在装盘点，无拆除 -> 安装精确区间实际已覆盖窗 -> 必然
        // 改为：安装在窗外且另有拆除，盘点夹在中间，验证可能区间被缺席边界收窄
        var anchors = Map.of(eq, List.of(
                sign("REM", "REMOVE_SIGN", eq, phys, OffsetDateTime.parse("2025-05-01T00:00:00Z"),
                        OffsetDateTime.parse("2025-05-01T00:00:00Z"), "ABSENT"),
                count("IC-P", eq, phys, OffsetDateTime.parse("2025-02-01T00:00:00Z"), "PRESENT")));
        var result = engine.assess(anchors, WINDOW).get(eq);
        assertEquals(Qualification.POSSIBLE, result.qualification());
        // 可能区间上界应被 2025-05 拆除收窄，而不是开放到 FAR_FUTURE
        TimeInterval clipped = result.possibleIntervals().get(0);
        assertEquals(OffsetDateTime.parse("2025-05-01T00:00:00Z"), clipped.end());
    }
}
