package com.mdm.platform.service.notice;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import com.mdm.platform.security.DigestUtil;
import com.mdm.platform.service.QualityEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 维修通知资格服务（主编排）：
 * 对一个供应商条件（供应商 + 目标型号）与目标时间窗，
 * 综合“安装证据区间引擎”与“证据论证图”，输出每台设备的通知资格四档与矛盾待核，
 * 并落库资格结论、论证节点/边、独立支持路径，保证结论可回放。
 *
 * 产出的是【通知资格】而非受影响快照：
 *  - 不把仅在历史快照出现过某型号的设备全部通知，必须有目标时段的实际安装证据
 *  - 证据相互矛盾 -> PENDING_REVIEW，平台不做资格裁定
 *  - 嵌套总成转装内部件按层级派生、不复制为两处
 *  - 迟到凭证受 asOfReceived 认知边界约束，二次评估只产生补充名单，历史评估保留不改写
 */
@Service
public class MaintenanceNotificationService {

    private final JdbcSupport db;
    private final EvidenceGraphBuilder graphBuilder;
    private final InstallationIntervalEngine engine;
    private final QualityEventService qualityEvents;

    public MaintenanceNotificationService(JdbcSupport db, EvidenceGraphBuilder graphBuilder,
                                          InstallationIntervalEngine engine,
                                          QualityEventService qualityEvents) {
        this.db = db;
        this.graphBuilder = graphBuilder;
        this.engine = engine;
        this.qualityEvents = qualityEvents;
    }

    public record NoticeCondition(Long supplierId, Long targetModelId,
                                  OffsetDateTime windowStart, OffsetDateTime windowEnd,
                                  OffsetDateTime asOfReceived) {}

    // ---------------- 查询 ----------------

    public List<Map<String, Object>> listNotices() {
        return db.query("SELECT n.*, s.display_name AS supplier_name, m.entity_code AS target_model_code, " +
                "m.display_name AS target_model_name FROM maintenance_notice n " +
                "LEFT JOIN master_entity s ON s.id=n.supplier_id " +
                "LEFT JOIN master_entity m ON m.id=n.target_model_id ORDER BY n.id");
    }

    public List<Map<String, Object>> listRuns(long noticeId) {
        return db.query("SELECT r.*, n.notice_code FROM notice_eligibility_run r " +
                "JOIN maintenance_notice n ON n.id=r.notice_id WHERE r.notice_id=? ORDER BY r.id", noticeId);
    }

    public Map<String, Object> runDetail(long runId) {
        Map<String, Object> run = db.queryOne("SELECT * FROM notice_eligibility_run WHERE id=?", runId);
        if (run == null) {
            throw new IllegalArgumentException("评估记录不存在: " + runId);
        }
        List<Map<String, Object>> eligibilities = db.query(
                "SELECT el.*, eq.entity_code AS equipment_code, eq.display_name AS equipment_name " +
                "FROM notice_eligibility el JOIN master_entity eq ON eq.id=el.equipment_id " +
                "WHERE el.run_id=? ORDER BY el.qualification, el.equipment_id", runId);
        List<Map<String, Object>> paths = db.query(
                "SELECT * FROM evidence_support_path WHERE eligibility_id IN " +
                "(SELECT id FROM notice_eligibility WHERE run_id=?) ORDER BY id", runId);
        List<Map<String, Object>> edges = db.query(
                "SELECT ee.*, fn.node_code AS from_code, tn.node_code AS to_code FROM evidence_edge ee " +
                "JOIN evidence_node fn ON fn.id=ee.from_node_id JOIN evidence_node tn ON tn.id=ee.to_node_id " +
                "WHERE ee.run_id=? ORDER BY ee.id", runId);
        return Map.of("run", run, "eligibilities", eligibilities,
                "supportPaths", paths, "edges", edges);
    }

    @Transactional
    public Map<String, Object> createNotice(Map<String, Object> body) {
        String code = String.valueOf(body.get("noticeCode"));
        if (db.queryOne("SELECT id FROM maintenance_notice WHERE notice_code=?", code) != null) {
            throw new IllegalStateException("维修通知编号已存在: " + code);
        }
        OffsetDateTime ws = OffsetDateTime.parse(String.valueOf(body.get("windowStart")));
        OffsetDateTime we = OffsetDateTime.parse(String.valueOf(body.get("windowEnd")));
        if (!we.isAfter(ws)) {
            throw new IllegalArgumentException("目标时间窗结束必须晚于开始");
        }
        String sourceRef = String.valueOf(body.getOrDefault("sourceRef", code));
        String digest = DigestUtil.sha256(code + ws + we);
        long id = db.insertReturningId(
                "INSERT INTO maintenance_notice(notice_code,title,supplier_id,target_model_id," +
                "window_start,window_end,source_ref,payload_digest,created_by) " +
                "VALUES (?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?) RETURNING id",
                code, body.get("title"), body.get("supplierId"), body.get("targetModelId"),
                ws, we, sourceRef, "sha256:" + digest, CurrentUser.username());
        return db.queryOne("SELECT * FROM maintenance_notice WHERE id=?", id);
    }

    // ---------------- 评估（持久化） ----------------

    @Transactional
    public Map<String, Object> evaluateNotice(long noticeId, OffsetDateTime asOfReceived) {
        Map<String, Object> notice = db.queryOne("SELECT * FROM maintenance_notice WHERE id=?", noticeId);
        if (notice == null) {
            throw new IllegalArgumentException("维修通知不存在: " + noticeId);
        }
        NoticeCondition condition = new NoticeCondition(
                notice.get("supplier_id") == null ? null : ((Number) notice.get("supplier_id")).longValue(),
                notice.get("target_model_id") == null ? null : ((Number) notice.get("target_model_id")).longValue(),
                db.toOffsetDateTime(notice.get("window_start")),
                db.toOffsetDateTime(notice.get("window_end")),
                asOfReceived);

        ComputedResult computed = compute(condition);

        long runCount = db.count("SELECT count(*) FROM notice_eligibility_run");
        String runNo = String.format("NEL-RUN-%05d", runCount + 1);
        String runDigest = DigestUtil.sha256(
                "notice=" + noticeId + "|asOf=" + asOfReceived + "|phys=" + computed.physicalIds()
                        + "|digestInputs=" + computed.inputFingerprint());
        long runId = db.insertReturningId(
                "INSERT INTO notice_eligibility_run(notice_id,run_no,as_of_received,certain_count," +
                "possible_count,insufficient_count,excluded_count,review_count,run_digest,created_by) " +
                "VALUES (?,?,?::timestamptz,?,?,?,?,?,?,?) RETURNING id",
                noticeId, runNo, asOfReceived,
                countOf(computed, Qualification.CERTAIN), countOf(computed, Qualification.POSSIBLE),
                countOf(computed, Qualification.INSUFFICIENT), countOf(computed, Qualification.EXCLUDED),
                countOf(computed, Qualification.PENDING_REVIEW), runDigest, CurrentUser.username());

        persistGraphAndEligibility(runId, noticeId, computed);
        return runDetail(runId);
    }

    // ---------------- 临时评估（不落库，用于即时试算） ----------------

    public Map<String, Object> evaluateAdHoc(NoticeCondition condition) {
        ComputedResult computed = compute(condition);
        Map<Qualification, Long> counts = new LinkedHashMap<>();
        for (Qualification q : Qualification.values()) {
            counts.put(q, computed.equipmentResults().values().stream()
                    .filter(v -> v.qualification() == q).count());
        }
        Map<String, Object> condView = new LinkedHashMap<>();
        condView.put("supplierId", condition.supplierId());
        condView.put("targetModelId", condition.targetModelId());
        condView.put("windowStart", condition.windowStart());
        condView.put("windowEnd", condition.windowEnd());
        condView.put("asOfReceived", condition.asOfReceived() == null ? "ALL_KNOWN" : condition.asOfReceived());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("condition", condView);
        summary.put("counts", counts);
        summary.put("eligibilities", computed.equipmentResults().values().stream()
                .map(this::toJsonView).toList());
        return summary;
    }

    // ---------------- 核心计算 ----------------

    public record ComputedResult(NoticeCondition condition, List<Long> physicalIds, String inputFingerprint,
                                 Map<Long, EquipmentView> equipmentResults, Map<String, EvidenceAnchor> nodeIndex,
                                 List<String[]> derivationEdges, List<EvidenceAnchor> allAnchors) {}

    public record EquipmentView(long equipmentId, Qualification qualification,
                                List<TimeInterval> necessaryIntervals, List<TimeInterval> possibleIntervals,
                                boolean necessaryOpenEnded, boolean possibleOpenEnded,
                                List<EvidenceAnchor> minimalEvidence, List<List<EvidenceAnchor>> supportPaths,
                                List<InstallationIntervalEngine.Contradiction> contradictions,
                                String reasoning, Set<Long> physicalIds) {}

    private ComputedResult compute(NoticeCondition condition) {
        if (condition.targetModelId() == null) {
            throw new IllegalArgumentException("维修通知资格评估必须指定目标部件型号");
        }
        TimeInterval window = new TimeInterval(condition.windowStart(), condition.windowEnd());
        EvidenceGraphBuilder.AssemblyLinks links = graphBuilder.loadAssemblyLinks();
        List<Map<String, Object>> physicals = graphBuilder.physicalsOfModel(condition.targetModelId());

        // 供应商条件：实物挂 supplier_code（供应商实体编码）；给定供应商时只纳入该供应商的实物
        String supplierCode = null;
        if (condition.supplierId() != null) {
            Map<String, Object> supplier = db.queryOne(
                    "SELECT entity_code FROM master_entity WHERE id=? AND entity_type='SUPPLIER'",
                    condition.supplierId());
            if (supplier == null) {
                throw new IllegalArgumentException("供应商不存在: " + condition.supplierId());
            }
            supplierCode = String.valueOf(supplier.get("entity_code"));
        }
        final String sc = supplierCode;
        List<Long> physicalIds = physicals.stream()
                .filter(p -> sc == null || sc.equals(p.get("supplier_code")))
                .map(p -> ((Number) p.get("id")).longValue()).toList();

        Map<String, EvidenceAnchor> nodeIndex = new LinkedHashMap<>();
        List<EvidenceAnchor> allAnchors = new ArrayList<>();
        List<String[]> derivationEdges = new ArrayList<>();
        // 设备 -> 实物 -> 评估
        Map<Long, Map<Long, InstallationIntervalEngine.EquipmentAssessment>> byEquipment = new LinkedHashMap<>();

        for (Long physicalId : physicalIds) {
            EvidenceGraphBuilder.PhysicalGraph graph =
                    graphBuilder.buildForPhysical(physicalId, links, condition.asOfReceived());
            graph.derivationEdges().forEach(derivationEdges::add);
            Map<Long, InstallationIntervalEngine.EquipmentAssessment> assessed =
                    engine.assess(graph.anchorsByEquipment(), window);
            for (Map.Entry<Long, List<EvidenceAnchor>> e : graph.anchorsByEquipment().entrySet()) {
                for (EvidenceAnchor a : e.getValue()) {
                    nodeIndex.putIfAbsent(a.nodeCode(), a);
                    allAnchors.add(a);
                }
            }
            for (Map.Entry<Long, InstallationIntervalEngine.EquipmentAssessment> e : assessed.entrySet()) {
                byEquipment.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).put(physicalId, e.getValue());
            }
        }

        // 全量设备：无任何该型号证据的设备归入证据不足，形成完整四档划分
        Map<Long, EquipmentView> equipmentResults = new LinkedHashMap<>();
        List<Map<String, Object>> allEquipment = db.query(
                "SELECT * FROM master_entity WHERE entity_type='EQUIPMENT' ORDER BY id");
        for (Map<String, Object> eq : allEquipment) {
            long equipmentId = ((Number) eq.get("id")).longValue();
            Map<Long, InstallationIntervalEngine.EquipmentAssessment> perPhysical =
                    byEquipment.getOrDefault(equipmentId, Map.of());
            equipmentResults.put(equipmentId, aggregate(equipmentId, perPhysical));
        }

        Map<Qualification, Long> counts = new LinkedHashMap<>();
        for (Qualification q : Qualification.values()) {
            counts.put(q, equipmentResults.values().stream().filter(v -> v.qualification() == q).count());
        }
        String fingerprint = "model=" + condition.targetModelId()
                + "|window=" + window.start() + ".." + window.end()
                + "|asOf=" + condition.asOfReceived()
                + "|anchors=" + nodeIndex.keySet();
        return new ComputedResult(condition, physicalIds, fingerprint, equipmentResults,
                nodeIndex, derivationEdges, allAnchors);
    }

    /** 跨同一设备上多件实物聚合资格：矛盾优先，其次必然、可能、可排除、证据不足。 */
    private EquipmentView aggregate(long equipmentId,
                                    Map<Long, InstallationIntervalEngine.EquipmentAssessment> perPhysical) {
        List<TimeInterval> necessary = new ArrayList<>();
        List<TimeInterval> possible = new ArrayList<>();
        boolean necOpenEnded = false;
        boolean posOpenEnded = false;
        List<EvidenceAnchor> minimal = new ArrayList<>();
        List<List<EvidenceAnchor>> paths = new ArrayList<>();
        List<InstallationIntervalEngine.Contradiction> contradictions = new ArrayList<>();
        Set<Long> involvedPhysicals = new LinkedHashSet<>();
        List<String> reasonParts = new ArrayList<>();

        for (Map.Entry<Long, InstallationIntervalEngine.EquipmentAssessment> e : perPhysical.entrySet()) {
            involvedPhysicals.add(e.getKey());
            InstallationIntervalEngine.EquipmentAssessment a = e.getValue();
            necessary.addAll(a.necessaryIntervals());
            possible.addAll(a.possibleIntervals());
            necOpenEnded |= a.necessaryOpenEnded();
            posOpenEnded |= a.possibleOpenEnded();
            contradictions.addAll(a.contradictions());
            for (EvidenceAnchor an : a.minimalEvidence()) {
                if (minimal.stream().noneMatch(x -> x.nodeCode().equals(an.nodeCode()))) {
                    minimal.add(an);
                }
            }
            paths.addAll(a.supportPaths());
            reasonParts.add("实物" + e.getKey() + "=" + a.qualification().label());
        }

        Qualification q;
        String reasoning;
        if (perPhysical.isEmpty()) {
            q = Qualification.INSUFFICIENT;
            reasoning = "目标时段内没有任何与目标型号相关的安装/缺席凭证，无法证实该设备装过该型号，证据不足";
        } else if (!contradictions.isEmpty()) {
            q = Qualification.PENDING_REVIEW;
            reasoning = "存在相互矛盾的安装证据（" + contradictions.size() + " 处），平台不做资格裁定，只能待核；"
                    + String.join("；", reasonParts);
        } else if (perPhysical.values().stream().anyMatch(a -> a.qualification() == Qualification.CERTAIN)) {
            q = Qualification.CERTAIN;
            reasoning = "至少一件实物的已证实安装（必要）区间与目标窗相交：" + String.join("；", reasonParts);
        } else if (perPhysical.values().stream().anyMatch(a -> a.qualification() == Qualification.POSSIBLE)) {
            q = Qualification.POSSIBLE;
            reasoning = "仅有盘点扩张的可能区间与目标窗相交，装拆时点稀疏，留下较宽可能集："
                    + String.join("；", reasonParts);
        } else if (perPhysical.values().stream().allMatch(a -> a.qualification() == Qualification.EXCLUDED)) {
            q = Qualification.EXCLUDED;
            reasoning = "目标窗被已证实缺席/独立缺席盘点覆盖且无在装主张，可排除：" + String.join("；", reasonParts);
        } else {
            q = Qualification.INSUFFICIENT;
            reasoning = "证据不足以判定目标时段安装状态：" + String.join("；", reasonParts);
        }
        return new EquipmentView(equipmentId, q, TimeInterval.merge(necessary),
                TimeInterval.merge(possible), necOpenEnded, posOpenEnded,
                minimal, paths, contradictions, reasoning, involvedPhysicals);
    }

    // ---------------- 持久化 ----------------

    private void persistGraphAndEligibility(long runId, long noticeId, ComputedResult computed) {
        // 论证节点（全局编号唯一，重复评估复用同一节点）
        for (EvidenceAnchor a : computed.nodeIndex().values()) {
            db.update("INSERT INTO evidence_node(node_code,node_type,equipment_id,mount_position_id," +
                    "physical_part_id,part_model_id,claim_from,claim_to,certainty,source_table,source_id," +
                    "source_ref,payload_digest,trust_score,received_at) " +
                    "VALUES (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?::timestamptz) " +
                    "ON CONFLICT (node_code) DO NOTHING",
                    a.nodeCode(), a.nodeType(), a.equipmentId(), a.mountPositionId(),
                    a.physicalPartId(), a.partModelId(), a.claimFrom(), a.claimTo(),
                    a.certainty(), a.sourceTable(), a.sourceId(), a.sourceRef(),
                    a.payloadDigest(), a.trustScore(), a.receivedAt());
        }
        Map<String, Long> nodeIdByCode = new LinkedHashMap<>();
        for (String code : computed.nodeIndex().keySet()) {
            Map<String, Object> row = db.queryOne("SELECT id FROM evidence_node WHERE node_code=?", code);
            if (row != null) {
                nodeIdByCode.put(code, ((Number) row.get("id")).longValue());
            }
        }
        // 总成派生边
        for (String[] edge : computed.derivationEdges()) {
            Long from = nodeIdByCode.get(edge[0]);
            Long to = nodeIdByCode.get(edge[1]);
            if (from != null && to != null) {
                db.update("INSERT INTO evidence_edge(run_id,from_node_id,to_node_id,edge_type,note) " +
                        "VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                        runId, from, to, "DERIVED_ASSEMBLY", "嵌套总成转装：内部件安装断言随父总成派生");
            }
        }

        for (EquipmentView view : computed.equipmentResults().values()) {
            long eligibilityId = db.insertReturningId(
                    "INSERT INTO notice_eligibility(run_id,notice_id,equipment_id,target_model_id," +
                    "qualification,necessary_intervals,possible_intervals,minimal_evidence,contradiction,reasoning) " +
                    "VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?) RETURNING id",
                    runId, noticeId, view.equipmentId(), computed.condition().targetModelId(),
                    view.qualification().name(),
                    db.toJson(intervalJson(view.necessaryIntervals(), view.necessaryOpenEnded())),
                    db.toJson(intervalJson(view.possibleIntervals(), view.possibleOpenEnded())),
                    db.toJson(view.minimalEvidence().stream().map(this::anchorJson).toList()),
                    db.toJson(view.contradictions().stream().map(this::contradictionJson).toList()),
                    view.reasoning());

            // 独立支持路径
            int pathIdx = 0;
            Set<String> seenPath = new LinkedHashSet<>();
            for (List<EvidenceAnchor> path : view.supportPaths()) {
                String[] codes = path.stream().map(EvidenceAnchor::nodeCode).toArray(String[]::new);
                String key = String.join("->", codes);
                if (seenPath.add(key)) {
                    pathIdx++;
                    db.update("INSERT INTO evidence_support_path(eligibility_id,path_no,independent,node_codes," +
                            "path_summary) VALUES (?,?,?,?::varchar[],?)",
                            eligibilityId, String.format("PATH-%02d", pathIdx), true,
                            "{" + String.join(",", codes) + "}",
                            "独立支持路径：" + path.stream().map(a -> a.nodeType() + "/" + a.sourceRef()).toList());
                }
            }

            if (view.qualification() == Qualification.PENDING_REVIEW) {
                qualityEvents.record("NOTICE_PENDING_REVIEW", "HIGH", "EQUIPMENT", view.equipmentId(),
                        null, null,
                        "维修通知 " + noticeId + " 评估：设备 " + view.equipmentId()
                                + " 安装证据相互矛盾，资格待核，不纳入必然通知名单",
                        Map.of("runId", runId, "contradictions",
                                view.contradictions().stream().map(this::contradictionJson).toList()));
            }
        }
    }

    private long countOf(ComputedResult computed, Qualification q) {
        return computed.equipmentResults().values().stream().filter(v -> v.qualification() == q).count();
    }

    private List<Map<String, Object>> intervalJson(List<TimeInterval> intervals, boolean openEnded) {
        List<Map<String, Object>> out = new ArrayList<>();
        int last = intervals.size() - 1;
        for (int i = 0; i < intervals.size(); i++) {
            TimeInterval iv = intervals.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", iv.start().toString());
            m.put("end", iv.end().toString());
            // 裁剪后落在目标窗结束边界的末段，若源区间延伸到远端（缺拆除单），标记为开放端
            boolean open = EvidenceGraphBuilder.FAR_FUTURE.equals(iv.end())
                    || (openEnded && i == last);
            m.put("openEnd", open);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> anchorJson(EvidenceAnchor a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeCode", a.nodeCode());
        m.put("nodeType", a.nodeType());
        m.put("equipmentId", a.equipmentId());
        m.put("physicalPartId", a.physicalPartId());
        m.put("partModelId", a.partModelId());
        m.put("claimFrom", a.claimFrom() == null ? null : a.claimFrom().toString());
        m.put("claimTo", a.claimTo() == null ? null : a.claimTo().toString());
        m.put("certainty", a.certainty());
        m.put("observed", a.observed());
        m.put("sourceTable", a.sourceTable());
        m.put("sourceRef", a.sourceRef());
        m.put("trustScore", a.trustScore());
        m.put("assemblyDerived", a.assemblyDerived());
        m.put("note", a.note());
        return m;
    }

    private Map<String, Object> contradictionJson(InstallationIntervalEngine.Contradiction c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detail", c.detail());
        m.put("presence", c.presence() == null ? null : anchorJson(c.presence()));
        m.put("absence", c.absence() == null ? null : anchorJson(c.absence()));
        return m;
    }

    private Map<String, Object> toJsonView(EquipmentView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equipmentId", v.equipmentId());
        m.put("qualification", v.qualification().name());
        m.put("qualificationLabel", v.qualification().label());
        m.put("necessaryIntervals", intervalJson(v.necessaryIntervals(), v.necessaryOpenEnded()));
        m.put("possibleIntervals", intervalJson(v.possibleIntervals(), v.possibleOpenEnded()));
        m.put("minimalEvidence", v.minimalEvidence().stream().map(this::anchorJson).toList());
        m.put("supportPaths", v.supportPaths().stream()
                .map(p -> p.stream().map(EvidenceAnchor::nodeCode).toList()).toList());
        m.put("contradictions", v.contradictions().stream().map(this::contradictionJson).toList());
        m.put("reasoning", v.reasoning());
        m.put("physicalIds", v.physicalIds());
        return m;
    }
}
