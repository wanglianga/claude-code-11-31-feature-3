# 维修配置与备件兼容主数据血缘核验平台

面向设备制造企业的**配置主数据 / 血缘 / 兼容性事实 / 质量事件 / 版本化查询**平台。
解决"系统直接覆盖最新版 BOM，导致同一序列号在不同报表被解释成两种配置"的问题：
平台采用**版本化快照 + 追加式实装事件**，长期保留来源文件、版本索引与重放计算，
查询必须显式选择时点，已被引用的历史报告不会被静默改写。

技术栈：Vue 3 + Vite + Element Plus + Pinia ｜ Java 25 + Spring Boot 3.5 + Spring JDBC ｜ PostgreSQL 18。

---

## 一、平台能力与边界

### 交付的能力（本平台职责）
| 模块 | 职责 |
| --- | --- |
| 资料接入契约库 | 登记图号、供应商规则、实装凭证、检验数据的字段、来源、版本号、生效边界与来源可信度，并保留来源文件指纹与版本索引 |
| 实体消歧器 | 部件型号 / 实物序列号 / 装配位置 / 设备序列号 / 供应商编码**五类分开建模**；名称相似只产生消歧候选，**绝不自动合并** |
| 配置快照编译器 | 按指定维修时点重放出厂记录 + 追加式拆装凭证，汇集来源可信度，生成**位置级不可变快照**与重放指纹 |
| 规则血缘解析器 | 把替代方向、联换条件、协议/尺寸/电源约束连回图号修订、测试报告与生效版本 |
| 兼容事实求值器 | 对给定快照输出 **通过 / 不通过 / 证据不足** 及**最小证据链** |
| 影响分析器 | 规则、图号、实装记录变更后定位受影响快照、历史结论与下游数据集 |
| 发布闸门 | 草稿 / 已验证 / 待复核 / 已废止分通道对外，冲突未裁定或非 PASS 结论禁止发布 |
| 维修通知资格论证器 | 对供应商条件+目标时间窗，按实际安装证据输出**必然安装/可能安装/证据不足/可排除（矛盾只能待核）**设备集合与最小支持材料；区间求交+证据论证图双方法，嵌套总成转装不复制内部件，迟到凭证只产生补充名单 |

### 明确不做的事（外部业务系统职责）
不生成**领料单、维修工序、库存调拨、采购决策、工位排程**。平台只产出可回放的事实与证据。

### 三条关键业务纪律（已在代码中固化）
1. **序列号区间重叠**：两张图号都声称对同一序列号生效时，编译器产生冲突事实并冻结快照、阻止兼容发布，**不默认采用最新上传文件**。
2. **同一实物重叠时间出现在两台设备**：实体消歧器冻结相关实物/位置并要求凭证裁定，**不按接收顺序覆盖**。
3. **供应商收回替代规则**：影响分析器仅让引用该规则的快照/事实进入待复核；**已签发结论保留当时锁定版本，另发更正事实**，历史报告不改写。

---

## 二、目录结构

```
31/
├── docker-compose.yml          # 一键编排：postgres + backend + frontend(nginx)
├── backend/                    # Spring Boot 工程
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   ├── application.yml
│       │   └── db/schema.sql   # 全部表结构
│       │       db/seed.sql     # 五大场景 + 维修通知四类验收基础主数据
│       └── java/com/mdm/platform/
│           ├── config/         # 初始化器、Jackson/PG 序列化
│           ├── security/       # 令牌认证、角色拦截
│           ├── repository/     # JdbcSupport
│           ├── service/        # 核心模块逻辑
│           │   └── notice/     # 维修通知资格：区间引擎、证据论证图、资格服务
│           └── web/            # REST 控制器
└── frontend/                   # Vue3 工程
    ├── Dockerfile
    ├── nginx.conf
    └── src/views/              # 11 个业务页面（含“维修通知资格论证”）
```

---

## 三、本地启动

### 方式 A：Docker Compose 一键启动（推荐，需本机有 Docker）

```bash
cd /workspace/31
docker compose up --build
```

- 前端：http://localhost:8080
- 后端 API：http://localhost:18080/api
- PostgreSQL：localhost:5432（库/用户/密码 `ccdev`）
- 后端首次启动自动建表、灌入种子数据，并按真实服务逻辑重放五大场景生成派生数据。

> 重置演示数据：`RESET_ON_START=true docker compose up backend`（或在库中 drop schema 后重启）。

### 方式 B：本地运行时开发启动（本仓库实际验证方式）

前置：JDK 25、Maven 3.9、Node 22、PostgreSQL 18（库 `ccdev`，账号/密码 `ccdev`）。

```bash
# 1. 后端（默认连 127.0.0.1:5432/ccdev，账号密码 ccdev）
cd backend
mvn -DskipTests package
DB_PASSWORD=ccdev java -jar target/mdm-platform.jar
# 服务监听 http://127.0.0.1:8080/api ，启动即自动建表+种子+场景重放

# 2. 前端（新终端）
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev
# 打开 http://127.0.0.1:5173 ，vite 已配置 /api 代理到 8080
```

生产模式前端：`npm run build` 后用任意静态服务器托管 `dist/`（容器内用 nginx，配置见 `frontend/nginx.conf`）。

---

## 四、测试账号

| 角色 | 账号 | 密码 | 权限 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部读写、发布闸门、裁定 |
| 工程师 | `engineer` | `eng123` | 业务数据读写、编译、求值（无系统管理限制外的写权限） |
| 只读查询 | `viewer` | `view123` | 仅查询，写操作返回 403 |

密码以 `SHA-256("mdm-salt::" + 明文)` 存储于 `app_user.password_hash`。

---

## 五、五大样例场景与验证方式

样例叙事数据见 `backend/src/main/resources/db/seed.sql`，派生快照/事实/冲突/影响/质量事件由
`DemoDataInitializer` 启动时调用**真实服务逻辑**重放生成，因此每个结论都能在接口与页面回放。

| 场景 | 数据抓手 | 预期可观察结果 |
| --- | --- | --- |
| **A 跨修订序列号重叠** | 图号 `DRW-MOTOR-100` revA(`SN-0001~0020`) 与 revB(`SN-0015~0030`) 重叠；设备 `EQ-CNC-2024-0018`(SN-0018) | 快照 `SNP-0018-*` `has_conflict=true/status=FROZEN`，产生 `DRAWING_RANGE_OVERLAP` 冲突事实；对其发布兼容事实被闸门拦截 |
| **B 迟到拆装凭证** | 事件 `EV-INST-003`：冷却泵安装事件时间 `2024-08-20`，接收时间 `2025-03-01` | 同一设备同一维修时点编译出 `SNP-0007-20241201-v1`（接收边界前，冷却位 VACANT）与 `v2`（纳入迟到凭证，冷却位已装）；旧快照保留不改写，并产生 `LATE_VOUCHER` 质量事件 |
| **C 同名异件** | 三个"伺服驱动单元" `PM-SDU-A1`(EtherCAT/380V)、`PM-SDU-B2`(CANopen/220V)、`PM-SDU-C3` | 实体消歧页有 PENDING 候选，仅能人工 `KEEP_DISTINCT`；系统无任何自动合并入口 |
| **D 规则撤回** | 规则 `SUB-RULE-001`（A1→C3）被撤回 | 已发布事实保留 `rule_version_locked=v1`，另生成 `supersedes` 更正事实；引用快照进入 REVIEW；产生 `RULE_RETIRE`/`FACT_CORRECTION` 事件；历史时点重评仍 PASS 且标注规则已撤回 |
| **E 两约束联动** | 电源规则 `SUB-RULE-002`(电压380V+尺寸2U) / `SUB-RULE-003`(电压380V+尺寸2U，候选 `PM-PWR-502` 实际尺寸3U) | `500→501` 判 **PASS**；`500→502` 因尺寸约束失败判 **FAIL**；证据链逐项列出两条约束的 expected/actual/passed |

附加：实物 `PP-PUMP-0001` 在重叠时间段分别安装于设备 0007/0008，触发 `PHYSICAL_PART_DOUBLE_MOUNT`，
实物与相关位置被冻结，需在"实体消歧"页做凭证裁定。

### 建议的页面核验路径
1. 用 `admin` 登录 → **核验总览**看统计与场景导航。
2. **快照与时点查询**：查看 `SNP-0018`（冲突冻结）、`SNP-0007` 的 v1/v2 版本差异，打开详情看重放指纹与位置事实。
3. **兼容与发布闸门**：选快照+位置+候选型号求值，展开最小证据链；尝试发布冲突快照上的事实，观察被拦截。
4. **规则与血缘**：打开 `SUB-RULE-001` 血缘抽屉（图号修订/测试报告/引用事实），点"撤回"观察影响。
5. **影响分析 / 质量事件 / 实体消歧**：复核待复核清单、更正链与凭证裁定。

### 主要 REST 接口（均需 `Authorization: Bearer <token>`，登录接口除外）
```
POST /api/auth/login
GET  /api/contracts | /api/contracts/versions
GET  /api/entities?entityType=&keyword= | /api/entities/alias-candidates | /api/entities/conflicts
POST /api/entities/alias-candidates/{id}/decide | /api/entities/conflicts/{id}/adjudicate
GET  /api/drawings | /api/drawing-revisions | /api/inspections | /api/factory-records
GET  /api/install-events | POST /api/install-events
GET  /api/rules | /api/rules/{id}/lineage | POST /api/rules/{id}/retire
GET  /api/constraints
POST /api/snapshots/compile | GET /api/snapshots | GET /api/snapshots/{id}
GET  /api/equipment/{id}/as-of?pointInTime=
POST /api/compatibility/evaluate | GET /api/compatibility/facts-all
POST /api/facts/{id}/gate | GET /api/facts/public | GET /api/release/audit-logs
POST /api/impact/rule/{id} | /api/impact/drawing-revision/{id} | /api/impact/install-event/{id}
GET  /api/impact | GET /api/quality-events | POST /api/quality-events/{id}/close
# 维修通知资格论证
GET  /api/maintenance/notices | POST /api/maintenance/notices
GET  /api/maintenance/notices/{id}/runs | POST /api/maintenance/notices/{id}/evaluate
GET  /api/maintenance/runs/{id} | POST /api/maintenance/evaluate-adhoc
GET  /api/maintenance/inventory-counts | /api/maintenance/assembly-links
```

---

## 五·补、维修通知资格论证（供应商维修通知 → 设备实际安装证据 → 通知资格）

供应商发出维修通知时，**名单不能仅由“历史快照出现过某型号”决定**：服务负责人必须判断每台设备在目标时段是否
具备足够的实际安装证据。本平台新增**维修通知资格论证器**，对一个供应商条件（供应商+目标型号）与目标时间窗，
输出每台设备的资格四档与最小支持材料。

### 资格四档 + 待核
| 资格 | 判定依据 |
| --- | --- |
| **必然安装 CERTAIN** | 已证实安装（必要）区间与目标窗相交；装拆时点精确（装拆签字/出厂基线），窗内有闭合证据 |
| **可能安装 POSSIBLE** | 仅盘点扩张出的可能区间与目标窗相交；装/拆时点无精确凭证，稀疏凭证留下较宽可能集（含缺少拆除单的开放端） |
| **证据不足 INSUFFICIENT** | 目标时段没有任何与目标型号相关的在装/缺席凭证 |
| **可排除 EXCLUDED** | 已证实缺席区间覆盖目标窗，或两次独立缺席盘点且从无在装主张 |
| **矛盾待核 PENDING_REVIEW** | 在装主张与相反盘点/已证实缺席相互矛盾，平台不做资格裁定，只能待核 |

### 两种方法都已落地，产出的是“通知资格”而非受影响快照
1. **必要/可能时间区间求交**：把每件实物的出厂基线、装拆签字、盘点转成必要（NECESSARY）/可能（POSSIBLE）
   安装区间与必要缺席区间，再与目标窗 `[start,end)` 求交。精确装拆给清晰边界；盘点只给两次盘点之间的区间，
   稀疏凭证自然留下较宽可能集。
2. **证据论证图**：维修单/装拆签字/盘点/出厂/总成层级统一为论证节点（`evidence_node`），节点间以
   支持/矛盾/总成派生/界定边（`evidence_edge`）连接；一条设备归属结论必须有至少一条**独立支持路径**
   （`evidence_support_path`），证据缺口可显式表达，论证规则可回放。

### 三条关键纪律（已在代码固化）
- **矛盾只能待核**：在装签字与“盘到空位”相反盘点冲突时判 `PENDING_REVIEW`，不猜倾向、不纳入必然名单，并登记质量事件。
- **嵌套总成转装不复制内部件**：内部件无独立装拆凭证时，沿 `part_assembly_link` 从父总成派生安装断言（节点带
  `assemblyDerived` 标记、以 `DERIVED_ASSEMBLY` 边连接），内部件随整树迁移，绝不会在两台设备被复制为两处。
- **迟到凭证只产生补充名单**：评估支持 `asOfReceived` 认知边界，边界后接收的凭证不参与当时论证；再次评估只新增
  一个 `notice_eligibility_run`，历史批次保留不改写。缺少拆除单时，开放结尾的安装主张降级为可能区间。

### 四类验收样例（种子数据 + 启动重放，目标窗统一 2025-01-01 ~ 2025-07-01）
| 场景 | 数据抓手 | 预期资格 |
| --- | --- | --- |
| **F 拆装时间缺口** | 冷却器 2024-11 拆、2025-03 装回（装回凭证 2025-05 才接收） | 认知边界批次 EQ0101=可排除；全量批次 EQ0101=必然安装；窗前已拆未复装设备保持可排除 |
| **G 相反盘点 / 独立缺席盘点** | 液压件在装签字 vs 2025-02 盘到空位；另一位置两次独立缺席盘点 | 矛盾设备=待核；双缺席且无在装主张设备=可排除；窗内在装盘点设备=必然 |
| **H 嵌套总成转装** | 齿轮箱总成（含两个轴承）2025-02 从 EQ0103 转装 EQ0106 | 父总成与内部轴承在两设备按区间正确定档；内部件证据为总成派生、节点不重复 |
| **I 缺少拆除单** | 辅助驱动 2024-08 安装签字 + 一次在装盘点，无拆除单 | 开放端可能集覆盖目标窗 → 可能安装（`openEnd=true`），不夸大为必然 |

页面核验：左侧菜单 **“维修通知资格”**，可对通知做全量/认知边界评估、查看四档设备集合、展开最小支持材料、
独立支持路径与总成派生边；也可用“即时试算”不落库直接给供应商条件+时间窗。

---

## 六、版本化与不可篡改是怎么落地的

- `install_event` **只追加**：无 update/delete 接口，凭证号唯一；事件时间与接收时间分离，迟到凭证靠接收边界识别。
- `config_snapshot` / `snapshot_position` **只插入不更新**：每次编译产生新版本号，保存 `source_event_ids`、`source_factory_ids` 与 `replay_digest`（重放指纹）。
- 兼容性事实带 `rule_version_locked`；规则撤回后原事实不被改写，更正通过新事实 + `supersedes_fact_id` 串联。
- 所有来源记录保存 `source_ref` + `payload_digest`（文件指纹），契约版本索引长期保留。

---

## 七、实际执行过的验证结论

以下为在本隔离环境**实际执行**并通过的验证（截至交付时）：

1. `mvn -DskipTests package` 成功，产出 `backend/target/mdm-platform.jar`（Spring Boot 3.5.16 / JDK 25）。
2. `npm run build` 成功，产出 `frontend/dist`（Vite + Vue3 生产包，含“维修通知资格论证”页面）。
3. 后端连接本地 PostgreSQL 18 成功，自动建表、灌种子并重放全部场景：启动日志确认生成 **5 个配置快照、6 条兼容事实、6 条质量事件**，以及维修通知 **5 条通知、6 个评估批次、54 条资格结论、28 个论证节点、21 条独立支持路径**，且
   - 场景 A 冲突冻结 + 发布闸门拦截成功；
   - 场景 B v1/v2 版本差异正确（v1 无冷却位证据、v2 纳入迟到凭证后出现冷却泵）；
   - 场景 E 伺服/电源 PASS、502 尺寸 FAIL；
   - 场景 D 撤回后历史结论保留锁定版本并生成更正链；
   - 维修通知场景 F/G/H/I 四档资格与矛盾待核定档正确（见“五·补”章）。
4. REST 冒烟通过：登录鉴权（错误密码 HTTP 401）、VIEWER 写操作返回 403、版本化时点查询、规则血缘、证据链 jsonb 原生输出、`facts-all`/`facts/public`、维修通知评估/批次/即时试算数据正确。
5. 前端 `vite build` 编译无错误，11 个页面与路由、角色按钮禁用均已接线。
6. 单元测试：`InstallationIntervalEngineTest`（区间引擎四档定档，7 项）连同既有 `SnapshotServiceTest`/`DigestUtilTest` 共 12 项全部通过。
7. 端到端断言脚本 `backend/verify_e2e.py`（仅依赖 Python 标准库，对运行中的服务做 **37 项**断言，含维修通知资格 16 项）全部通过，可重复执行：
   ```bash
   # 先启动后端，再运行
   python3 backend/verify_e2e.py
   ```

**未在本环境执行的验证**：当前容器未安装 Docker，故 `docker compose up --build` 的容器化一键启动**未在本环境实跑**；Dockerfile/compose 已按多阶段构建与官方基础镜像编写，需在具备 Docker 的环境执行确认。

---

## 八、配置项（application.yml / 环境变量）

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` | 数据库连接 |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` | 数据库账号 |
| `AUTO_INIT` | `true` | 启动自动建表+种子+场景重放 |
| `RESET_ON_START` | `false` | 置 `true` 启动时清空重建演示数据 |
