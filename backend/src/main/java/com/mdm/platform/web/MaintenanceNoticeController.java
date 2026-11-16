package com.mdm.platform.web;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.service.notice.MaintenanceNotificationService;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 维修通知资格论证接口：
 * 对供应商条件 + 目标时间窗，输出“必然安装 / 可能安装 / 证据不足 / 可排除 / 矛盾待核”的设备集合，
 * 并附最小支持材料与独立支持路径。产出的是通知资格，不是受影响快照。
 */
@RestController
@RequestMapping("/maintenance")
public class MaintenanceNoticeController {

    private final MaintenanceNotificationService service;
    private final JdbcSupport db;

    public MaintenanceNoticeController(MaintenanceNotificationService service, JdbcSupport db) {
        this.service = service;
        this.db = db;
    }

    // ---------- 维修通知 ----------
    @GetMapping("/notices")
    public ApiResult<List<Map<String, Object>>> notices() {
        return ApiResult.ok(service.listNotices());
    }

    @PostMapping("/notices")
    public ApiResult<Map<String, Object>> createNotice(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(service.createNotice(body));
    }

    @GetMapping("/notices/{id}/runs")
    public ApiResult<List<Map<String, Object>>> runs(@PathVariable long id) {
        return ApiResult.ok(service.listRuns(id));
    }

    /**
     * 对通知做一次资格评估并落库。
     * 传 asOfReceived 可模拟认知边界（迟到凭证不参与）；不传则纳入当前全部已知凭证。
     * 重复评估只新增 run，历史 run 保留不改写（迟到凭证只产生补充名单）。
     */
    @PostMapping("/notices/{id}/evaluate")
    public ApiResult<Map<String, Object>> evaluate(@PathVariable long id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        OffsetDateTime asOf = null;
        if (body != null && body.get("asOfReceived") != null
                && !String.valueOf(body.get("asOfReceived")).isBlank()) {
            asOf = OffsetDateTime.parse(String.valueOf(body.get("asOfReceived")));
        }
        return ApiResult.ok(service.evaluateNotice(id, asOf));
    }

    @GetMapping("/runs/{id}")
    public ApiResult<Map<String, Object>> runDetail(@PathVariable long id) {
        return ApiResult.ok(service.runDetail(id));
    }

    /** 即时试算（不落库）：直接给供应商条件 + 时间窗，返回四档集合与证据。 */
    @PostMapping("/evaluate-adhoc")
    public ApiResult<Map<String, Object>> evaluateAdHoc(@RequestBody Map<String, Object> body) {
        Long supplierId = body.get("supplierId") == null || String.valueOf(body.get("supplierId")).isBlank()
                ? null : Long.parseLong(String.valueOf(body.get("supplierId")));
        Long targetModelId = body.get("targetModelId") == null || String.valueOf(body.get("targetModelId")).isBlank()
                ? null : Long.parseLong(String.valueOf(body.get("targetModelId")));
        OffsetDateTime ws = OffsetDateTime.parse(String.valueOf(body.get("windowStart")));
        OffsetDateTime we = OffsetDateTime.parse(String.valueOf(body.get("windowEnd")));
        OffsetDateTime asOf = body.get("asOfReceived") == null || String.valueOf(body.get("asOfReceived")).isBlank()
                ? null : OffsetDateTime.parse(String.valueOf(body.get("asOfReceived")));
        return ApiResult.ok(service.evaluateAdHoc(
                new MaintenanceNotificationService.NoticeCondition(supplierId, targetModelId, ws, we, asOf)));
    }

    // ---------- 盘点凭证与总成层级（只读浏览） ----------
    @GetMapping("/inventory-counts")
    public ApiResult<List<Map<String, Object>>> inventoryCounts(
            @RequestParam(required = false) Long equipmentId) {
        String sql = "SELECT ic.*, eq.entity_code AS equipment_code, pp.entity_code AS physical_code, " +
                "pm.entity_code AS model_code, pos.entity_code AS position_code " +
                "FROM inventory_count ic " +
                "JOIN master_entity eq ON eq.id=ic.equipment_id " +
                "LEFT JOIN master_entity pp ON pp.id=ic.physical_part_id " +
                "LEFT JOIN master_entity pm ON pm.id=ic.part_model_id " +
                "LEFT JOIN master_entity pos ON pos.id=ic.mount_position_id ";
        if (equipmentId == null) {
            return ApiResult.ok(db.query(sql + " ORDER BY ic.count_time, ic.id"));
        }
        return ApiResult.ok(db.query(sql + " WHERE ic.equipment_id=? ORDER BY ic.count_time, ic.id", equipmentId));
    }

    @GetMapping("/assembly-links")
    public ApiResult<List<Map<String, Object>>> assemblyLinks() {
        return ApiResult.ok(db.query(
                "SELECT l.*, p.entity_code AS parent_code, c.entity_code AS child_code " +
                "FROM part_assembly_link l " +
                "JOIN master_entity p ON p.id=l.parent_physical_id " +
                "JOIN master_entity c ON c.id=l.child_physical_id ORDER BY l.id"));
    }
}
