package com.mdm.platform.service.notice;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 安装证据区间引擎（纯逻辑，不依赖数据库，可单测）：
 * 把一件实物在各设备上的证据锚点，转换为“已证实安装（必要）/可能安装（可能）/已证实缺席”时间区间，
 * 再与目标时间窗求交，给出四档资格与矛盾标记。
 *
 * 语义约定（半开区间 [start,end)）：
 *  - 精确装拆签字/出厂基线：端点是已证实时点，产生【必要】安装区间，拆卸后产生【必要缺席】区间
 *  - 盘点 PRESENT：只证实盘点瞬时在装；无精确凭证覆盖时，向前后已证实缺席边界扩张为【可能】区间
 *  - 盘点 ABSENT：证实盘点瞬时缺席；两次独立缺席盘点且从无在装主张 -> 可排除
 *  - 在装主张与缺席主张在同一时点冲突 -> 矛盾，只能待核（PENDING_REVIEW）
 */
@Service
public class InstallationIntervalEngine {

    /** 单设备评估结果。 */
    public record EquipmentAssessment(
            long equipmentId,
            Qualification qualification,
            List<TimeInterval> necessaryIntervals,   // 已裁剪到目标窗
            List<TimeInterval> possibleIntervals,    // 已裁剪到目标窗
            boolean necessaryOpenEnded,              // 裁剪前存在延伸到远端（缺拆除单）的必要区间
            boolean possibleOpenEnded,               // 裁剪前存在延伸到远端（缺拆除单）的可能区间
            List<EvidenceAnchor> minimalEvidence,
            List<List<EvidenceAnchor>> supportPaths, // 独立支持路径（每条可独立支撑结论）
            List<Contradiction> contradictions,
            String reasoning
    ) {}

    public record Contradiction(EvidenceAnchor presence, EvidenceAnchor absence, String detail) {}

    private static final OffsetDateTime FAR_PAST = EvidenceGraphBuilder.FAR_PAST;
    private static final OffsetDateTime FAR_FUTURE = EvidenceGraphBuilder.FAR_FUTURE;

    /**
     * 评估一件实物在各设备上、相对目标窗的资格。
     *
     * @param anchorsByEquipment key=equipmentId
     */
    public Map<Long, EquipmentAssessment> assess(Map<Long, List<EvidenceAnchor>> anchorsByEquipment,
                                                 TimeInterval window) {
        Map<Long, EquipmentAssessment> results = new LinkedHashMap<>();
        for (Map.Entry<Long, List<EvidenceAnchor>> entry : anchorsByEquipment.entrySet()) {
            results.put(entry.getKey(), assessOne(entry.getKey(), entry.getValue(), window));
        }
        detectCrossEquipmentDoubleMount(results, anchorsByEquipment, window);
        return results;
    }

    private EquipmentAssessment assessOne(long equipmentId, List<EvidenceAnchor> anchors, TimeInterval window) {
        List<EvidenceAnchor> precise = anchors.stream()
                .filter(a -> !"INVENTORY_COUNT".equals(a.nodeType()))
                .sorted(Comparator.comparing(EvidenceAnchor::claimFrom))
                .toList();
        List<EvidenceAnchor> counts = anchors.stream()
                .filter(a -> "INVENTORY_COUNT".equals(a.nodeType()))
                .sorted(Comparator.comparing(EvidenceAnchor::claimFrom))
                .toList();

        // 1. 精确凭证状态机 -> 必要安装区间 / 必要缺席区间
        List<TimeInterval> necessary = new ArrayList<>();
        List<TimeInterval> provenEmpty = new ArrayList<>();
        OffsetDateTime installedSince = null;
        for (EvidenceAnchor a : precise) {
            OffsetDateTime t = a.claimFrom();
            boolean presence = !a.isAbsentClaim() && !"REMOVE_SIGN".equals(a.nodeType());
            if (presence) {
                if (installedSince != null) {
                    necessary.add(new TimeInterval(installedSince, t)); // 两次安装间闭合前一段
                }
                installedSince = t;
            } else { // REMOVE_SIGN / 缺席型精确凭证
                if (installedSince != null) {
                    necessary.add(new TimeInterval(installedSince, t));
                    installedSince = null;
                }
                provenEmpty.add(new TimeInterval(t, FAR_FUTURE)); // 下一次安装会收窄它
            }
        }
        if (installedSince != null) {
            necessary.add(new TimeInterval(installedSince, FAR_FUTURE));
        }
        necessary = TimeInterval.merge(necessary);
        provenEmpty = narrowEmptySegments(provenEmpty, necessary);

        // 2. 盘点凭证一致性检查 + 可能区间
        List<Contradiction> contradictions = new ArrayList<>();
        List<EvidenceAnchor> presentCounts = new ArrayList<>();
        List<EvidenceAnchor> absentCounts = new ArrayList<>();
        for (EvidenceAnchor c : counts) {
            OffsetDateTime t = c.claimFrom();
            if (c.isAbsentClaim()) {
                if (covers(necessary, t)) {
                    contradictions.add(new Contradiction(
                            anchorAtTime(precise, t), c,
                            "盘点在 " + t + " 盘到空位，但精确装拆凭证证实该实物此时在装（相反盘点）"));
                }
                absentCounts.add(c);
            } else {
                if (covers(provenEmpty, t)) {
                    contradictions.add(new Contradiction(
                            c, anchorAtTime(precise, t),
                            "盘点在 " + t + " 盘到在装，但精确凭证证实此时该位置已拆除/为空"));
                }
                presentCounts.add(c);
            }
        }

        // 3. 开放结尾安装段降级（缺少拆除单纪律）：
        //    只有安装主张、没有任何拆除凭证时，无法证实目标时段仍在装。
        //    若其后有在装盘点 p，则 [安装, p) 仍为必要，[p, 远端) 降为可能；否则整段降为可能。
        List<TimeInterval> possible = new ArrayList<>();
        if (contradictions.isEmpty()) {
            List<TimeInterval> downgraded = new ArrayList<>();
            for (TimeInterval seg : necessary) {
                if (!FAR_FUTURE.equals(seg.end())) {
                    downgraded.add(seg);
                    continue;
                }
                OffsetDateTime lastPresence = presentCounts.stream()
                        .map(EvidenceAnchor::claimFrom)
                        .filter(t -> !t.isBefore(seg.start()))
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                if (lastPresence != null && lastPresence.isAfter(seg.start())) {
                    downgraded.add(new TimeInterval(seg.start(), lastPresence));
                    possible.add(new TimeInterval(lastPresence, FAR_FUTURE));
                } else {
                    possible.add(new TimeInterval(seg.start(), FAR_FUTURE));
                }
            }
            necessary = TimeInterval.merge(downgraded);
        }

        // 4. 未被必要区间覆盖的 PRESENT 盘点，向相邻已证实缺席边界扩张为可能区间
        possible.addAll(buildPossibleIntervals(presentCounts, necessary, provenEmpty, absentCounts));
        possible = TimeInterval.merge(possible);

        // 4. 与目标窗求交（开放端标志按裁剪前区间计算，避免被窗边界掩盖“缺少拆除单”）
        boolean necOpenEnded = necessary.stream().anyMatch(iv -> FAR_FUTURE.equals(iv.end()));
        boolean posOpenEnded = possible.stream().anyMatch(iv -> FAR_FUTURE.equals(iv.end()));
        List<TimeInterval> necClip = TimeInterval.clipToWindow(necessary, window);
        List<TimeInterval> posClip = TimeInterval.clipToWindow(possible, window);
        List<Contradiction> windowContradictions = contradictions.stream()
                .filter(c -> withinOrTouchingWindow(c, window))
                .toList();

        // 5. 最小证据与独立支持路径
        List<EvidenceAnchor> minimal = new ArrayList<>();
        List<List<EvidenceAnchor>> paths = new ArrayList<>();
        Qualification qualification = decide(
                window, necClip, posClip, provenEmpty, absentCounts, presentCounts,
                windowContradictions, precise, minimal, paths);

        String reasoning = buildReasoning(qualification, window, necClip, posClip,
                provenEmpty, absentCounts, windowContradictions);
        return new EquipmentAssessment(equipmentId, qualification, necClip, posClip,
                necOpenEnded, posOpenEnded, minimal, paths, windowContradictions, reasoning);
    }

    /** 用必要安装区间收窄必要缺席区间：缺席段 [t,+inf) 在下一次安装处结束。 */
    private List<TimeInterval> narrowEmptySegments(List<TimeInterval> rawEmpty, List<TimeInterval> necessary) {
        List<TimeInterval> narrowed = new ArrayList<>();
        for (TimeInterval empty : rawEmpty) {
            OffsetDateTime end = FAR_FUTURE;
            for (TimeInterval nec : necessary) {
                if (!nec.start().isBefore(empty.start()) && nec.start().isBefore(end)) {
                    end = nec.start();
                }
            }
            if (empty.start().isBefore(end)) {
                narrowed.add(new TimeInterval(empty.start(), end));
            }
        }
        return TimeInterval.merge(narrowed);
    }

    private List<TimeInterval> buildPossibleIntervals(List<EvidenceAnchor> presentCounts,
                                                      List<TimeInterval> necessary,
                                                      List<TimeInterval> provenEmpty,
                                                      List<EvidenceAnchor> absentCounts) {
        List<TimeInterval> possible = new ArrayList<>();
        for (EvidenceAnchor c : presentCounts) {
            OffsetDateTime t = c.claimFrom();
            if (covers(necessary, t)) {
                continue; // 已被必要区间覆盖，不重复计可能
            }
            // 下界：t 之前最近的已证实缺席（拆卸/缺席盘点）
            OffsetDateTime low = FAR_PAST;
            for (TimeInterval e : provenEmpty) {
                if (!e.start().isAfter(t) && e.start().isAfter(low)) {
                    low = e.start();
                }
            }
            for (EvidenceAnchor abs : absentCounts) {
                if (!abs.claimFrom().isAfter(t) && abs.claimFrom().isAfter(low)) {
                    low = abs.claimFrom();
                }
            }
            // 上界：t 之后最早的已证实缺席
            OffsetDateTime high = FAR_FUTURE;
            for (TimeInterval e : provenEmpty) {
                if (e.start().isAfter(t) && e.start().isBefore(high)) {
                    high = e.start();
                }
            }
            for (EvidenceAnchor abs : absentCounts) {
                if (abs.claimFrom().isAfter(t) && abs.claimFrom().isBefore(high)) {
                    high = abs.claimFrom();
                }
            }
            possible.add(new TimeInterval(low, high));
        }
        return TimeInterval.merge(possible);
    }

    private Qualification decide(TimeInterval window, List<TimeInterval> necClip, List<TimeInterval> posClip,
                                 List<TimeInterval> provenEmpty, List<EvidenceAnchor> absentCounts,
                                 List<EvidenceAnchor> presentCounts, List<Contradiction> contradictions,
                                 List<EvidenceAnchor> precise,
                                 List<EvidenceAnchor> minimal, List<List<EvidenceAnchor>> paths) {
        if (!contradictions.isEmpty()) {
            Contradiction first = contradictions.get(0);
            minimal.add(first.presence());
            minimal.add(first.absence());
            paths.add(List.of(first.presence()));
            paths.add(List.of(first.absence()));
            return Qualification.PENDING_REVIEW;
        }
        if (!necClip.isEmpty()) {
            // 最小证据：开启相交必要区间的安装/出厂凭证（若窗内被拆卸则附拆卸凭证）
            EvidenceAnchor opener = precise.stream()
                    .filter(a -> !a.isAbsentClaim() && !"REMOVE_SIGN".equals(a.nodeType()))
                    .filter(a -> necClip.stream().anyMatch(iv -> !a.claimFrom().isAfter(iv.start())))
                    .max(Comparator.comparing(EvidenceAnchor::claimFrom))
                    .orElse(precise.isEmpty() ? null : precise.get(0));
            if (opener != null) {
                minimal.add(opener);
                paths.add(List.of(opener));
            }
            precise.stream().filter(a -> "REMOVE_SIGN".equals(a.nodeType()))
                    .filter(a -> window.contains(a.claimFrom()))
                    .findFirst().ifPresent(rem -> {
                        minimal.add(rem);
                        paths.add(List.of(rem));
                    });
            return Qualification.CERTAIN;
        }
        if (!posClip.isEmpty()) {
            List<EvidenceAnchor> supporting = presentCounts.stream()
                    .filter(c -> posClip.stream().anyMatch(iv -> iv.contains(c.claimFrom())
                            || iv.start().equals(c.claimFrom())))
                    .toList();
            List<EvidenceAnchor> chosen = supporting.isEmpty() ? presentCounts : supporting;
            for (EvidenceAnchor c : chosen) {
                minimal.add(c);
                paths.add(List.of(c)); // 每张盘点凭证是一条独立支持路径
            }
            return Qualification.POSSIBLE;
        }
        // 无窗内安装证据：区分可排除 vs 证据不足
        boolean windowProvenEmpty = coversFully(provenEmpty, window);
        long absentInWindow = absentCounts.stream()
                .filter(a -> window.contains(a.claimFrom())).count();
        boolean noPresenceEver = presentCounts.isEmpty()
                && precise.stream().noneMatch(a -> !a.isAbsentClaim() && !"REMOVE_SIGN".equals(a.nodeType()));
        if (windowProvenEmpty || (noPresenceEver && absentInWindow >= 2)) {
            if (windowProvenEmpty) {
                precise.stream().filter(a -> "REMOVE_SIGN".equals(a.nodeType()))
                        .max(Comparator.comparing(EvidenceAnchor::claimFrom))
                        .ifPresent(rem -> {
                            minimal.add(rem);
                            paths.add(List.of(rem));
                        });
            }
            for (EvidenceAnchor abs : absentCounts) {
                if (window.contains(abs.claimFrom())) {
                    minimal.add(abs);
                    paths.add(List.of(abs));
                }
            }
            return Qualification.EXCLUDED;
        }
        return Qualification.INSUFFICIENT;
    }

    private boolean withinOrTouchingWindow(Contradiction c, TimeInterval window) {
        OffsetDateTime t1 = c.presence() == null ? null : c.presence().claimFrom();
        OffsetDateTime t2 = c.absence() == null ? null : c.absence().claimFrom();
        return (t1 != null && !t1.isBefore(window.start()) && t1.isBefore(window.end()))
                || (t2 != null && !t2.isBefore(window.start()) && t2.isBefore(window.end()));
    }

    private boolean covers(List<TimeInterval> intervals, OffsetDateTime t) {
        return intervals.stream().anyMatch(iv -> iv.contains(t));
    }

    private boolean coversFully(List<TimeInterval> intervals, TimeInterval window) {
        List<TimeInterval> clipped = TimeInterval.clipToWindow(intervals, window);
        if (clipped.isEmpty()) {
            return false;
        }
        // 裁剪后合并的首段须覆盖整个窗
        TimeInterval first = clipped.get(0);
        return clipped.size() == 1
                && !first.start().isAfter(window.start())
                && !first.end().isBefore(window.end());
    }

    /** 找出在 t 时刻主张“在装”的精确凭证（用于矛盾对照）。 */
    private EvidenceAnchor anchorAtTime(List<EvidenceAnchor> precise, OffsetDateTime t) {
        return precise.stream()
                .filter(a -> !a.isAbsentClaim() && !"REMOVE_SIGN".equals(a.nodeType()))
                .filter(a -> !a.claimFrom().isAfter(t))
                .max(Comparator.comparing(EvidenceAnchor::claimFrom))
                .orElse(precise.isEmpty() ? null : precise.get(0));
    }

    /** 同一实物在两台设备的必要安装区间在窗内重叠 -> 双重安装矛盾，两端均待核。 */
    private void detectCrossEquipmentDoubleMount(Map<Long, EquipmentAssessment> results,
                                                 Map<Long, List<EvidenceAnchor>> anchorsByEquipment,
                                                 TimeInterval window) {
        List<Long> equipmentIds = new ArrayList<>(results.keySet());
        for (int i = 0; i < equipmentIds.size(); i++) {
            for (int j = i + 1; j < equipmentIds.size(); j++) {
                EquipmentAssessment a = results.get(equipmentIds.get(i));
                EquipmentAssessment b = results.get(equipmentIds.get(j));
                boolean overlap = a.necessaryIntervals().stream()
                        .anyMatch(iva -> b.necessaryIntervals().stream().anyMatch(iva::overlaps));
                if (overlap) {
                    results.put(equipmentIds.get(i), markDoubleMount(a));
                    results.put(equipmentIds.get(j), markDoubleMount(b));
                }
            }
        }
    }

    private EquipmentAssessment markDoubleMount(EquipmentAssessment a) {
        if (a.qualification() == Qualification.PENDING_REVIEW) {
            return a;
        }
        Contradiction c = new Contradiction(null, null,
                "同一实物在目标窗内于两台设备同时存在必要安装区间（双重安装），资格待核");
        List<Contradiction> cs = new ArrayList<>(a.contradictions());
        cs.add(c);
        String reasoning = "同一实物在目标窗内于两台设备同时存在必要安装区间（双重安装），平台不做资格裁定，只能待核";
        return new EquipmentAssessment(a.equipmentId(), Qualification.PENDING_REVIEW,
                a.necessaryIntervals(), a.possibleIntervals(),
                a.necessaryOpenEnded(), a.possibleOpenEnded(),
                a.minimalEvidence(), a.supportPaths(), cs, reasoning);
    }

    private String buildReasoning(Qualification q, TimeInterval window, List<TimeInterval> necClip,
                                  List<TimeInterval> posClip, List<TimeInterval> provenEmpty,
                                  List<EvidenceAnchor> absentCounts, List<Contradiction> contradictions) {
        String win = "目标窗[" + window.start() + "," + window.end() + ")";
        return switch (q) {
            case CERTAIN -> win + "与已证实安装（必要）区间相交 " + necClip + "，装拆时点精确，资格=必然安装";
            case POSSIBLE -> win + " 仅与盘点扩张出的可能区间相交 " + posClip
                    + "，装/拆时点无精确凭证，稀疏凭证留下较宽可能集，资格=可能安装";
            case EXCLUDED -> win + " 被已证实缺席区间覆盖或由两次独立缺席盘点支持，且从无在装主张，资格=可排除";
            case PENDING_REVIEW -> "在装主张与缺席凭证相互矛盾（" + contradictions.size()
                    + " 处），平台不做资格裁定，只能待核";
            case INSUFFICIENT -> win + " 无任何在装/缺席凭证与之相关，资格=证据不足";
        };
    }
}
