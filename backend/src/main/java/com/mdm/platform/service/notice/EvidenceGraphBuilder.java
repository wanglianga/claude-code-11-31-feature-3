package com.mdm.platform.service.notice;

import com.mdm.platform.repository.JdbcSupport;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 证据论证图构建器：
 * 把出厂记录、装拆签字、盘点凭证、总成装配层级统一转成 {@link EvidenceAnchor}（节点断言）。
 *
 * 纪律：
 *  - asOfReceived 认知边界：边界之后才接收的凭证不参与本次论证（迟到凭证在后续评估中只补充）
 *  - 嵌套总成：内部件没有自己的装拆凭证时，沿总成层级从父总成派生安装断言，
 *    派生节点带 DERIVED 标记，内部件不会在两台设备被复制为两处
 */
@Component
public class EvidenceGraphBuilder {

    /** 开放区间的远端哨兵（仅用于区间运算，输出时会还原为开放端说明）。 */
    public static final OffsetDateTime FAR_PAST = OffsetDateTime.parse("1970-01-01T00:00:00Z");
    public static final OffsetDateTime FAR_FUTURE = OffsetDateTime.parse("2099-12-31T00:00:00Z");

    private final JdbcSupport db;

    public EvidenceGraphBuilder(JdbcSupport db) {
        this.db = db;
    }

    /** 总成父子关系（一次性加载）。 */
    public record AssemblyLinks(Map<Long, List<Long>> parentToChildren,
                                Map<Long, Long> childToParent) {}

    public AssemblyLinks loadAssemblyLinks() {
        Map<Long, List<Long>> parentToChildren = new HashMap<>();
        Map<Long, Long> childToParent = new HashMap<>();
        for (Map<String, Object> row : db.query(
                "SELECT * FROM part_assembly_link WHERE effective_to IS NULL ORDER BY id")) {
            long parent = ((Number) row.get("parent_physical_id")).longValue();
            long child = ((Number) row.get("child_physical_id")).longValue();
            parentToChildren.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
            childToParent.put(child, parent);
        }
        return new AssemblyLinks(parentToChildren, childToParent);
    }

    /** 目标型号对应的全部实物（按 spec_payload.modelCode 匹配）。 */
    public List<Map<String, Object>> physicalsOfModel(long targetModelId) {
        Map<String, Object> model = db.queryOne(
                "SELECT * FROM master_entity WHERE id=? AND entity_type='PART_MODEL'", targetModelId);
        if (model == null) {
            throw new IllegalArgumentException("目标部件型号不存在: " + targetModelId);
        }
        String modelCode = String.valueOf(model.get("entity_code"));
        return db.query(
                "SELECT * FROM master_entity WHERE entity_type='PHYSICAL_PART' " +
                "AND spec_payload->>'modelCode'=? ORDER BY id", modelCode);
    }

    /** 一个实物的论证图片段：按设备分组的锚点 + 总成派生边（父节点编号 -> 子节点编号）。 */
    public record PhysicalGraph(Map<Long, List<EvidenceAnchor>> anchorsByEquipment,
                                List<String[]> derivationEdges) {
        public static PhysicalGraph empty() {
            return new PhysicalGraph(new LinkedHashMap<>(), new ArrayList<>());
        }
    }

    /**
     * 构建一个实物在各设备上的证据锚点。
     * 若该实物无任何直接凭证、但属于某总成内部件，则沿父链派生（递归，带循环保护）。
     */
    public PhysicalGraph buildForPhysical(long physicalId, AssemblyLinks links,
                                          OffsetDateTime asOfReceived) {
        Map<Long, List<EvidenceAnchor>> direct = loadDirectAnchors(physicalId, asOfReceived);
        if (!direct.isEmpty()) {
            return new PhysicalGraph(direct, new ArrayList<>());
        }
        // 无直接凭证：尝试总成派生
        return deriveFromAssembly(physicalId, links, asOfReceived, new HashSet<>());
    }

    /** 直接凭证：出厂基线 + 装拆签字 + 盘点。 */
    private Map<Long, List<EvidenceAnchor>> loadDirectAnchors(long physicalId, OffsetDateTime asOfReceived) {
        Map<Long, List<EvidenceAnchor>> byEquipment = new LinkedHashMap<>();

        // 出厂基线（无接收时间概念，始终为已知）
        for (Map<String, Object> f : db.query(
                "SELECT * FROM factory_record WHERE physical_part_id=? ORDER BY id", physicalId)) {
            long equipmentId = ((Number) f.get("equipment_id")).longValue();
            byEquipment.computeIfAbsent(equipmentId, k -> new ArrayList<>()).add(new EvidenceAnchor(
                    "EVNODE-FAC-" + f.get("id"), "FACTORY_RECORD", equipmentId,
                    f.get("mount_position_id") == null ? null : ((Number) f.get("mount_position_id")).longValue(),
                    physicalId, ((Number) f.get("part_model_id")).longValue(),
                    db.toOffsetDateTime(f.get("factory_time")), FAR_FUTURE,
                    "NECESSARY", "PRESENT", "factory_record", ((Number) f.get("id")).longValue(),
                    String.valueOf(f.get("source_ref")), String.valueOf(f.get("payload_digest")),
                    f.get("trust_score") == null ? null : ((Number) f.get("trust_score")).doubleValue(),
                    null, "出厂基线安装"));
        }

        // 装拆签字（按认知边界过滤迟到凭证）
        String eventSql = "SELECT * FROM install_event WHERE physical_part_id=?";
        List<Object> args = new ArrayList<>(List.of(physicalId));
        if (asOfReceived != null) {
            eventSql += " AND received_at<=?";
            args.add(asOfReceived);
        }
        eventSql += " ORDER BY event_time, id";
        for (Map<String, Object> e : db.query(eventSql, args.toArray())) {
            long equipmentId = ((Number) e.get("equipment_id")).longValue();
            boolean install = "INSTALL".equals(e.get("event_type"));
            OffsetDateTime t = db.toOffsetDateTime(e.get("event_time"));
            byEquipment.computeIfAbsent(equipmentId, k -> new ArrayList<>()).add(new EvidenceAnchor(
                    "EVNODE-EV-" + e.get("id"),
                    install ? "INSTALL_SIGN" : "REMOVE_SIGN", equipmentId,
                    ((Number) e.get("mount_position_id")).longValue(), physicalId,
                    ((Number) e.get("part_model_id")).longValue(),
                    t, install ? FAR_FUTURE : t,
                    "NECESSARY", install ? "PRESENT" : "ABSENT",
                    "install_event", ((Number) e.get("id")).longValue(),
                    String.valueOf(e.get("source_ref")), String.valueOf(e.get("payload_digest")),
                    ((Number) e.get("trust_score")).doubleValue(),
                    db.toOffsetDateTime(e.get("received_at")),
                    "FROZEN".equals(e.get("adjudication")) ? "凭证冻结待裁定" : null));
        }

        // 盘点凭证（PRESENT/ABSENT，区间证据；按接收边界过滤）
        String countSql = "SELECT * FROM inventory_count WHERE physical_part_id=?";
        List<Object> cargs = new ArrayList<>(List.of(physicalId));
        if (asOfReceived != null) {
            countSql += " AND received_at<=?";
            cargs.add(asOfReceived);
        }
        countSql += " ORDER BY count_time, id";
        for (Map<String, Object> c : db.query(countSql, cargs.toArray())) {
            long equipmentId = ((Number) c.get("equipment_id")).longValue();
            OffsetDateTime t = db.toOffsetDateTime(c.get("count_time"));
            byEquipment.computeIfAbsent(equipmentId, k -> new ArrayList<>()).add(new EvidenceAnchor(
                    "EVNODE-IC-" + c.get("id"), "INVENTORY_COUNT", equipmentId,
                    c.get("mount_position_id") == null ? null : ((Number) c.get("mount_position_id")).longValue(),
                    physicalId, c.get("part_model_id") == null ? null : ((Number) c.get("part_model_id")).longValue(),
                    t, t, "POSSIBLE", String.valueOf(c.get("observed")),
                    "inventory_count", ((Number) c.get("id")).longValue(),
                    String.valueOf(c.get("source_ref")), String.valueOf(c.get("payload_digest")),
                    ((Number) c.get("trust_score")).doubleValue(),
                    db.toOffsetDateTime(c.get("received_at")),
                    String.valueOf(c.getOrDefault("note", ""))));
        }
        return byEquipment;
    }

    /**
     * 总成派生：内部件 P 的安装断言 = 最近祖先总成的安装断言（整树迁移）。
     * 派生出的锚点编号带祖先与来源，论证图中以 DERIVED_ASSEMBLY 边连接，不产生独立装拆凭证。
     */
    private PhysicalGraph deriveFromAssembly(long physicalId, AssemblyLinks links,
                                             OffsetDateTime asOfReceived, Set<Long> guard) {
        Map<Long, List<EvidenceAnchor>> result = new LinkedHashMap<>();
        List<String[]> edges = new ArrayList<>();
        Long parentId = links.childToParent().get(physicalId);
        if (parentId == null || !guard.add(parentId)) {
            return new PhysicalGraph(result, edges);
        }
        Map<Long, List<EvidenceAnchor>> parentAnchors = loadDirectAnchors(parentId, asOfReceived);
        if (parentAnchors.isEmpty()) {
            // 父总成也是内部件：继续向上派生，并把更上层的派生边一并带回
            PhysicalGraph upstream = deriveFromAssembly(parentId, links, asOfReceived, guard);
            parentAnchors = upstream.anchorsByEquipment();
            edges.addAll(upstream.derivationEdges());
        }
        for (Map.Entry<Long, List<EvidenceAnchor>> entry : parentAnchors.entrySet()) {
            List<EvidenceAnchor> derived = new ArrayList<>();
            // 每条父锚点派生一条子件锚点：保留原始凭证节点类型（装拆签字/盘点），仅标记总成派生，
            // 时间断言完全继承（同一实物链，不会复制为两处）
            for (EvidenceAnchor pa : entry.getValue()) {
                String childNode = "EVNODE-ASM-P" + parentId + "-C" + physicalId + "-" + pa.nodeCode();
                derived.add(new EvidenceAnchor(
                        childNode, pa.nodeType(), entry.getKey(), pa.mountPositionId(),
                        physicalId, modelOfPhysical(physicalId),
                        pa.claimFrom(), pa.claimTo(), pa.certainty(), pa.observed(),
                        "part_assembly_link", parentId,
                        "总成派生:内部件 " + physicalId + " 随父总成 " + parentId + " / " + pa.sourceRef(),
                        pa.payloadDigest(), pa.trustScore(), pa.receivedAt(),
                        "嵌套总成转装派生，内部件无独立装拆凭证", true));
                edges.add(new String[]{pa.nodeCode(), childNode});
            }
            result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(derived);
        }
        return new PhysicalGraph(result, edges);
    }

    private Map<String, Object> physicalEntity(long physicalId) {
        Map<String, Object> e = db.queryOne("SELECT * FROM master_entity WHERE id=?", physicalId);
        return e == null ? Map.of("id", physicalId) : e;
    }

    private Long modelOfPhysical(long physicalId) {
        Map<String, Object> e = physicalEntity(physicalId);
        Map<String, Object> spec = db.toMap(e.get("spec_payload"));
        String modelCode = String.valueOf(spec.getOrDefault("modelCode", ""));
        Map<String, Object> model = db.queryOne(
                "SELECT id FROM master_entity WHERE entity_type='PART_MODEL' AND entity_code=?", modelCode);
        return model == null ? null : ((Number) model.get("id")).longValue();
    }
}
