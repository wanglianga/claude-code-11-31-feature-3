#!/usr/bin/env python3
"""端到端核验脚本：对运行中的后端做五大场景断言。用法: python3 verify_e2e.py"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
PASS, FAIL = [], []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    else:
        data = None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")


# 登录
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
token = r["data"]["token"]
check("管理员登录", st == 200 and r["data"]["role"] == "ADMIN")
st, r = call("POST", "/auth/login", body={"username": "viewer", "password": "view123"})
viewer = r["data"]["token"]
check("只读角色登录", r["data"]["role"] == "VIEWER")
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "wrong"})
check("错误密码拒绝(401)", st == 401)
st, _ = call("POST", "/snapshots/compile", token=viewer,
             body={"equipmentId": 10, "pointInTime": "2024-10-01T00:00:00Z"})
check("只读角色写操作拒绝(403)", st == 403, f"http={st}")

# 场景 A：图号区间重叠
_, r = call("GET", "/entities/conflicts", token)
conflicts = r["data"]
draw = [c for c in conflicts if c["conflict_type"] == "DRAWING_RANGE_OVERLAP"]
check("场景A 图号重叠冲突存在且 OPEN", bool(draw) and draw[0]["resolution_status"] == "OPEN"
      and draw[0]["blocks_release"] is True)
_, r = call("GET", "/snapshots", token)
snaps = r["data"]
s18 = [s for s in snaps if "0018" in s["snapshot_no"]][0]
check("场景A 设备0018 快照 FROZEN+冲突", s18["status"] == "FROZEN" and s18["has_conflict"] is True)
# 闸门拦截
_, r = call("GET", f"/snapshots/{s18['id']}/facts", token)
blocked_fact = r["data"][0]
st, r = call("POST", f"/facts/{blocked_fact['id']}/gate", token,
             {"action": "publish", "reason": "测试拦截"})
check("场景A 冲突快照发布被闸门拦截", st == 409 and "冲突" in r["message"], r.get("message", ""))

# 场景 B：迟到凭证 v1/v2
v12 = [s for s in snaps if "20241201" in s["snapshot_no"]]
check("场景B 同一时点存在 v1/v2 两版本", len(v12) == 2)
_, r = call("GET", f"/snapshots/{sorted(v12, key=lambda x: x['id'])[0]['id']}", token)
pos = r["data"]["positions"]
coolant = [p for p in pos if p["position_code"] == "POS-COOLANT"]
# v1 在"接收边界"前没有任何冷却位证据 -> 该位置不应出现在快照中（无证据不虚构）
check("场景B v1 无冷却位证据（迟到凭证未纳入，位置缺失）", len(coolant) == 0)
_, r2 = call("GET", f"/snapshots/{sorted(v12, key=lambda x: x['id'])[1]['id']}", token)
coolant2 = [p for p in r2["data"]["positions"] if p["position_code"] == "POS-COOLANT"]
check("场景B v2 纳入迟到凭证后冷却位出现冷却泵",
      bool(coolant2) and coolant2[0]["model_code"] == "PM-PUMP-200")
_, r = call("GET", "/quality-events", token)
check("场景B 产生 LATE_VOUCHER 质量事件",
      any(q["event_type"] == "LATE_VOUCHER" for q in r["data"]))

# 场景 C：同名异件不自动合并
_, r = call("GET", "/entities/alias-candidates", token)
check("场景C 消歧候选全部 PENDING（无自动合并）",
      len(r["data"]) >= 2 and all(c["decision"] == "PENDING" for c in r["data"]))

# 场景 D：规则撤回 + 更正链
_, r = call("GET", "/rules", token)
rule1 = [x for x in r["data"] if x["rule_code"] == "SUB-RULE-001"][0]
check("场景D SUB-RULE-001 已撤回", rule1["status"] == "RETIRED" and rule1["effective_to"] is not None)
_, r = call("GET", "/compatibility/facts-all", token)
facts = r["data"]
verified = [f for f in facts if f["status"] == "VERIFIED"]
check("场景D 已签发结论保留锁定规则版本",
      bool(verified) and all(f["rule_version_locked"] == "v1" for f in verified))
correction = [f for f in facts if f.get("supersedes_fact_id")]
check("场景D 存在 supersedes 更正事实", bool(correction) and correction[0]["status"] == "REVIEW")

# 场景 E：两约束联动
verdicts = {f["fact_no"]: f["verdict"] for f in facts}
check("场景E 存在 PASS 结论", any(v == "PASS" for v in verdicts.values()))
fail_fact = [f for f in facts if f["verdict"] == "FAIL"][0]
check("场景E FAIL 事实含 1 项未通过约束（尺寸联动）",
      len(fail_fact["failed_constraints"]) == 1
      and fail_fact["failed_constraints"][0]["constraintType"] == "DIMENSION")
check("场景E 证据链含规则血缘+约束校验+快照位置",
      {"RULE_LINEAGE", "CONSTRAINT_CHECK", "SNAPSHOT_POSITION"}
      <= {e["step"] for e in fail_fact["evidence_chain"]})

# 双重安装冻结
double = [c for c in conflicts if c["conflict_type"] == "PHYSICAL_PART_DOUBLE_MOUNT"]
check("附加 双重安装冲突 OPEN 待裁定", bool(double) and double[0]["resolution_status"] == "OPEN")

# 对外发布通道
_, r = call("GET", "/facts/public", token)
pub = r["data"]
check("对外通道仅 VERIFIED+PASS",
      all(f["status"] == "VERIFIED" and f["verdict"] == "PASS" for f in pub) and len(pub) >= 1)

# 凭证裁定后冲突解除（写操作，验证裁定闭环）
cid = double[0]["id"]
st, r = call("POST", f"/entities/conflicts/{cid}/adjudicate", token,
             {"resolutionNote": "E2E 核验裁定：以现场签收凭证为准", "keepFirstVoucher": True})
check("凭证裁定闭环", st == 200 and r["data"]["resolution_status"] == "ADJUDICATED")

# =====================================================================
# 维修通知资格论证（四类验收 + 迟到补充名单 + 总成不复制）
# =====================================================================
_, r = call("GET", "/maintenance/notices", token)
notices = {n["notice_code"]: n for n in r["data"]}
check("维修通知清单含 5 条通知", len(notices) == 5, str(sorted(notices)))

def run_detail(notice_id):
    _, rr = call("GET", f"/maintenance/notices/{notice_id}/runs", token)
    run_ids = [x["id"] for x in rr["data"]]
    out = {}
    for rid in run_ids:
        _, d = call("GET", f"/maintenance/runs/{rid}", token)
        d = d["data"]
        for el in d["eligibilities"]:
            out[(rid, el["equipment_code"])] = el
    return rr["data"], out

# 场景 F：拆装时间缺口 + 迟到凭证只产生补充名单
n1_runs, n1 = run_detail(notices["NTC-2025-001"]["id"])
check("场景F 冷却器通知有 2 个评估批次（认知边界 + 全量）", len(n1_runs) == 2)
r1, r2 = n1_runs[0]["id"], n1_runs[1]["id"]
check("场景F 首次评估（迟到装回未纳入）EQ0101 可排除",
      n1[(r1, "EQ-CNC-2025-0101")]["qualification"] == "EXCLUDED")
check("场景F 二次评估（迟到凭证纳入）EQ0101 必然安装，且首次批次保留不改写",
      n1[(r2, "EQ-CNC-2025-0101")]["qualification"] == "CERTAIN"
      and n1[(r1, "EQ-CNC-2025-0101")]["qualification"] == "EXCLUDED")
check("场景F 必然资格附最小支持材料（装回签字凭证）",
      any(e.get("sourceRef") == "VCH-LATE-20250315"
          for e in n1[(r2, "EQ-CNC-2025-0101")]["minimal_evidence"]))
# 窗内已拆除未复装的设备保持可排除（边界清晰）
check("场景F EQ0106 冷却器窗前已拆 -> 可排除",
      n1[(r2, "EQ-CNC-2025-0106")]["qualification"] == "EXCLUDED")

# 场景 G：相反盘点矛盾待核 + 独立缺席盘点可排除
_, n2 = run_detail(notices["NTC-2025-002"]["id"])
g_run = list({k[0] for k in n2})[0]
review = n2[(g_run, "EQ-CNC-2025-0102")]
check("场景G 在装签字与盘到空位矛盾 -> 待核",
      review["qualification"] == "PENDING_REVIEW" and len(review["contradiction"]) >= 1)
excluded = n2[(g_run, "EQ-CNC-2025-0104")]
check("场景G 两次独立缺席盘点且从无在装主张 -> 可排除",
      excluded["qualification"] == "EXCLUDED" and len(excluded["minimal_evidence"]) >= 2)
check("场景G 窗内有在装盘点的液压设备 -> 必然安装",
      n2[(g_run, "EQ-CNC-2025-0106")]["qualification"] == "CERTAIN")

# 场景 H：嵌套总成转装（父总成 + 内部件随树迁移，内部件不复制为两处）
_, n3 = run_detail(notices["NTC-2025-003"]["id"])
h_run = list({k[0] for k in n3})[0]
check("场景H 齿轮箱总成转装：原设备与目标设备均能按区间定档",
      n3[(h_run, "EQ-CNC-2025-0103")]["qualification"] == "CERTAIN"
      and n3[(h_run, "EQ-CNC-2025-0106")]["qualification"] == "CERTAIN")
_, n4 = run_detail(notices["NTC-2025-004"]["id"])
b_run = list({k[0] for k in n4})[0]
bearing_target = n4[(b_run, "EQ-CNC-2025-0106")]
check("场景H 内部轴承经总成派生 -> 必然安装",
      bearing_target["qualification"] == "CERTAIN")
derived = [e for e in bearing_target["minimal_evidence"] if e.get("assemblyDerived")]
check("场景H 内部件证据带总成派生标记且无独立装拆凭证", len(derived) >= 1)
# 不复制为两处：同一设备同型号资格只有一行，且最小材料节点编号不重复
codes = [e["nodeCode"] for e in bearing_target["minimal_evidence"]]
check("场景H 单设备最小材料节点不重复（内部件未复制为两处）", len(codes) == len(set(codes)))

# 场景 I：缺少拆除单 -> 开放端可能集 -> 可能安装
_, n5 = run_detail(notices["NTC-2025-005"]["id"])
i_run = list({k[0] for k in n5})[0]
possible = n5[(i_run, "EQ-CNC-2025-0104")]
check("场景I 仅安装签字+在装盘点且无拆除单 -> 可能安装（开放端可能集）",
      possible["qualification"] == "POSSIBLE"
      and any(iv.get("openEnd") for iv in possible["possible_intervals"]))

# 即时试算（不落库）返回完整五档计数
st, r = call("POST", "/maintenance/evaluate-adhoc", token, {
    "targetModelId": 131,
    "windowStart": "2025-01-01T00:00:00Z",
    "windowEnd": "2025-07-01T00:00:00Z"})
check("即时试算返回五档计数", st == 200 and set(r["data"]["counts"]) ==
      {"CERTAIN", "POSSIBLE", "INSUFFICIENT", "EXCLUDED", "PENDING_REVIEW"})

# 只读角色不能触发评估（403）
st, _ = call("POST", f"/maintenance/notices/{notices['NTC-2025-001']['id']}/evaluate",
             viewer, {})
check("只读角色维修通知评估拒绝(403)", st == 403, f"http={st}")

print("\n================ 汇总 ================")
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项:", FAIL)
    raise SystemExit(1)
print("全部端到端断言通过 ✅")
