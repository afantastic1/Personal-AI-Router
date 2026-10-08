 

# PAIR Android M12.5 完整工程施工规范（V2.1）

**主题：MNN 专用 Model Hub、双向配对幂等、运行时稳定性、路由验收与代码质量工程化**

| 属性     | 内容                                                                                             |
| -------- | ------------------------------------------------------------------------------------------------ |
| 仓库     | `https://github.com/afantastic1/Personal-AI-Router`                                            |
| 工作分支 | `feature/android-build`                                                                        |
| 审查基线 | `10a7c013dfbcec2f7dfbd1c02c08b37a754a00e1`（M12，2026-10-08 检查时）                           |
| 计划版本 | `M12.5 / 修订 V2.1`                                                                            |
| 实施方式 | 供 AI 编码代理分阶段、分提交执行                                                                 |
| 首要验收 | A 配对 B 一次即可；任意一方停止/重启 PAIR 后无须重新配对；MNN 模型可搜索→安装→推理→跨设备路由 |
| 阶段外   | 张量/层级分布式联合推理、GGUF/ONNX 推理、新聊天 App、公开的 LAN API、账号系统                    |

> **文档效力**：本文件是施工指导，不是“未经执行的测试报告”。代码代理必须先核验基线和现有接口，不得在未查看调用者时照抄示例。示例代码表达数据契约和逻辑规则；真实函数名、锁序和返回结构以代码仓库检查结果为准。不得借“修复”之名大规模重写 PAIR 已有安全协议。

---

## 0. 给 AI 编码代理的总指令

将以下内容直接作为本阶段所有编码任务的上级约束：

1. **理解先于修改**：先绘制现有调用链，标记现有类型、RPC 名称、状态流、持久化目录和测试，再实现补丁。
2. **小步、可回滚**：每个提交只承担一个相对独立的责任；接口变更先更新测试和所有调用者；不得在一次提交中同时移动大量无关文件。
3. **唯一事实来源**：Go `nvpair-cluster-manager` 拥有成员与信任的安全事实；Kotlin UI 只读投影。MNN 是否可运行最终以 `MnnModelManager.resolve` 为准。Go gateway 是最终路由决策者。
4. **不绕过安全边界**：不能因 UI 显示 `trusted=true`、IP 相同、名称相同或远端声称“已配对”就生成本地可信凭证；不能静默覆盖证书、更换 clusterId 或绕过撤销证明。
5. **保证可读性**：业务函数尽量单一职责，优先明确的数据类型和枚举，禁止魔法字符串、超长 Composable / Service 方法、重复 package 判断和无上下文异常吞噬。文档注释写“为什么”，不是重述“做什么”。
6. **失败关闭**：网络、磁盘、身份、校验、加锁、RPC、生命周期遇到不确定状态，保持现有可信数据不变、避免重复请求和部分成功；展示可诊断错误。
7. **禁止伪造通过**：只能把确实运行的测试标记 PASS。无法连接真机、PC 或公网时明确标记 `NOT RUN` 并提供准确运行命令；不以 mock 证明实机链路通过。
8. **保护用户工作树**：不得自动 `git reset --hard`、`git clean -fdx`、覆盖未提交文件、修改 secrets、上传模型权重或 APK 到 Git。
9. **限制变更范围**：不得将 EdgeMesh 的 C++ ModelHub 整套复制到 PAIR；PAIR 已有 Kotlin Model Hub，继续保持 Kotlin Provider/Installer 与 Go router 分层。
10. **持续交付**：每一提交输出变更文件、设计依据、测试命令/结果、剩余风险、是否满足提交门禁；不过门禁不得继续下一阶段。

### 0.1 开工前必须输出的基线审计

```powershell
# 在仓库根目录执行
git branch --show-current
git rev-parse HEAD
git status --short
git log -5 --oneline

# Android 构建入口
Get-Content android/app/build.gradle.kts | Select-Object -First 65

# 当前代码检索（如安装 ripgrep）
rg 'cluster:leave|ACTION_STOP|cluster:invite-node|trusted|M65AndroidToPc' android services
rg 'PageNumber|PageSize|author=|owner=|requiredArtifacts|sha256' android/app/src/main/java
```

审计表必须记录：文件路径、现有行为、拟改动、调用链、测试位置、风险。若 HEAD 已不同于上述 M12，**以实际代码为准重新形成差异清单**，不机械修改已经修复过的功能。

---

# 第一部分：事实基线、需求和约束

## 1. 现有架构（保留）

```text
Android UI (Compose)
      │
      ├── PairRuntimeController
      │         │
      │         ▼
      │   PairRuntimeService (FGS)
      │         │
      │         ├── BrokerSession → Go nvpair-ui-broker
      │         │           ├── nvpair-cluster-manager（身份/信任/成员）
      │         │           ├── discovery、scheduler、workload-manager
      │         │           ├── engine-manager
      │         │           └── nvpair-proxy / gateway
      │         │
      │         └── MnnRuntimeContainer（Android app 持有）
      │                     ├── MnnModelManager / MnnModelCatalog
      │                     ├── MnnInferenceService / MnnEngineHost
      │                     └── MnnHttpServer :14325
      │
      └── ModelHubScreen → Models / Provider / Installer

MNN facade          :14324
Android MNN backend :127.0.0.1:14325
Unified gateway     :127.0.0.1:14326/v1
Cluster manager     :14321（当前默认值，不能把它当所有平台永远固定的接口）
```

**不得破坏**：`/v1/models`、`/v1/chat/completions`、streaming SSE、`auto` 系列模型别名、Go 已有集群安全机制、M10 PC→Android 路由。`14326` 当前仅 loopback；`pair-local` 不是一个可以安全部署到公网的认证凭据。

## 2. 已复核的具体问题（附代码定位）

| 优先级       | 问题                                                   | 主要定位                                                                                                                        | 必须修复的表现                                                     |
| ------------ | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| **P0** | **停止 PAIR 会主动离群**                         | `PairRuntimeService.stopRuntime()` 调用 `ClusterApi(session).leave()`                                                       | `Stop` ≠ `Leave cluster`；停服务后重启不能重新配对            |
| **P0** | 配对 UI/请求未以同一集群可信成员为充分依据禁止重复发起 | `ClusterManagement.kt` 只 `filterNot(PairNode::trusted)`；`PairRuntimeService.performClusterAction()` 直接 `api.invite` | A 邀请 B 成功后，两端应同步显示已配对，不能再出 PIN                |
| **P1** | 同目标重复点击/多请求可生成多个 invite                 | `services/nvpair-cluster-manager/invite.go:handleInviteNode` 主要按 `inviteId` 管理                                         | 单目标 pending 去重，重复请求可观察、可取消，不能重复会话          |
| **P1** | 发现状态可能滞后于成员/证书状态                        | `BrokerApi.toPairNodes`、`ClusterRepository`、`ClusterManagement`                                                         | UI 需要有成员、pending、发现信息的统一投影                         |
| **P1** | ModelScope 搜索 URL 报 404                             | `models/ModelSourceAdapters.kt:ModelScopeAdapter.search` 使用 `/api/v1/models?PageNumber...`                                | 替换为 MNN 官方组织源的真实已验证搜索 API                          |
| **P1** | Model Hub 混合通用模型与 MNN LLM，搜索依赖 SHA         | `ModelSourceAdapters.kt`、`ModelHubInstaller.kt`                                                                            | 只找 MNN 官方组织，详情阶段解析文件，安装阶段核验                  |
| **P1** | UI 写死 artifacts                                      | `ModelHubScreen.kt`                                                                                                           | 文件由`config.json` 动态确定，支持 `.mtok`、子目录、自定义名称 |
| **P1** | 拒绝通知权限后不启动服务                               | `MainActivity.kt` 权限回调以 `granted` 为前提                                                                               | Android 13+ 仍可 Start PAIR                                        |
| **P1** | MNN 未监控代理状态                                     | `RouterRepository.kt`、`PairRuntimeService.monitorProxyStatus()`                                                            | MNN 应成为一等 engine                                              |
| **P1** | M65 Android→PC 测试默认排除                           | `android/app/build.gradle.kts`                                                                                                | 添加独立 runner + 参数 + 实机记录                                  |
| **P1** | 已加载模型删除时缺少运行时协调                         | `ModelHubInstaller.deleteInstalledModel()`                                                                                    | unload 确认后删除；异常不得损坏活动模型                            |
| P2           | MNN native 停止时可能无限等待                          | `MnnEngineHost.close()` / native generation                                                                                   | 有界控制面、无悬垂引用、记录 stuck 状态                            |
| P2           | Auto selector 部分数据仍是启发式估计                   | Go gateway +`shared/modelselection`                                                                                           | 明确`unknown`；不虚构遥测，限定本阶段改动                        |
| P2           | CI 无当前 M12 集成通过证据                             | 仓库 workflow / 当前 commit                                                                                                     | 建立 JVM/Go/build/真机分级门禁                                     |

**特别说明：配对不是新建一个简单的 `TrustedPeer(hostUuid)` 数据库。** PAIR 的 Go `TrustStore` 通过 `trusted/<peerNodeUuid>.json` 记录证书、指纹、cluster、endorsements、admission epoch；成员列表由 `membership` 管理，证书更换会被拒绝，撤销证明阻止旧成员复活。要利用这一基础，而不是复制可信状态。

## 3. 完成定义（用户可见行为）

- 在手机 A 发现电脑 B：首次配对按原 EAP-NOOB PIN 流程进行，一次完成。
- 配对成功后：**A 和 B 均显示 Paired**。B 不应再给 A 展示 `Pair` 按钮；A 也不应再对 B 创建新 PIN。
- A/B 任一侧 `Stop PAIR` / 再 `Start PAIR`：原身份、cluster、pin、member 都应保留；网络连接可断开再恢复，**无需重新配对**。
- 对方暂时离线：显示 `Paired · Offline`，而不是 `Unpaired`。
- 对方属于**不同的** cluster：提示 `Already in another cluster` / `Requires explicit leave or migration`，不得伪装成“与本机已配对”。
- 两边同时点击邀请：不生成两条成功关系；允许一个尝试因明确冲突失败并要求重试，但**不得**自动清空已承诺的信任，也不得静默建立另一个 cluster。
- Model Hub 只从 ModelScope 的 `MNN` 组织和 Hugging Face 的 `taobao-mnn` 组织搜索；只有完整且可被 PAIR 当前 MNN LLM 加载的 package 才能安装。
- 手机完成安装后，本机 API 和跨设备 PAIR 路由能发现并使用新模型。

---

# 第二部分：配对幂等、持久信任与集群状态（本次新增重点）

## 4. 首先修 Stop/Leave 的语义错误（P0）

### 4.1 证据

当前 `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt`：

```kotlin
private suspend fun stopRuntime(startId: Int) {
    // ...
    val session = activeSession.get()
    if (session != null) {
        val leaveFailed = withContext(Dispatchers.IO) {
            runCatching { ClusterApi(session).leave() }.isFailure
        }
        // ...
    }
    closeMnnRuntime()
    // ...
}
```

而 Go `services/nvpair-cluster-manager/leave.go` 的 `leaveCluster()` 明确会发送离开证明/墓碑、清除 pins、members、cluster identity。它是安全语义上的**主动退群**，绝不是停掉 broker 的进程清理动作。

### 4.2 必须实现

`ACTION_STOP`：

```text
Stop accepting local requests
→ cancel/finish in-flight work (按既有取消协议)
→ stop discovery / close mDNS subscriptions
→ stop MNN HTTP/runtime
→ close broker and child processes
→ release multicast lock / foreground
→ mark runtime STOPPED
```

**不得**调用：

```text
cluster:leave
nodes:remove
TrustStore.Remove
清空 cluster identity
删除 trusted/ 下的 pin
生成 removal proof/tombstone
```

`ACTION_CLUSTER_LEAVE` 继续保留为单独的用户显式命令，UI 必须有确认操作说明“离群会撤销信任，需要重新配对”。`Remove member` 亦只在明确用户授权时使用。

**重要**：`onDestroy()`、系统进程终止、broker 崩溃重启，也不能把停止解释为离群。持久信任交由 Go 原有 state store 管理；退出后安全凭据仍留在 app 私有文件目录。系统真正的应用“清除数据”仍会删除它们，这是预期行为。

### 4.3 推荐代码形态

保持现有 `commandMutex`、Service 生命周期和 MNN 所有权；仅精确移除 `stopRuntime()` 中的 `ClusterApi.leave()`，并在 `performClusterAction(ACTION_CLUSTER_LEAVE)` 中保留明确调用。抽取小函数改善可读性：

```kotlin
private suspend fun shutdownRuntimeResources() {
    stopAcceptingRequests()
    // 复用现有安全 close 顺序；不写 cluster 持久状态
    closeMnnRuntime()
    activeSession.getAndSet(null)?.let { session ->
        withContext(Dispatchers.IO) { session.close() }
    }
}
```

上例是职责示意，**`stopAcceptingRequests()` 是否已有现成接口必须先检查**；不要编造方法然后空实现。`runBrokerLoop` finally 的二次 close 必须幂等，不能引发 close race。

### 4.4 测试

`PairRuntimeServiceInstrumentedTest` 增加 spy/fake RPC（若现有测试不支持，先通过可注入 `ClusterActions` 提供 seam，而不是 mock 静态全局）：

```text
Start → paired → Stop:
    cluster:leave calls = 0
    nodes:remove calls = 0
    broker.close calls = 1 (或经设计确认幂等)

Start → explicit Leave:
    cluster:leave calls = 1

Stop → Start:
    nodeUuid unchanged
    cert fingerprint unchanged
    clusterId unchanged
    trust/member snapshot recovered
```

还需 Go 端 `leave_test.go` 原有测试证明**显式离群**行为仍保持原样；不能因为修复自动 Leave 而破坏真正的撤销流程。

## 5. 配对的正确“事实”定义

### 5.1 四个概念不能混淆

| 名词              | 来源               | 含义                                                                         |
| ----------------- | ------------------ | ---------------------------------------------------------------------------- |
| `Discovered`    | discovery          | 网络上看到节点，不代表信任                                                   |
| `TrustedPin`    | Go`TrustStore`   | 本地持有并验证对方证书，可能因撤销/cluster 差异不能使用                      |
| `ClusterMember` | Go membership      | 本节点认为对方是该 cluster 正式成员；可能离线                                |
| `Paired`        | 经聚合后的权限状态 | **同一 cluster 的有效正式成员 + 对应合法 pinned identity、无撤销冲突** |

`hostUuid` / `nodeUuid` 是稳定**加密身份**的主要键，`nodeId`/机器名用于展示和旧 RPC 兼容，IP 仅用于连接。证书指纹必须与可信记录匹配，禁止仅按 UUID 声称便放行。

### 5.2 不得错误推导

```text
peer.trusted == true            不能单独证明处于同一集群
peer.clustered == true          不能证明“已与我配对”
peer.ipAddress == member.ip     不能证明是同一个节点
nodeId == machineName           不能作为安全去重 key
一个端显示成功                 不能擅自让另一端产生 TrustStore pin
```

UI 快速状态可以用 `PairNode.trusted` 提示，但实际 `invite` 资格和路由授权必须问 Go 的成员/信任源。若成员信息与发现信息不同步，显示 `Syncing / Reconnecting`，触发 refresh/reconciliation；不得生成伪造 trust 文件。

### 5.3 不要新建第二个 TrustedPeer 持久化体系

禁止新增独立 JSON/SharedPreferences：

```text
trustedPeers = [hostUuid]
```

并把它作为权威。允许创建一个**仅供 UI 使用的无安全权限缓存**：`PeerUiState` / `PeerRelationshipView`，来源永远是 Go membership/trust + discovery 投影。

## 6. 统一配对状态投影

在 `android/.../data/` 添加小型纯函数聚合器，推荐：

```text
PeerRelationship.kt
PeerRelationshipResolver.kt
```

**候选**类型（真实属性可按已有数据模型调整）：

```kotlin
enum class PeerRelationshipStatus {
    PAIRED_ONLINE,
    PAIRED_OFFLINE,
    INVITE_OUTBOUND,
    INVITE_INBOUND,
    DISCOVERED_UNPAIRED,
    IN_OTHER_CLUSTER,
    INCONSISTENT,
    UNKNOWN_IDENTITY,
}

data class PeerRelationshipView(
    val nodeUuid: String?,
    val displayName: String,
    val status: PeerRelationshipStatus,
    val address: String?,
    val canInvite: Boolean,
    val reason: String?,
)
```

只要身份可确定，列表以 `nodeUuid` 合并：`discovery` 与 `members` 的同一人不能在 Nearby/Paired 各出现一份。已入群但离线应保留在 `Paired Devices`；单纯发现但无入群关系显示 `Nearby Devices`。

状态优先级建议：

```text
[本地合法会员+pin+同 cluster] → PAIRED_ONLINE / PAIRED_OFFLINE
[该 peer 存在 live outbound/inbound] → INVITE_*
[对端确实为别的 cluster] → IN_OTHER_CLUSTER
[身份/信任互相矛盾] → INCONSISTENT
[只有 discovery，无 stable UUID] → UNKNOWN_IDENTITY（不直接入可信）
[discovered，明确未配对] → DISCOVERED_UNPAIRED
```

**识别已有成员不应依赖对方在线**。`nodes:get-initial` 包含成员是持久事实，`discovery` 只更新可达性；App 冷启动必须先让 `ClusterApi.initialize` 完成，再把 “unpaired” 渲染为可点击，避免成员快照尚未加载时的瞬时重复 Pair。可显示 `Checking pairing status...`。

## 7. UI 必须拦截，但 UI 不是安全边界

修改 `ClusterManagement.kt`：

- 从 `PeerRelationshipView` 渲染按钮，而不只是 `discoveredNodes.filterNot(PairNode::trusted)`。
- `PAIRED_ONLINE` → `Paired · Online`，不可 Pair；`PAIRED_OFFLINE` → `Paired · Offline`，不可 Pair。
- `INVITE_OUTBOUND` → 显示当前 PIN/取消，不能第二次点击；`INVITE_INBOUND` → 输入同一个邀请的 PIN。
- `IN_OTHER_CLUSTER` → “Other cluster” 及明确的提示，不可直接覆盖原关系。
- `INCONSISTENT` → “Pairing needs repair”，只提供状态刷新/诊断，不自动重新配对。
- 只有 `DISCOVERED_UNPAIRED` 且 identity 验证可用于安全预检查时才显示 `[Pair]`。
- `Disconnect` 不能实现为 `Leave`；当前如果没有“断开连接但保留关系”能力，就不要展示一个行为不明的 `Disconnect` 按钮。
- `Forget` / `Remove` / `Leave` 必须有明确区分和确认，复用 Go `nodes:remove`/`cluster:leave`，不要本地删 pin 文件。

UI 使用纯函数/Presenter：

```kotlin
fun buildPeerViews(
    discovered: List<PairNode>,
    cluster: ClusterState,
    trustEvidence: Map<String, TrustEvidence>,
    loaded: Boolean,
): List<PeerRelationshipView>
```

`TrustEvidence` 如果 broker 尚无稳定的安全只读 API，可先基于 Go `nodes:get-initial` 的**confirmed member** 投影“paired”，由 Go 在 server 邀请入口独立核验 pins。UI 必须标注投影不授予网络权限。不要将 Kotlin 侧 `trusted` 当成服务器安全授权。

## 8. Kotlin Controller/Service 再次校验

修改：

```text
PairRuntimeController.kt
PairRuntimeService.kt
ClusterRepository.kt
ClusterApi.kt
```

`PairRuntimeController.invite()` 只发送 UI 意图；`PairRuntimeService.performClusterAction(ACTION_CLUSTER_INVITE)` 在调用 `ClusterApi.invite()` 前，先读取一致的成员/邀请投影：

```kotlin
sealed interface InviteDecision {
    data object Allowed : InviteDecision
    data class AlreadyMember(val peerUuid: String) : InviteDecision
    data class ExistingInvite(val inviteId: String) : InviteDecision
    data class NotAllowed(val reason: String) : InviteDecision
}
```

调用流程：

```text
resolve stable target UUID
→ verify target is not self
→ verify no confirmed membership with same cluster
→ verify no live pending invite to target
→ send RPC
→ inspect typed result/error
→ refresh members/discovery/invites
```

在同一进程内同一目标重复 Intent 应串行处理或 single-flight。现有 `commandMutex` 主要用于 start/stop，不应在长达 35 秒的配对 RPC 上持有同一把全局锁；可以对 target UUID 用小粒度 pending map / `Mutex`，取消后释放。**真正的并发防重复由 Go 层负责。**

## 9. Go `cluster-manager` 是最终防重复门

涉及：

```text
services/nvpair-cluster-manager/invite.go
services/nvpair-cluster-manager/membership.go
services/nvpair-cluster-manager/truststore.go（原则上只读现有 API，不重写 Pin）
services/nvpair-cluster-manager/rpcerrors.go
services/nvpair-cluster-manager/httpserver.go
services/nvpair-cluster-manager/respond.go
```

### 9.1 现有能力必须保留

当前 Go 已有：

- stable `NodeUUID`、证书 subject/URI 一致性校验；
- `TrustStore.Pin` 对**同 UUID 不同证书**拒绝自动更换；
- `members` 以 `nodeUuid` 为主键；
- 对**已处于 cluster 的 joiner**在 Initial Exchange 前返回 `already-clustered`；
- `inviteMu`、`rosterMu`、`sessGen`、`admissionEpoch`、teardowns、tombstone/removal proof；
- invite/session 已各有独立生命周期，以及并发/失败/撤销测试。

**不得删除或绕过其中任何一个。**

### 9.2 服务器 `invite-node` eligibility

在 `handleInviteNode` 的“创建 cluster / mint inviteId / 网络请求”之前执行权威预检查：

1. 把目标输入规范化为**可验证的 `nodeUuid`**。`nodeId` 可兼容旧调用，但不应以同名机器作为去重 key；从可信 roster/discovery 解析不到唯一 UUID 时，返回明确错误，而非对所有“同名设备”混为一人。
2. 拒绝邀请自己。
3. 如果目标已是**本 cluster 的合法 confirmed member + pinned cert**：返回类型化 `AlreadyMember`（不新建 invite，不创建 cluster，不发起网络 EAP，不输出 PIN）。
4. 如果存在同目标**pending outbound invite**：返回同一活动 invite 的引用或明确的 `InviteInProgress`，**不新建第二条**。注意 PIN 只应按现有规则交付给发起本地会话的一方，不得泄露给入站/跨身份查询者。
5. 如果发现目标属于其他 cluster：返回 `DifferentCluster`/现有 `already-clustered` 语义，绝不能当作本 cluster 已配对。
6. 只有完全未配对、无冲突才执行现有 `foundCluster → pending invite → Initial Exchange`。

### 9.3 错误/结果契约（兼容性优先）

**不建议伪造一条 `state=paired` 且 `inviteId` 为假值的记录。** 当前 Kotlin `parseClusterInvite()` 强制 `inviteId` 非空，PC 端也可能依赖 invite lifecycle。

建议优先使用现有 JSON-RPC `codePrecondition=-32004`，在 `error.data` 中加入**稳定 machine reason**：

```json
{
  "code": -32004,
  "message": "node is already a member of this cluster",
  "data": {
    "reason": "already-member",
    "nodeUuid": "<stable-uuid>"
  }
}
```

同目标 pending：

```json
{
  "code": -32004,
  "message": "pairing already in progress",
  "data": {
    "reason": "invite-in-progress",
    "inviteId": "<existing-id>"
  }
}
```

必须先检查 `JsonRpcClient`/Go broker 是否保留 `error.data`；如果链路会丢失 data，**先扩展类型安全的 RPC error parser**，不靠解析 `message` 文本。正常旧调用和完整 invitation schema 不修改。成功结果原样返回 `ClusterInvite`。对外 HTTP peer 侧现有 `409 rejected`/`already-clustered` 继续保留；不要让“already-member”跳过 certificate verification。

### 9.4 避免 check-then-act 竞态

不能：

```go
if !isMember(target) { // unlock
    createInvite(target) // another goroutine can do same
}
```

须在当前**已有的 invite/cluster 锁边界**中核验 + 注册单次 pending；涉及 trust/member 的查询不得随意新增锁序。编码前绘出锁获取顺序（`inviteMu`, `rosterMu`, `memMu`, `TrustStore.mu`, `sessMu`），检查现有 `withClusterComposition`、`withPendingPairing` 以及 teardown 流程，补充并发测试，必要时使用封装好的快照查询。**不得在持有 inviteMu 或 rosterMu 时进行远端 HTTP 网络调用。**

推荐内部辅助函数（示意）：

```go
type inviteEligibilityKind uint8
const (
    inviteAllowed inviteEligibilityKind = iota
    inviteAlreadyMember
    inviteExistingPending
    inviteTargetUnknown
    inviteDifferentCluster
)

type inviteEligibility struct {
    kind inviteEligibilityKind
    targetUUID string
    existingInviteID string
}
```

将 pending-by-peer 查重与 invite register 放在同一线性化点；不创建第二份持久“配对表”。

## 10. 双向/并发配对冲突的正确边界

### 10.1 普通“反向发起”

```text
A invites B → B enters PIN → A/B commit admission
B sees A → relationship=PAIRED → no Pair button
B attempts raw invite(A) → Go returns already-member without PIN
```

### 10.2 同时点击的两个邀请

两端各自运行 `cluster-manager`，所以 `pairKey=min(UUID):max(UUID)` **只能当作相关性/去重辅助键**，不能自动实现跨机器分布式锁。当前邀请会在本地自动建 cluster；两端同时各自建 cluster 的交叉邀请可能被现有 `already-clustered` 保护拒绝。

本阶段**安全的最低要求**：

- 同一个 peer 不得出现两个已确认的成员/信任关系；
- 不得在一个节点并行注册多条指向同 UUID 的 pending outbound；
- 已经建立的合法信任不能因另一条竞争请求失败而被清理；
- 如果两个首次邀请彼此冲突，可对其中一个或两个给出 `concurrent-invite / already-clustered` 的清晰失败，待 pending 清理后用户**单侧重试**；
- 严禁自动 `leaveCluster` 或静默清除用户已建立的 cluster 来解决冲突；
- 如果要实现“同时点击自动择一成功”，需要**单独的跨节点提议/仲裁协议设计和安全审查**，不是加一个 `pairKey` 字符串就能保证。

建议将两端均未入群的同时发起用 Go 并发/integration 测试覆盖，明确 `0 或 1` 成功、`0` 重复成员、`0` 信任破坏；如结果为双方均拒绝，UI 应提示“请由其中一台设备重新发起邀请”。

## 11. 不一致/单边配对恢复

真实场景：A 显示成功，但 B 的 `cluster:identity-changed`/`nodes:changed` 到 Android UI 的通知丢失，或 broker 重启。

修复优先级：

```text
先检查 B 的 Go durable admission / members / pin 是否实际上已经提交
    ├── 已提交：重新读取 nodes:get-initial + cluster:get-node-id
    │          并合并 discovery，恢复 UI；不再 Pair
    └── 未提交：不能只凭 A 的状态在 B 补写 trusted pin
               诊断 invite/ack/roster 状态，通过现有安全协议完成或显示故障
```

当 `nodes:changed`、`cluster:identity-changed`、invite terminal notifications 到达时，更新 `ClusterRepository`，并在终态或 broker reconnect 后做**一次有界、幂等、非阻塞** refresh：`nodes:get-initial` + `cluster:get-node-id`。现有 `membersGeneration` 机制避免旧 snapshot 覆写新 notification；保留该防竞态设计。

若两个节点有相同 UUID、不同 cert / revoked epoch 不一致，返回 `INCONSISTENT` 并要求明确的安全恢复流程；禁止自动“接受远端说法”。

## 12. 配对专项测试矩阵

| Case | 前置状态                      | 操作                    | 期待                                           |
| ---- | ----------------------------- | ----------------------- | ---------------------------------------------- |
| P01  | A/B 从未配对                  | A→B、B 输入 PIN        | 双端同 cluster，正式成员+pin，UI Paired        |
| P02  | P01 完成                      | A 再 Pair B             | AlreadyMember，不产生新 invite / PIN           |
| P03  | P01 完成                      | B 再 Pair A             | AlreadyMember，不产生新 invite / PIN           |
| P04  | P01 完成                      | A Stop → Start         | UUID、指纹、cluster、pin、membership 不变      |
| P05  | P01 完成                      | B Stop → Start         | B 仍 Paired；A 可视为 Paired Offline→Online   |
| P06  | B 临时无网络                  | A 浏览 B                | Paired Offline，不显示 Pair                    |
| P07  | 未配对 A/B                    | A 快速连续点击两次      | 一个 pending invite / 一个 PIN                 |
| P08  | 未配对 A/B                    | 两端同时点击            | 不出现两个成功关系；明确冲突策略；无残存假 pin |
| P09  | A 在 cluster X，B 在 Y        | A invite B              | DifferentCluster，不当作 AlreadyMember         |
| P10  | 更换对方证书但 UUID 相同      | 试图邀请/复用           | 拒绝 silent re-pin，安全错误                   |
| P11  | 有 removal proof              | 旧 peer 重新上线        | 不复活已撤销的成员                             |
| P12  | P01 完成                      | A 显式 Leave            | A 正式离群，B 经现有撤销协议最终收敛           |
| P13  | P01 完成                      | A 显式 Remove B         | B 被正确移除，后续重配须显式操作               |
| P14  | broker 中途崩溃               | 重启、订阅、取 snapshot | 不丢历史成员，也不重复邀请                     |
| P15  | 收到重复 invite terminal 事件 | 处理多次                | UI 和状态幂等，历史不会复活 pending            |

> **测试先于发布**：Go 添加 `pairing_idempotency_test.go`、`pairing_duplicate_invite_test.go`、`pairing_conflict_test.go`（按现有 helper），Android 添加 `PeerRelationshipResolverTest.kt` 与 Service cluster command test。不要为测试引入真实网络和 sleep 依赖；可用同步 barrier、fake transport、test scheduler。

---

# 第三部分：Model Hub 改为官方 MNN LLM 专用站点

## 13. 目标边界

```text
PAIR Model Hub
       │
       ├── ModelScope / owner=MNN
       │
       └── Hugging Face / author=taobao-mnn
                   │
                   ▼
           Search 只返回候选元数据
                   │
                   ▼
           Inspect repo / file tree
                   │
                   ▼
           Download config.json
                   │
                   ▼
           Resolve MNN LLM artifacts
                   │
                   ▼
           Validate / download / hash / atomic install
                   │
                   ▼
           MnnModelManager.resolve
                   │
                   ▼
           Catalog / PAIR facade / Gateway
```

**不支持**仅靠 `.mnn` 后缀就安装。MNN 项目可能有 CV、扩散、Embedding、Audio 等格式；M12.5 的 PAIR 本地聊天推理只接受符合当前 MNN LLM config schema 的 package。模型卡片可以展示 `Inspecting` / `Not supported`，但最终 `[Install]` 必须以解析和 Runtime 校验为准。

## 14. 采用 EdgeMesh Provider *语义*，不复制其 C++ 子系统

EdgeMesh 当前：

```text
cpp/modelhub/modelscope_provider.cpp   → ModelScope owner=MNN
cpp/modelhub/huggingface_provider.cpp  → HF author=taobao-mnn
```

PAIR 继续在 Android Kotlin 实现 Provider；**不新加 ModelHub JNI / 第三套下载器**。保留 Compose UI、Go router、现有 MNN Runtime，迁移时以最小重构为原则。

## 15. Provider URL/协议契约

### 15.1 ModelScope

搜索使用其 OpenAPI：

```text
GET https://modelscope.cn/openapi/v1/models
    ?owner=MNN
    &search=<encoded-query>
    &page_number=1
    &page_size=20
```

现有错误入口：

```text
https://modelscope.cn/api/v1/models?PageNumber=1&PageSize=...&Name=...
```

文件列表及下载属于另一套 repo API（沿用 EdgeMesh，但**开工时必须执行在线 smoke 并检查 schema、鉴权、重定向**）：

```text
GET https://modelscope.cn/api/v1/models/{owner}/{repo}/repo/files
       ?Revision=<revision>&Recursive=true

GET https://modelscope.cn/api/v1/models/{owner}/{repo}/repo
       ?Revision=<revision>&FilePath=<encoded-path>
```

禁止 UI 拼接 URL，禁止把 `/openapi/v1` 替换字符串后无测试复用所有请求。定义单独 `searchBase`、`repositoryBase` 与封装的 URL builder。

### 15.2 Hugging Face

```text
GET https://huggingface.co/api/models
    ?author=taobao-mnn
    &search=<encoded-query>
    &limit=20
```

详情可使用：

```text
GET https://huggingface.co/api/models/{repoId}
```

若详情未返回完整文件 metadata，必须使用官方仓库树/文件元数据 API（`tree`，支持递归、分页及 LFS metadata），**不得假设 search 的 `siblings` 一定携带 SHA-256**。所有分页必须取完目标 package 所需文件或明确终止，不得把第一页当作全量文件列表。

下载使用官方 resolve 路径，并在安装阶段 pin revision：

```text
https://huggingface.co/{repoId}/resolve/{commitSha}/{artifactPath}
```

### 15.3 不能固定“404 一定是 URL 拼错”

HTTP 404 也可能来自：模型删除、组织/仓库错误、API 升级、区域 CDN、路径编码错误、访问受限。正确实现要求记录：`provider`、`operation`、脱敏 URL origin/path、HTTP status、`request-id`、是否可重试。**上线前的 live smoke 才能确认实际 200**；不能单凭替换 URL 就宣称 ModelScope 已修复。

## 16. 推荐最小代码分层

如果现有文件足够小，可保持目录不移动，只抽清职责。建议目标：

```text
android/app/src/main/java/com/nv/pair/models/
├── ModelDescriptor.kt
├── ModelRepositories.kt            # source/inventory facade
├── ModelHubScreen.kt              # 仅展示 Compose 与回调
├── ModelHubViewModel.kt           # 状态、取消、分页、事件编排
├── provider/
│   ├── ModelSourceAdapter.kt       # 新统一 provider 契约
│   ├── ModelScopeMnnProvider.kt
│   ├── HuggingFaceMnnProvider.kt
│   ├── ProviderModels.kt
│   └── ModelHubHttpTransport.kt
└── install/
    ├── MnnArtifactResolver.kt
    ├── MnnPackageManifest.kt
    ├── ModelHubInstaller.kt
    ├── ResumableModelDownloader.kt
    └── InstalledModelManifest.kt
```

不要求一次 PR 移动所有文件，**先保证抽象边界，再进行文件移动**；避免代码审查出现大量无意义 rename diff。

## 17. Provider 契约（明确而不是 nullable 万能对象）

```kotlin
interface MnnModelProvider {
    val id: ModelProviderId

    suspend fun search(query: String, page: Int, pageSize: Int): ModelSearchPage
    suspend fun inspect(repoId: String): RemoteModelDetails
    suspend fun artifacts(repoId: String, revision: String): List<RemoteArtifact>
}

enum class ModelProviderId { MODELSCOPE, HUGGING_FACE }

data class RemoteModelSummary(
    val provider: ModelProviderId,
    val repoId: String,
    val displayName: String,
    val description: String?,
)

data class RemoteArtifact(
    val repositoryPath: String,
    val sizeBytes: Long?,
    val expectedSha256: String?,
    val revision: String,
    val url: URL,
)

data class ModelSearchPage(
    val items: List<RemoteModelSummary>,
    val page: Int,
    val hasNextPage: Boolean,
)
```

- 搜索页不能以 `format=UNKNOWN` 判不可安装。
- `RemoteModelSummary` 永远保留 `provider`，不能在序列化/归并时丢失。
- `repoId` 是远程标识，**不是**本地安全路径；远程路径必须验证。
- `ProviderError` 应包含 `provider`, `operation`, `category`, `httpStatus?`, `retryable`, `requestId?`；敏感 header/token 永不打印。
- 底层网络调用可保留 JVM `HttpURLConnection`（注入 `Transport` 方便测试），但必须统一超时、取消、重定向、body 限制、header 处理。

## 18. 搜索与详情严格分离

```text
Search(query)
    -> 仅获取组织内 repo 元数据
    -> 返回卡片 UNKNOWN/INSPECTABLE

Inspect(repo)
    -> 固定 revision（首选不可变 commit SHA）
    -> 获取文件树/size/hash metadata
    -> 小文件 config.json 先读并限制上限
    -> 解析 required artifact list
    -> 显示 Compatible / Unsupported(reason)

Install(resolved)
    -> 只下载 manifest 中已解析且严格安全的文件
```

不再在 ModelScope 搜索每条结果时逐个 `listFiles`；会导致 N+1 网络、搜索卡顿与局部 404。Hugging Face 搜索时也不以 sha 是否存在判断 compatible。

## 19. `MnnArtifactResolver` 唯一解析 package 规则

必须和现有 `MnnModelManager.resolve()` 对齐。审查时它要求 `config.json` 中 **`llm_model` 为非空**；另外使用这些缺省文件名：

```text
llm_model      → llm.mnn（但当前 runtime 要求字段有效，不能把“字段缺失”当合格）
llm_weight     → llm.mnn.weight
tokenizer_file → tokenizer.txt
```

（上行缩进仅为展示。）

实现流程：

1. 检查 repo file list 有且只有可选择的根目录 `config.json`；若 repo 含多个模型 variant，第一版要么提供 variant 选择，要么明确不支持，不要随便取第一个 `.mnn`。
2. 获取 config，JSON 解析前限制字节数（建议 `<=1 MiB`）；验证 `llm_model` 有效且可运行所需属性符合当前 Runtime。
3. 从 config 读取 `llm_model`、`llm_weight`、`tokenizer_file`；缺省逻辑**以现有 Runtime**为准；若出现 `tokenizer.mtok` 或嵌套目录正常处理。
4. 路径仅允许规范化的相对路径，拒绝空、绝对路径、`..`、反斜杠混淆、`.`/空 segment、NUL、URI scheme、符号链接逃逸、重复路径和文件/目录冲突。解压/重定向不可越界。
5. file list 中所需 artifact 均存在；不存在则 `UNSUPPORTED_MISSING_ARTIFACT`。
6. 不接受 GGUF、ONNX、普通 safetensors-only、缺 LLM config、只有 vision/diffusion MNN 的 repo。
7. 生成不可变 `ResolvedMnnPackage`，包含**完整来源、revision、文件列表、校验元数据**；不得把“搜到的 descriptor.files”直接传给 Installer。

核心接口：

```kotlin
sealed interface MnnCompatibility {
    data class Compatible(val value: ResolvedMnnPackage) : MnnCompatibility
    data class Unsupported(val code: String, val detail: String) : MnnCompatibility
}

class MnnArtifactResolver {
    fun resolve(
        model: RemoteModelDetails,
        configJson: String,
        files: List<RemoteArtifact>,
    ): MnnCompatibility
}
```

UI 不得再包含当前类似：

```kotlin
val requiredArtifacts = setOf("config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt")
```

## 20. 本地 ID、身份映射与升级

**不得简单删除 `-MNN` 后直接覆盖同名模型。** 两个来源可能有同名 repo，但权重、量化、revision 不同。

策略：

- 本地 ID 符合 `MnnModelManager` 当前安全正则；用户可读、稳定、有限长度。
- `provider + repoId + revision + variant` 写入 `.pair-model.json`，供重复安装/来源展示/后续升级使用。
- 若旧本地目录为原始 repo basename，优先兼容，不做自动重命名迁移；新安装冲突明确提示而非覆盖。
- model ID 对 OpenAI 客户端稳定，不因临时路径、下载 task UUID、展示名称变化而变化。
- 同 ID 同内容显示 Installed；同 ID 不同来源或 revision 采用冲突处理（显式选择新 ID 或后续 upgrade），不隐式覆盖。

## 21. SHA、完整性与供应链策略

分清：**传输真实性**、**内容完整性**、**独立可信的 expected digest** 是不同概念。

- 必须使用 HTTPS，固定可信下载主机，重定向后仍为受允许 host/scheme；不得向未批准 host 转发 bearer token。
- 从平台可获取 sha256/LFS metadata 时，必须比对；不相符禁止安装。
- 当平台没有某文件的可信 expected sha256：必须明确该文件为 `UNVERIFIED_SOURCE_DIGEST`，用固定 revision + HTTPS 下载并计算**本地** SHA-256 留存 manifest；不能把自行计算的值误称为“远程校验通过”。
- 对大权重文件可设置较严格的发布策略（如缺可信 checksum 时在 UI 显示风险/阻止自动安装），由 M12.5 产品策略统一决定并写测试；不得“某些页面缺 sha 禁用，另一些下载器无 sha 接受”。建议初版允许**明确提示源摘要不可用**的官方组织公开模型；不对外宣称“经远端 SHA 验证”。
- 对 gated/private repo：如未实现 token 提供和授权 UI，返回清晰“不支持/需要授权”，绝不能绕过权限。
- `revision=master/main` 是可变引用：安装时尽力解析为 immutable revision（commit SHA），下载/重新下载要锁定同一 revision；无法 pin 则标记无法保证一致并按明确策略拒绝/显式提示。

## 22. Downloader 正确性

保留断点续传，但必须抽象为 `DownloadTask` / `Progress`；UI 不应直接持有打开的 HTTP stream。

**请求/响应矩阵**：

| 状态              | 正确行为                                                                  |
| ----------------- | ------------------------------------------------------------------------- |
| fresh + 200       | 从 0 写`.part`                                                          |
| partial + 206     | 解析`Content-Range`，start 必须等于 offset；校验总长度/ETag（可用时）   |
| partial + 200     | 服务端忽略 Range；截断 partial 从 0 重新写，不 append                     |
| 416               | 完整 partial 则校验 hash；否则有限次数清理后重下                          |
| 3xx               | 检查跳转目标与凭证域，不跟随到任意 host                                   |
| 401/403           | 认证/授权错误；不可盲目重试                                               |
| 404               | 文件/revision 不存在；停止当前安装，记录来源                              |
| 408/429/5xx       | 有界、指数退避 + jitter、尊重 Retry-After（可用时）                       |
| checksum mismatch | 清理损坏的文件，失败，不发布 final                                        |
| cancel            | 关闭 stream/连接，状态`CANCELLED`，保留安全可恢复的 partial（策略固定） |
| disk full         | 完整回滚，不删除已安装模型，用户可见错误                                  |

所有下载创建 `.<modelId>.downloading/` 暂存目录；下载和状态元数据只允许位于 app 私有目录内。进度、取消、重试应在下载 manager 中串行协调，不靠 `busyModel = "search"` 这样的魔法字符串。

**关于后台下载**：普通 Compose `rememberCoroutineScope` 与 Activity 绑定，Activity 销毁会取消大文件下载。M12.5 至少将任务所有权从 Composable 移出（独立 repository/service），支持恢复部分下载；如果设计为锁屏/切到后台仍必须继续长时间下载，应使用符合 Android 前台服务类型限制的明确机制，而非“后台无限 coroutine”。此功能可作为已知限制明确记录，不能虚称后台稳定持续下载。

## 23. Installer 事务

```text
resolve package
→ ensure modelId unused
→ validate disk estimate / free space
→ create staging (专有 owner lock)
→ fetch pinned-revision config
→ verify selected artifact list
→ download required files
→ verify hashes / sizes / safe paths
→ MnnModelManager.resolve(staging)
→ write .pair-model.json
→ fsync needed files/directories where supported
→ atomic publish staging → final
→ refresh installed catalog + engine discovery
```

**不可见的中途失败**：最终目录只有经过完整校验才出现。`Files.move(..., ATOMIC_MOVE)` 在目标文件系统不支持时：不能偷偷 `REPLACE_EXISTING` 覆盖老模型；若提供 fallback，要有独立的安全发布状态与 crash recovery 并有测试。由于 staging 与 final 均在 app 私有同一 root，可优先要求 atomic move 成功，否则终止安装并保留诊断。下载失败的 partial 可以留在 staging 用于重试，不能当 installed model 扫描。

`MnnModelCatalog` 只列出 `MnnModelManager.resolve` 合格的目录；metadata `.pair-model.json` 为来源信息，不是 Runtime 的依赖。

## 24. 删除模型与活动推理互斥

当前 `ModelHubInstaller.deleteInstalledModel` 仅删目录。正确动作应由 **runtime-aware application service** 调度：

```text
Delete(modelId)
→ acquire per-model lifecycle lock
→ reject new load for this model
→ if loaded: request unload
→ wait completion / timeout and check actual loaded state
→ if request still active or unload failed: DENY deletion
→ after confirmed safe: remove model directory
→ update catalog / inventory / gateway
```

**不能在删除器里直接 new 一个独立的 MnnRuntime**。MNN Runtime ownership 属于 `PairRuntimeService/MnnRuntimeContainer`。若 Service 没运行，可直接核实无当前 native runtime 且拿到目录锁后删除；若 Service 正运行，通过明确的受保护 controller/IPC command 进行 unload。必须测试删除同时有 streaming 请求、模型切换、重启、重复 Delete。

## 25. Model Hub UI/数据流

建议 ViewModel/Repository 统一如下状态：

```kotlin
sealed interface CatalogPhase {
    data object Idle : CatalogPhase
    data object Searching : CatalogPhase
    data class Failed(val failure: CatalogFailure) : CatalogPhase
}

enum class DownloadPhase {
    QUEUED, RESOLVING, DOWNLOADING, VERIFYING, INSTALLING,
    INSTALLED, FAILED, CANCELLED
}
```

页面：`[ModelScope] [Hugging Face]` 切换；搜索、分页、空结果、独立源错误、卡片详情、兼容性原因、下载字节进度、取消/重试、安装记录与删除。**禁止在 Compose 中 new Provider/Installer/Network client**；用生命周期稳定的 ViewModel 与注入的 Repository，所有 IO 在 `Dispatchers.IO`，错误在 state 中呈现。

源切换时取消或忽略过期搜索响应（request generation）；不能发生“切到 HF，ModelScope 的迟到结果覆盖当前列表”。已安装模型刷新必须与 API 库存刷新解耦且可观测。

## 26. Provider 单元测试必须覆盖

- ModelScope URL 是 OpenAPI `/models`、包含 `owner=MNN`、分页参数为 snake_case；文件 repo API 与下载 API 是正确不同路径。
- ModelScope 响应大小写、缺省字段、空列表、明确错误结构解析。
- Hugging Face URL 包含 `author=taobao-mnn`；search 无 `siblings`/无 hash 仍显示卡片。
- HF tree 分页/递归，`lfs.sha256` 和 git blob id 不能混淆为 sha256。
- 404/401/429/5xx、请求取消、连接断开、错误 redaction、非法 repoID/path。
- 自定义 `llm_model`/`llm_weight`/`tokenizer.mtok`、缺文件、恶意 config 路径、不符合 MNN LLM 的 `.mnn` repo。
- duplicate/local ID conflict/断点续传 206/200/416/不可变 revision/原子安装失败清理。
- 测试使用 `FakeTransport` + fixture JSON，**默认 JVM 单测不连公网**。

---

# 第四部分：Android 启动、MNN 状态、路由和稳健关闭

## 27. 通知权限与 FGS（P1）

`MainActivity.kt` 当前回调：

```kotlin
if (granted && startAfterNotificationPermission) runtimeController.start()
```

Android 13+ `POST_NOTIFICATIONS` 拒绝**不阻止启动前台服务**。改成：

```kotlin
private val notificationPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { _ ->
    val shouldStart = startAfterNotificationPermission
    startAfterNotificationPermission = false
    if (shouldStart) runtimeController.start()
}
```

更推荐将“用户点击 Start”的动作与“权限提示结果”分离，处理 repeated click/Activity recreation，保证一次点击最多一个 start Intent；FGS 启动仍受 Android 对 foreground/background launch、service type 和相关权限的限制，不保证任何后台环境都可以随时拉起。

测试：拒绝、允许、滑动关闭通知权限弹窗、重复点击、前后台切换、FGS 正确启动与停止。

## 28. MNN engine 状态一等化（P1）

当前 `RouterRepository` 默认只有 `ollama`、`lmstudio`，`PairRuntimeService.monitorProxyStatus` 也只遍历两者。

最小收口：

```kotlin
object PairEngineIds {
    const val OLLAMA = "ollama"
    const val LMSTUDIO = "lmstudio"
    const val MNN = "mnn"
    val ROUTABLE = listOf(OLLAMA, LMSTUDIO, MNN)
}
```

统一应用于：初始 proxy 状态、monitor、broker crash reset、UI、对应单测。`mnn` health 的真实来源应为现有 Go facade/engine manager 的 `proxyStatus`，不可用时明确 false；不将“App 本地 14325 活着”推断为“跨设备 MNN facade 可路由”。

## 29. MNN 生命周期：不允许无限 STOPPING（P2）

保持：

```text
PairRuntimeService owns MnnRuntimeContainer
→ MnnHttpServer owns request handling
→ MnnInferenceService serializes generate/unload
→ MnnEngineHost owns native Session
```

风险：native kernel 可能阻塞在 `generate(1)`，atomic cancellation 只能在 token 边界生效。`executor.submit { runtime.close() }.get()` 没有超时，Service 可能永久等待。

### 必须改进的控制面

- close 入口幂等，先 `STOPPING`，立即拒绝新请求；
- active request 标记 cancel，并让 network listener 先停止接收；
- 分别给 `stopAccept`、`await in-flight`、`close` 设合理受控时间限制；
- **Kotlin coroutine `withTimeout` 不会杀死 JNI native 线程**，不能因此提前 free 仍被 native 使用的 session；
- 超时要记录 `UNHEALTHY/STUCK_NATIVE`，阻止后续新 load/generate，避免 use-after-free；
- 必须分析 native lifetime、线程 ownership、daemon thread 与进程退出的真实约束，写一个不依赖强行 `Thread.stop()` 的方案；
- 如果无法保证从同进程 native hang 中安全回收，明确界定“控制面及时失败 + 进程级恢复”的界限，不伪称彻底解除阻塞。

建议单元测试使用可控 fake engine 在 `generate()` 卡住，确保 API server 停止接收、Service 控制协程无无限等待；另外上设备测试长输出中 Stop 与重复 Start。

## 30. Model inventory 与 auto-routing

模型来源必须区分：

```text
Remote catalog（可搜索下载） != Runtime inventory（可路由）
```

只有安装成功、MNN Runtime 能 load、Go facade/Discovery 广告可用的模型才能进入 gateway 的 `auto*` 候选。`ModelHubInstaller` 发布后应刷新 MNN catalog，触发 manager/facade 的既有库存/健康检查机制，不能简单拿 UI 卡片数量充当 `/v1/models` 的真实数量。

Auto Model 本阶段不重做大范围算法：至少 `loaded`、`engine`、`node identity` 来自事实；缺少实际 tokens/s、TTFT、网络代价、空闲内存时，明确标记 unavailable，并在 scorer 中测试对未知值的影响，**不能虚构监控值**。最终决策仍由 Go gateway 决定，Kotlin `AutoModelSelector` 应避免给出与实际不同的权威结果。

## 31. 网关与安全边界回归

保持：

```text
GET  http://127.0.0.1:14326/v1/models
POST http://127.0.0.1:14326/v1/chat/completions
```

`pair-local` 是本地占位凭据，当前 gateway loopback-only；不将其开放到 `0.0.0.0`。远端调用手机模型继续走 PAIR 的现有集群/调度/facade 路径。跨设备流必须遵循现有受信任节点验证，不能因“双方不用重复配对”放松任何 mTLS 或授权校验。

---

# 第五部分：跨设备验收、构建、测试与 CI

## 32. M10 PC → Android（已有 runner，必须回归）

保留：

```text
android/scripts/run-m10-pc-to-android-acceptance.ps1
M10PcToAndroidMnnInstrumentedTest.kt
```

要求 PC 通过 PAIR 可信路径发现 Android MNN，调用 Android-only 模型，返回真实 token，SSE `[DONE]`，workload 归属 Android 节点。配对持久化修改后，必须测试 Stop/Start 前后的 M10 连通性。

## 33. M65 Android → PC（缺失正式 runner，P1）

现状：

```text
android/app/build.gradle.kts
    excludedInstrumentedTests += M65AndroidToPcRoutingInstrumentedTest
```

新增：

```text
android/scripts/run-m65-android-to-pc-acceptance.ps1
```

参数（根据现有 M10 风格对齐）：

```text
-PcAddress <ip>
-ModelId <PC-only-model-id>
-Engine <lmstudio|ollama>
```

对应 Gradle：

```text
-PpairM65Acceptance=true
-PpairM65PcAddress=...
-PpairM65ModelId=...
-PpairM65Engine=lmstudio
```

设置 `pairM65Acceptance` 时仅选择 M65 的 instrumentation class，并**不要同时用 `notClass` 把 M65 排除**；默认无 PC 的自动测试仍排除它。注意现有 M65 test 对 LM Studio 可能硬编码 backend port `1235`，不要让 `-Engine ollama` 假装通用：要么改为按 Engine 获取实际 backend private port，要么明确第一版仅支持 `lmstudio` 并报错拒绝其他值。

M65 通过条件：

```text
Android PAIR running
→ PC discovered and trusted in same cluster
→ PC model available through engine inventory
→ Android cannot directly bypass PAIR and reach private engine endpoint (适用时)
→ Android local facade/gateway route to PC
→ HTTP 200 / valid SSE token / [DONE]
→ workload.status=completed
→ workload.scheduledOn == PC nodeUuid
```

不要以“GET /models 能看到 PC 模型”代替真正生成；也不要用“绕过私有 backend 失败”来断言路由成功。

## 34. Model Hub 真实设备 smoke

真实公网 smoke 与 mock tests 分离，只有显式运行时才使用真实 ModelScope/Hugging Face：

```text
A. ModelScope 选择源，搜索 Qwen
B. 验证请求实际返回可解析结果，且 repo owner=MNN
C. Hugging Face 选择源，搜索 Qwen
D. 验证 repo author=taobao-mnn
E. 点击模型，获取完整 repo artifacts，解析 config
F. 选择设备内存可承载的一个小型 MNN LLM
G. 下载、断点恢复、校验、atomic install
H. installed 列表出现，MnnModelManager.resolve 成功
I. MNN backend 能 load + generate
J. 统一 `/v1/models` 能看到可路由模型
K. `/v1/chat/completions` 非 stream 和 stream 均通过
```

预先检查实际 RAM/存储是否足够、CPU/OpenCL 是否可用。不得预设 Model Hub 一定包含某个确切名字或大小；所选模型以现场源返回和本机兼容性判断为准。测试中发生 404 记录**实际 endpoint 和 response schema**，修改 Provider fixture 后重新执行，而不是仅针对一个成功 URL 做 hardcode。

## 35. 分层测试

| 层级                               | 性质               | 是否应作为普通 PR 门禁             |
| ---------------------------------- | ------------------ | ---------------------------------- |
| Go cluster-manager 单测/竞态测试   | 纯本地             | 是                                 |
| Go gateway/engine-manager 单测     | 纯本地             | 是                                 |
| Android`testDebugUnitTest`       | JVM fake transport | 是                                 |
| Android`assembleDebug`           | 构建               | 是                                 |
| Android instrumentation 无公网测试 | 模拟器/设备        | 有设备 Runner 时是                 |
| 真机 Model Hub 官方源              | 外部联网           | 手动/专用 nightly，不影响离线单测  |
| M10/M65 双设备                     | PC+Android 实机    | 发布门禁，不作为普通无设备 PR 门禁 |
| Native hang/inference soak         | 专用设备           | 发布前专项验证                     |

Windows/PowerShell 执行示例（以 Gradle wrapper 和各 Go module 为准）：

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug

cd ..\services\nvpair-cluster-manager
go test ./...
go test -race ./...

cd ..\nvpair-proxy
go test ./...

cd ..\nvpair-engine-manager
go test ./...
```

`go test -race` 的目标平台需要编译器/CGo 等前提；不支持时标记环境受限而不是把 race test 视为 PASS。Android 构建可能要求本地 MNN 源与构建脚本，先确认第三方 revision `d407447...`、patch 和 NDK 预设。

## 36. 测试质量规范

- Go 使用 `testing.T` 子测试/表驱动、`t.Parallel` 仅对状态隔离完备的用例；对并发用 barrier/Channel，不靠 `time.Sleep` 猜时序。
- Kotlin 使用构造函数注入的 fake `Transport`、`Clock`、`Repository`，不得依赖公网。
- 每一个 bug 至少有一个 **失败前复现 / 修复后通过** 的回归测试；要验证防重复的副作用数量（invite count、session count、pin count），不能只测 UI 文案。
- 状态机测试终态重复通知、旧 snapshot 覆写新 notification、进程/Service 重启、error retry、撤销与并发。
- 模型下载测试使用小型 fixture（几 KB 文件）；大模型只用于真机 smoke。
- 不往 Git 加入实际权重、密钥、PIN、私有证书，测试凭证由 fixture / 临时目录生成。
- 保留 Go 原有 `admission_clustertrust_guard_test.go`、`review_closure_test.go`、`removal_proof_test.go`、`pairing_teardown_test.go` 等测试，防止简单去重补丁破坏安全协议。

---

# 第六部分：逐提交施工路线（AI 严格按顺序）

## 37. 提交序列（建议 17 个独立 commit）

以下都应遵守“失败停止，不跨越未通过门禁”的规则。当前用户最关心的重复配对排在 Model Hub 之前，避免产品上的已配对关系反复消失。

### C01 — `fix(android-runtime): preserve cluster membership when stopping PAIR`

**文件**：`PairRuntimeService.kt`、Service tests、必要注释/README。

**实施**：分离 Stop 与 Leave；Stop 仅关闭进程和网络资源，显式 Leave 仍调用 Go 退群。

**门禁**：Stop 没有 `cluster:leave` RPC；重启 clusterId 不变；显式 Leave 原功能正常。

### C02 — `feat(android-cluster): project peer relationship from membership and discovery`

**文件**：`ClusterModels.kt`、`ClusterRepository.kt`、`PairRepository.kt`、新增 `PeerRelationshipResolver.kt`、测试。

**实施**：按 nodeUuid 合并 `members + invites + discovery`，保留离线 paired；标注 unknown/other-cluster/inconsistent。

**门禁**：同一 nodeUuid 只显示一张卡；offline 不变 unpaired；旧 snapshot 不覆盖新事件。

### C03 — `fix(android-cluster): disable duplicate invite actions in UI and service`

**文件**：`ClusterManagement.kt`、`PairRuntimeController.kt`、`PairRuntimeService.kt`、测试。

**实施**：UI 按 PeerRelationshipView 输出；Service 再检查已配对/已有 pending，避免重复 Intent；保留现有 API 外观。

**门禁**：两个方向已配对后不可点击 Pair；快速双击单条业务动作；异常有明确提示。

### C04 — `fix(cluster-manager): reject already-member and dedupe pending peer invites`

**文件**：Go `invite.go`、`membership.go`、`rpcerrors.go`、新增 idempotency tests；按需要更新 broker JSON-RPC error forwarding。

**实施**：稳定 UUID、有效 membership+pin 判定、pending 同 peer 去重；只扩展 machine reason，不伪造 inviteId。

**门禁**：Go 重复 invite 无新增 session/PIN；同 UUID 不同证书拒绝；`go test -race` 可运行的环境无竞态。

### C05 — `fix(cluster-sync): refresh durable state after pairing terminal and reconnect`

**文件**：`ClusterApi.kt`、`ClusterRepository.kt`、`PairRuntimeService.kt`、通知处理相关测试。

**实施**：终态、broker 恢复、identity changed 触发受控 refresh；尊重 generation；避免 UI 一侧仍未配对。

**门禁**：A/B 实际成功后双端刷新显示 paired；重复/乱序事件不回退；无凭空重钉证书。

### C06 — `test(pairing): add two-device pairing persistence acceptance`

**文件**：新增配对验收脚本/说明和 Go/Kotlin tests。

**实施**：A→B、B→A、Stop/Start、offline/online、不同 cluster、两端同时发起冲突。

**门禁**：真实设备/PC 运行时有验收日志；无法运行明确 `NOT RUN`，不可宣称完成。

### C07 — `refactor(android-modelhub): establish typed provider and transport contracts`

**文件**：`ModelSourceAdapters.kt` 拆分（必要时）、新增 provider/transport models、测试。

**实施**：ProviderId、RemoteSummary、Details、Artifacts、错误类型；保持当前 UI 编译通过。

**门禁**：现有测试仍绿，网络连接统一可 fake，不得残留第二套未使用 downloader。

### C08 — `fix(modelhub): query ModelScope MNN organization through OpenAPI`

**文件**：`ModelScopeMnnProvider.kt`、ModelScope tests/fixtures。

**实施**：`/openapi/v1/models?owner=MNN...`；区分文件树 API；修复 404 的已确认真实原因。

**门禁**：URL 构造单测通过；在线 smoke 无协议错误（环境不可用明确注明）。

### C09 — `fix(modelhub): query Hugging Face taobao-mnn without search-time checksums`

**文件**：HF Provider、tests。

**实施**：只搜 `taobao-mnn`，详情/文件树阶段取得完整 metadata；search 阶段无 SHA 仍正常。

**门禁**：无 sibling/hash 的 fixture 仍有卡片；重复/分页数据正确。

### C10 — `feat(modelhub): resolve safe MNN LLM packages from config`

**文件**：`MnnArtifactResolver.kt`、`MnnModelManager.kt`（只有确实 schema 差异才微调）、tests。

**实施**：唯一 config 解析规则，支持 `.mtok` 和自定义路径，禁止 traversal/非 LLM。

**门禁**：自定义文件名成功，缺文件/错误格式/越界拒绝，Runtime resolve 是最终合法性标准。

### C11 — `fix(modelhub): harden HTTP resumable downloads and pinned revisions`

**文件**：`ResumableModelDownloader`、transport tests。

**实施**：200/206/416、Range 校验、重试/取消、host allowlist、hash、resume。

**门禁**：模拟服务器所有边界用例通过，不把 200 append 到 partial。

### C12 — `feat(modelhub): install atomically with provenance manifest`

**文件**：`ModelHubInstaller.kt`、manifest、catalog refresh、测试。

**实施**：staging、package-driven download、校验、原子 publish、source/revision hash、ID conflict。

**门禁**：失败无 final，成功 Runtime resolve 并出现在安装库存，不覆盖旧模型。

### C13 — `refactor(modelhub-ui): move state ownership out of Compose`

**文件**：`ModelHubScreen.kt`、`ModelHubViewModel.kt`、Repository、tests。

**实施**：可观察搜索、inspect、install、download progress、取消和错误；移除硬编码 artifacts。

**门禁**：切换源不串结果、生命周期更替不重复下载、安装后 UI 与 Runtime 数据刷新。

### C14 — `fix(android): start foreground runtime when notifications are denied`

**文件**：`MainActivity.kt`、UI/Service tests。

**实施**：权限拒绝仍启动；符合 FGS 前台启动约束。

**门禁**：Android 13+ 拒绝/允许/关闭权限对话框均可按用户点击启动。

### C15 — `fix(android-mnn): unify engine monitoring and protect active-model deletion`

**文件**：`RouterRepository.kt`、`PairRuntimeService.kt`、`ModelHubInstaller.kt`/application service、tests。

**实施**：MNN 纳入 proxy registry、crash reset；loaded model unload-first。

**门禁**：MNN 状态正确更新；unload 失败仍保留模型完整文件。

### C16 — `fix(android-mnn): bound shutdown control flow without unsafe native free`

**文件**：`MnnEngineHost.kt`、`MnnRuntimeContainer.kt`、`MnnHttpServer.kt`、必要 JNI/测试。

**实施**：停止接收、有界取消/等待、stuck health、资源 lifetime 安全；记录无法强杀 native 的边界。

**门禁**：fake hung generate 不永久阻塞控制面、不发生 UAF；正常 Stop 无泄漏回归。

### C17 — `test(m12.5): wire M65 acceptance and regression gates`

**文件**：`android/app/build.gradle.kts`、`android/scripts/run-m65-android-to-pc-acceptance.ps1`、`M65AndroidToPcRoutingInstrumentedTest.kt`、CI/README。

**实施**：修复 `class`/`notClass` 参数，runner 可选择 PC engine，执行 M10/M65/ModelHub/Pairing 回归，Go/JVM/build 分级门禁。

**门禁**：所有可自动运行层 PASS；人工双设备验收有结果或明确阻塞，不把 smoke 写成模拟通过。

---

# 第七部分：代码风格、安全性、可维护性硬指标

## 38. Kotlin 质量规范

- 优先小型 `data class` / `sealed interface` 表达明确状态，禁止通过 `"paired;true;0"` 等不透明字符串表示结果。
- `MainActivity` 仅渲染/触发意图；`PairRuntimeService` 负责运行时，避免将 Model Hub IO 或复杂 Pair UI 计算塞入 Service。
- 业务规则进 Repository/Resolver/Coordinator，Compose 仅展示状态。每个主要函数建议控制在约 30–60 行，超出说明职责拆分依据；**不是为了行数机械拆函数**。
- Kotlin 协程遵守结构化并发，不能 `GlobalScope`，不能创建失去所有权的后台线程；`CancellationException` 必须重新抛出。
- `runCatching` 应限于边界处理，不默认用 `.getOrDefault(emptyList())` 掩盖 Provider 网络故障。
- 不在 Logcat 或错误 message 输出 model authorization token、PIN、node 私钥或证书私钥。
- `MnnModelManager` 是最终可运行 package 校验器；Remote Resolver 不复制 native 推理逻辑。

## 39. Go 质量规范

- 不改变现有 EAP-NOOB cryptographic handshake 计算，也不删除 removal proofs / admission epoch 事务。
- 复用已有 `Manager` 状态持久化，**不通过在 Go 与 Android 各存一套 UUID 列表解决重复配对**。
- 明确 lock order；禁止加锁期间发起 HTTP；对过期邀请、取消、回滚、teardown 保留原不变量。
- typed machine error，控制层和 UI 不用字符串模糊匹配。
- 对会持久化成员/信任的写操作写 crash consistency 测试；遵守原 `atomicWrite`/journal。
- 新增 RPC 返回字段需和 PC/Android JSONRPC 解析兼容，历史行为需有回归。

## 40. 文件系统与安全规范

- 所有模型文件都在 `files/mnn/models` 下，路径用 `canonical/normalize` 校验 containment，禁用 symlink escaping。
- Model Hub 不执行下载模型自带的 shell/python 脚本。
- 所有下载、metadata HTTP 请求固定允许协议/主机；鉴权 token 只按 provider 发送，不对 redirect host 泄漏。
- staging/final 发布必须排除并发同 ID 安装、被删除模型正在使用、磁盘空间不足、进程崩溃残留。
- 只安装 MNN，不尝试运行 `.gguf` `.onnx` `.safetensors` 原生文件；若未来增加转换，作为独立里程碑。
- 不开放 `14326` 到 LAN 作为修复跨设备通信的捷径。

## 41. 单一事实来源（禁止重复规则）

| 规则                                  | 唯一归属                                         |
| ------------------------------------- | ------------------------------------------------ |
| 可信证书、撤销、入群、授权            | Go`nvpair-cluster-manager`                     |
| 配对 UI 状态投影                      | Kotlin`PeerRelationshipResolver`（无授权能力） |
| 远程模型 Provider/API                 | Kotlin provider 层                               |
| 远程 MNN package 解析                 | `MnnArtifactResolver`                          |
| 本地 MNN package 合法性               | `MnnModelManager.resolve`                      |
| 安装事务                              | `ModelHubInstaller`                            |
| 当前 native 模型 load/unload/generate | `MnnRuntimeContainer`/MNN inference domain     |
| 可路由模型决策                        | Go gateway/runtime inventory                     |
| UI 来源/进度/错误展示                 | ViewModel StateFlow                              |

---

# 第八部分：最终验收标准与交付格式

## 42. Definition of Done（必须逐项核验）

### 配对/信任

- [ ] Stop PAIR **不**调用 `cluster:leave`、`nodes:remove`
- [ ] 显式 Leave/Remove 符合原安全机制
- [ ] A 邀请 B 一次，双方显示 Paired
- [ ] A/B 再次邀请对方 → AlreadyMember/无新 PIN
- [ ] 单端快速双击 → 一个 pending invite
- [ ] 同时互邀 → 不会产生双成功或破坏成员/证书；冲突可诊断
- [ ] 成员离线仍显示 Paired Offline
- [ ] Stop/Start 保留 UUID、cert fingerprint、cluster identity、trust/member
- [ ] 同 UUID 不同 cert 被拒绝
- [ ] 其他 cluster 的节点不会被误判为我方已配对
- [ ] 已撤销成员不复活，removal proof 测试仍通过
- [ ] broker notification 乱序或重启后重新读取一致的状态

### Model Hub

- [ ] ModelScope 搜索仅 owner=MNN；在线请求能解析（无法联网时记录 NOT RUN）
- [ ] Hugging Face 搜索仅 author=taobao-mnn
- [ ] 搜索阶段不要求 SHA/完整文件树
- [ ] 详情阶段解析 revision、完整 file list 和 MNN config
- [ ] `.mtok`、自定义模型/weight 路径可识别
- [ ] 路径逃逸、非 MNN LLM、缺文件被拒绝
- [ ] 支持 200/206/416、取消、续传、重定向安全和错误区分
- [ ] 有远端 checksum 时严格核验；无远端 checksum 状态真实展示
- [ ] 原子安装，失败不污染 final
- [ ] model ID 冲突不静默覆盖
- [ ] 安装后 MnnModelCatalog 和运行时库存更新
- [ ] 删除已加载模型必须 unload 成功才删除
- [ ] 测试不含真实模型权重和 secrets

### Android/PAIR

- [ ] 通知权限拒绝仍能 Start PAIR
- [ ] Router UI/monitor/crash reset 正确处理 `mnn`
- [ ] Native hang 时控制面不会永久无响应，也不危险 free session
- [ ] GET `/v1/models` 正常，POST `/v1/chat/completions` 正常
- [ ] streaming SSE 有 token 和 `[DONE]`
- [ ] auto 别名回归正常且 inventory 不包含未安装目录
- [ ] `14326` 仍是 loopback，远端通过原 PAIR 信任路由

### 跨设备/CI

- [ ] M10 PC→Android 回归通过（有设备时）
- [ ] M65 Android→PC runner 可正确启动测试
- [ ] M65 真机完成并检查 `scheduledOn`（有设备时）
- [ ] A/B 配对、Stop/Start 的双端人工验收记录
- [ ] Go cluster tests、Go gateway tests、Android JVM tests 通过
- [ ] `:app:assembleDebug` 通过
- [ ] 提交清单/风险/未验证项完整，文档反映最终实际接口

## 43. 最终人工验收脚本（顺序不可省略）

```text
1. 清除测试机 A、PC B 的旧测试数据（仅测试环境；不影响生产凭据）
2. 启动 A、B PAIR，记录各自 nodeUuid/指纹
3. 在 A 发起 Invite B；B 输入 PIN，完成配对
4. A/B 双端都显示 Paired，互相能够路由
5. B 界面不能再出现对 A 的 Pair 按钮
6. A 服务端 raw invite(B) → AlreadyMember，不产生 inviteId/PIN
7. B 服务端 raw invite(A) → AlreadyMember，不产生 inviteId/PIN
8. Stop A，B 显示 A Paired Offline
9. Start A，A/B 身份与原 cluster 不变，自动回到 Paired Online
10. Stop B，再 Start B；仍然无须 PIN
11. 从 A Model Hub 搜索 ModelScope MNN 官方模型
12. 切换 HF 搜索 taobao-mnn 官方模型
13. Inspect 一款小型兼容 MNN LLM，下载并原子安装
14. 本地 MNN load/generate 成功
15. 127.0.0.1:14326/v1/models 出现该可用模型
16. 用本机 OpenAI 客户端调用该模型成功
17. 用 A 统一 API 调 B 的 PC-only 模型，SSE 返回正确
18. 用 B PAIR 路由调 A 的 MNN-only 模型，SSE 返回正确
19. 两个方向 workload.scheduledOn 与实际执行节点一致
20. 正在生成时 Stop/取消，没有无限 STOPPING 和 UAF
21. 显式 Leave A：进入未入群状态；B 最终收到撤销/离群
22. 之后重新 Pair 必须走新 PIN（这是正常且需要重新配对的场景）
```

## 44. AI 每一提交必须交付的报告模板

```markdown
### Commit Cxx — <commit message>

**Baseline / HEAD**：
**Scope**：本提交解决什么、不解决什么
**Design**：变更前后控制流，职责和锁/资源所有权变化
**Files changed**：逐文件列举为什么要改
**Protocol/API compatibility**：无变化 / 变化及迁移策略
**Security implications**：证书、pin、撤销、token、路径等影响
**Tests executed**：命令、环境、PASS/FAIL/NOT RUN
**Evidence**：关键断言、设备日志或报告位置
**Remaining risks**：不能证明的部分、后续处理
**Exit gate**：通过/未通过
```

任何项填不上时必须解释原因，不允许输出笼统的“已全部修复”“测试均通过”。

## 45. 最终交付清单

AI 完成所有提交后，必须提交：

1. `M12_5_CHANGELOG.md`：按 commit 描述真正完成项。
2. `M12_5_TEST_REPORT.md`：单测、构建、双端/真机 smoke 分层结果与复现命令。
3. `M12_5_SECURITY_NOTES.md`：pairing trust/Stop vs Leave、模型来源验证、checksum 风险、native hang 约束。
4. `M12_5_KNOWN_LIMITATIONS.md`：未覆盖的格式、需要鉴权的模型源、后台大文件下载、OpenCL 限制、跨设备竞态边界。
5. 若现有 README 对 Stop 的语义、配对、MNN Model Hub 或 API 地址有旧文案，必须同步更新。

---

# 附录 A：实际施工文件索引

```text
android/app/src/main/java/com/nv/pair/MainActivity.kt
android/app/src/main/java/com/nv/pair/ClusterManagement.kt
android/app/src/main/java/com/nv/pair/data/ClusterModels.kt
android/app/src/main/java/com/nv/pair/data/ClusterRepository.kt
android/app/src/main/java/com/nv/pair/data/PairNode.kt
android/app/src/main/java/com/nv/pair/data/PairRepository.kt
android/app/src/main/java/com/nv/pair/data/RouterRepository.kt
android/app/src/main/java/com/nv/pair/rpc/ClusterApi.kt
android/app/src/main/java/com/nv/pair/rpc/BrokerApi.kt
android/app/src/main/java/com/nv/pair/runtime/PairRuntimeController.kt
android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt
android/app/src/main/java/com/nv/pair/models/ModelSourceAdapters.kt
android/app/src/main/java/com/nv/pair/models/ModelRepositories.kt
android/app/src/main/java/com/nv/pair/models/ModelDescriptor.kt
android/app/src/main/java/com/nv/pair/models/ModelHubInstaller.kt
android/app/src/main/java/com/nv/pair/models/ModelHubScreen.kt
android/app/src/main/java/com/nv/pair/mnn/MnnModelManager.kt
android/app/src/main/java/com/nv/pair/mnn/MnnModelCatalog.kt
android/app/src/main/java/com/nv/pair/mnn/MnnRuntimeContainer.kt
android/app/src/main/java/com/nv/pair/mnn/MnnEngineHost.kt
android/app/src/main/java/com/nv/pair/mnn/http/MnnHttpServer.kt
android/app/build.gradle.kts
android/scripts/run-m10-pc-to-android-acceptance.ps1
android/app/src/androidTest/java/com/nv/pair/M10PcToAndroidMnnInstrumentedTest.kt
android/app/src/androidTest/java/com/nv/pair/M65AndroidToPcRoutingInstrumentedTest.kt
services/nvpair-cluster-manager/invite.go
services/nvpair-cluster-manager/respond.go
services/nvpair-cluster-manager/httpserver.go
services/nvpair-cluster-manager/membership.go
services/nvpair-cluster-manager/truststore.go
services/nvpair-cluster-manager/manager.go
services/nvpair-cluster-manager/leave.go
services/nvpair-cluster-manager/rpcerrors.go
services/nvpair-cluster-manager/README.md
services/nvpair-proxy/gateway.go
shared/modelselection/selector.go
```

**路径变更须更新本文档和测试引用，不得为了对齐文档强行移动实际上无需移动的目录。**

# 附录 B：外部技术依据

- ModelScope 官方技能文档：`https://github.com/modelscope/modelscope-skills/blob/main/skills/ms-hub/SKILL.md`（OpenAPI base、`owner`、`search`、分页参数；真实生产请求仍需 smoke）
- Hugging Face Hub API：`https://huggingface.co/docs/huggingface_hub/package_reference/hf_api`（repo tree、metadata、revision、分页）
- Android 通知权限：`https://developer.android.com/develop/ui/views/notifications/notification-permission`（拒绝 POST_NOTIFICATIONS 不等于不能启动 FGS）
- Android FGS 启动限制：`https://developer.android.com/develop/background-work/services/fgs/launch`
- EdgeMesh 对照路径：`https://github.com/afantastic1/EdgeMeshAi/tree/feature/shared-runtime/cpp/modelhub`

---

**结论**：M12.5 的本质不是修几个界面按钮，而是固定两个完整事务：**配对只发生一次并以持久安全事实为准**；**模型只有经过 MNN package 验证和原子安装才进入 Runtime**。先修 Stop/Leave 和 pairing 的事实同步，再重构 Model Hub，最后验证 Android↔PC 的双向路由，这样最利于 AI 逐步施工、代码审查和后续维护。
