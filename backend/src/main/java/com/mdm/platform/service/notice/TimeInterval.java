package com.mdm.platform.service.notice;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 半开时间区间 [start, end)。
 * 统一用半开语义：装拆时点精确到瞬时，目标窗 [windowStart, windowEnd)。
 * 区间求交、合并都在该语义下进行，保证边界清晰可回放。
 */
public record TimeInterval(OffsetDateTime start, OffsetDateTime end) {

    public TimeInterval {
        if (start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("区间结束早于开始: " + start + " ~ " + end);
        }
    }

    /** 左开右闭都按半开处理：两区间有非零（或端点相接）交集即视为重叠。 */
    public boolean overlaps(TimeInterval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    /** 与另一区间求交；无交集返回 null。 */
    public TimeInterval intersect(TimeInterval other) {
        OffsetDateTime s = start.isAfter(other.start) ? start : other.start;
        OffsetDateTime e = end.isBefore(other.end) ? end : other.end;
        if (!s.isBefore(e)) {
            return null;
        }
        return new TimeInterval(s, e);
    }

    public boolean contains(OffsetDateTime t) {
        return !t.isBefore(start) && t.isBefore(end);
    }

    /** 合并相邻/重叠区间，输出互不相交且按起点排序的区间列表。 */
    public static List<TimeInterval> merge(List<TimeInterval> intervals) {
        List<TimeInterval> sorted = intervals.stream()
                .filter(i -> i != null)
                .sorted(Comparator.comparing(TimeInterval::start))
                .toList();
        List<TimeInterval> merged = new ArrayList<>();
        for (TimeInterval iv : sorted) {
            if (merged.isEmpty()) {
                merged.add(iv);
                continue;
            }
            TimeInterval last = merged.get(merged.size() - 1);
            // 半开区间：last.end == iv.start 时首尾相接，可合并为一条连续区间
            if (!iv.start.isAfter(last.end)) {
                OffsetDateTime newEnd = last.end.isAfter(iv.end) ? last.end : iv.end;
                merged.set(merged.size() - 1, new TimeInterval(last.start, newEnd));
            } else {
                merged.add(iv);
            }
        }
        return merged;
    }

    /** 用目标窗裁剪一组区间，只保留落在窗内的交集（可能为空列表）。 */
    public static List<TimeInterval> clipToWindow(List<TimeInterval> intervals, TimeInterval window) {
        List<TimeInterval> clipped = new ArrayList<>();
        for (TimeInterval iv : merge(intervals)) {
            TimeInterval hit = iv.intersect(window);
            if (hit != null) {
                clipped.add(hit);
            }
        }
        return clipped;
    }
}
