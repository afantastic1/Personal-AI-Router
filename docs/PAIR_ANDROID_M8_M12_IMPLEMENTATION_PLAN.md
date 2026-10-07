<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Android 双向算力 + 单一 API 施工计划

> **适用分支**：`feature/android-build`  
> **基线提交**：`98d4cf572b7ecb86f3e38eeab17ff8c222242e8d` (`M7`)  
> **开发方式**：AI Agent 主导编码  
> **核心产品验收**：手机上的第三方对话软件只连接本机 PAIR 的一个 OpenAI-compatible URL，PAIR 自动发现并把请求路由到手机、电脑或未来其他节点；第三方软件不需要知道 Ollama / LM Studio / MNN、设备 IP 或实际执行节点。

---

# 0. 这版计划修正了什么

旧计划主要完成：

```text
M8  MNN HTTP
M9  engine=mnn
M10 PC → Android Compute
```

这只能完整解决：

```text
电脑可以使用手机 MNN 算力
```

但最终产品还要求：

```text
手机上的 Chat App
      ↓
一个本地 URL
      ↓
PAIR 自动查找模型
      ↓
手机 / PC / AI Box / NAS
```

因此路线修正为：

```text
M6.5  手机 → PC 现有链路 E2E 验收
M8    MNN Local HTTP Engine
M9    PAIR engine=mnn
M10   PC → Android Compute
M11   Unified Local OpenAI Gateway
M12   Model Catalog + Auto Model + Model Hub
M13   External LAN Gateway + Real API Key
M14   Reliability / Release
```

**M11 被提前到 Model Hub 之前。**

原因：

> “第三方对话软件只填一个 URL 就能自动路由到电脑”是核心产品能力，优先级高于模型下载商城。

---

# 1. 最终用户体验

手机安装 PAIR。

电脑安装 PAIR，并运行：

```text
Ollama
或
LM Studio
```

手机与电脑完成 PAIR 配对。

手机上的 Chat App 只配置：

```text
Base URL:
http://127.0.0.1:14326/v1

API Key:
pair-local

Model:
qwen3-8b
```

之后：

```text
Chat App
   ↓
PAIR Unified Gateway
   ↓
查询整个 PAIR 网络
   ↓
发现：
  Phone MNN:
      qwen3-1.7b

  PC Ollama:
      qwen3-8b
      deepseek-r1:8b

  PC LM Studio:
      gemma-3-12b
   ↓
qwen3-8b 所在位置 = PC Ollama
   ↓
PAIR 自动路由
   ↓
PC 推理
   ↓
SSE 返回手机 Chat App
```

用户不需要配置：

```text
192.168.x.x
11434
1234
14324
ollama
lmstudio
mnn
```

用户只需要：

```text
URL
KEY
MODEL
```

---

# 2. 最终架构

```text
┌───────────────────────────────────────────┐
│ Third-party Chat App / TAVO / OpenAI SDK │
└──────────────────────┬────────────────────┘
                       │
                       │ OpenAI-compatible HTTP
                       ▼
┌───────────────────────────────────────────┐
│          PAIR Unified Gateway             │
│              :14326                       │
│                                           │
│ model → engine → node resolution          │
└──────────────────────┬────────────────────┘
                       │
             reuse existing PAIR proxy
                       │
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
    Ollama          LM Studio          MNN
    facade           facade           facade
       │               │               │
       └───────────────┼───────────────┘
                       │
              PAIR scheduler
                       │
              discovery / cluster
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
        PC                        Android
  Ollama / LM Studio                 MNN
```

---

# 3. 三条绝对架构边界

所有 AI Agent 必须遵守。

## 3.1 Android owns MNN lifecycle

唯一 Owner：

```text
Android/Kotlin
```

不是：

```text
nvpair-engine-manager
nvpair-ui-broker
nvpair-proxy
```

---

## 3.2 HTTP is inference data-plane boundary

推理数据：

```text
prompt
messages
token stream
response
```

只走 HTTP。

禁止通过 PAIR JSON-RPC 搬运推理正文。

---

## 3.3 PAIR owns routing/discovery/scheduling

MNN 不实现自己的：

```text
节点发现
cluster
跨设备 scheduler
failover
信任关系
```

全部复用 PAIR。

---

# 4. 不允许形成的错误架构

禁止：

```text
Chat App
 ↓
Android 自己写一套路由器
 ↓
PC
```

同时又有：

```text
PAIR Router
```

最终只能存在一套路由核心：

```text
PAIR Proxy + Scheduler
```

---

# 5. Engine 与端口冻结

## 5.1 PAIR 已有服务

```text
14318  node-info
14319  errors
14320  workload
14321  cluster
14322  engine-manager models
14323  engine-manager control
```

## 5.2 MNN

```text
14324  MNN PAIR facade
14325  MNN loopback backend
```

## 5.3 Unified Gateway

```text
14326  PAIR unified local OpenAI API
```

### M8～M12 绑定规则

```text
14325 -> 127.0.0.1 only
14326 -> 127.0.0.1 only
```

M13 才允许外部 LAN bind。

---

# 6. Engine identities

最终 PAIR engine：

```text
ollama
lmstudio
mnn
```

MNN：

```text
Name             = mnn
DisplayName      = MNN
DiscoveryService = mn
FacadePort       = 14324
BackendPort      = 14325
ModelNaming      = exact
```

Unified Gateway：

```text
不是 engine
```

不要增加：

```text
engine=gateway
```

Gateway 是本机客户端入口，不参与模型所有权。

---

# 7. 开工前先做 M6.5

在继续 M8 前，必须验证现有：

```text
手机 → PC
```

路由链。

原因：

如果这条链本身有问题，等 M8～M10 写完再发现，会无法判断：

```text
是 Android runtime 问题
还是 proxy 问题
还是 discovery 问题
还是 MNN 问题
```

---

# 8. M6.5 — Android → PC E2E Gate

## 8.1 环境

PC：

```text
PAIR
Ollama
qwen3:0.6b / 1.7b / 其他小模型
```

Android：

```text
PAIR App
```

两者：

```text
同一 LAN
完成 cluster pairing
```

---

## 8.2 验收

Android 必须：

```text
发现 PC
看到 PC modelsByEngine
看到 Ollama / LM Studio facade
```

然后通过 Android 本地 engine facade：

```text
Android
↓
PAIR Ollama facade
↓
PC
↓
Ollama
```

发起一次：

```text
/v1/chat/completions
stream=true
```

或当前该 engine 已支持的推理 endpoint。

必须证明：

```text
[ ] request 到 PC
[ ] workload scheduledOn = PC
[ ] response stream 返回 Android
[ ] PC engine 执行
[ ] Android 不直接连接 PC engine backend
```

---

# 9. M6.5 不做什么

不做：

```text
Unified Gateway
MNN
Auto Model
Model Hub
```

只证明已有 PAIR Router 的：

```text
Android → PC
```

方向是健康的。

---

# 10. M8 — MNN Local HTTP Engine

目标：

> 把已经完成的 M7 MNN Runtime 变成一个可靠的 loopback OpenAI-compatible Engine。

---

# 11. M8 保留现有 Runtime

不得重新设计：

```text
MnnModelDescriptor
MnnBackend
MnnRuntime
MnnEngineHost
MnnModelManager
NativeMnn
NativeMnn.cpp
```

新增 Adapter 层：

```text
HTTP
 ↓
MnnInferenceService
 ↓
MnnEngineHost
 ↓
NativeMnn
```

---

# 12. M8 建议目录

```text
android/app/src/main/java/com/nv/pair/mnn/
├── MnnModels.kt
├── MnnRuntime.kt
├── MnnModelManager.kt
├── MnnEngineHost.kt
├── MnnInferenceService.kt
├── MnnModelCatalog.kt
└── http/
    ├── MnnHttpServer.kt
    ├── MnnHttpModels.kt
    ├── OpenAiRequestParser.kt
    ├── OpenAiResponseWriter.kt
    └── SseStream.kt
```

---

# 13. MnnInferenceService

HTTP 层禁止直接控制 native runtime。

建议接口：

```kotlin
interface MnnInferenceService {
    fun listModels(): List<MnnModelDescriptor>

    fun status(): MnnRuntimeStatus

    fun ensureLoaded(
        modelId: String,
        backend: MnnBackend
    ): MnnResult<MnnLoadedModel>

    fun generate(
        requestId: Long,
        request: MnnChatRequest,
        onToken: (String) -> Unit
    ): MnnResult<MnnGenerationResult>

    fun cancel(requestId: Long)

    fun unload(): MnnResult<Unit>
}
```

---

# 14. MNN 模型并发策略

M8～M12 固定：

```text
一个 MNN Runtime
一个 loaded model
一个 active generation
```

不要提前做：

```text
multi-model residency
continuous batching
parallel decode
```

第二个同时 generation：

```http
409 engine_busy
```

PAIR Scheduler 后续应避免把多个任务同时打到单槽手机。

---

# 15. OpenAI Chat 消息

当前 M7 的：

```text
prompt: String
```

必须升级为结构化 chat。

新增：

```kotlin
data class MnnChatMessage(
    val role: MnnChatRole,
    val content: String
)
```

```kotlin
data class MnnChatRequest(
    val modelId: String,
    val messages: List<MnnChatMessage>,
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val seed: Int?
)
```

---

# 16. Chat Template 必须由 MNN 处理

禁止：

```text
Kotlin 拼 ChatML
Kotlin 拼 Qwen 特殊 token
```

JNI 应把规范化 messages 转换为：

```text
MNN ChatMessages
```

再：

```text
apply_chat_template(ChatMessages)
```

因此 NativeMnn 可做最小扩展：

```text
nativeGenerateChat(...)
```

但不允许重写整个 NativeMnn 生命周期。

---

# 17. M8 HTTP API

必须实现：

```text
GET  /healthz
GET  /v1/models
POST /v1/chat/completions

GET  /internal/models/loaded
POST /internal/models/load
POST /internal/models/unload
```

绑定：

```text
127.0.0.1:14325
```

---

# 18. `/v1/models`

标准 OpenAI shape：

```json
{
  "object": "list",
  "data": [
    {
      "id": "qwen3-1.7b",
      "object": "model",
      "owned_by": "local"
    }
  ]
}
```

---

# 19. `/v1/chat/completions`

M8 必须支持：

```text
model
messages
stream
max_tokens
temperature
top_p
seed
```

roles：

```text
system
user
assistant
```

M8 暂不支持：

```text
tools
function calling
vision
audio
structured output
logprobs
```

请求这些能力时必须明确：

```http
400
```

不能静默忽略。

---

# 20. SSE

Streaming：

```text
Content-Type: text/event-stream
```

每个 chunk：

```text
data: {...}

```

结束：

```text
data: [DONE]

```

必须满足：

```text
第一个 token 在整个生成结束前到达客户端
```

---

# 21. HTTP disconnect → native cancel

必须完整：

```text
client disconnect
↓
HTTP write fails / request context cancelled
↓
MnnInferenceService.cancel(requestId)
↓
MnnEngineHost.cancel
↓
NativeMnn.cancel
↓
MNN generation exits
```

验收：

```text
下一请求仍可成功
```

---

# 22. M8 Lifecycle

MNN HTTP Engine 属于：

```text
PairRuntimeService
```

不新建第二个 ForegroundService。

启动：

```text
PairRuntimeService
↓
MNN runtime container
↓
MNN HTTP :14325
↓
health ready
↓
PAIR broker
```

停止：

```text
stop accepting HTTP
↓
cancel active generation
↓
unload
↓
close :14325
↓
PAIR shutdown
```

---

# 23. M8 Definition of Done

```text
[ ] /healthz
[ ] /v1/models
[ ] chat non-stream
[ ] chat stream
[ ] structured messages
[ ] MNN chat template
[ ] client cancel
[ ] load / unload
[ ] model switch
[ ] CPU pass
[ ] OpenCL typed unsupported / pass
[ ] unit tests
[ ] lint
[ ] real-device HTTP acceptance
```

---

# 24. M9 — MNN 成为 PAIR First-class Engine

M9 目标：

```text
PAIR Core 正式认识 engine=mnn
```

---

# 25. M9 的关键架构：hosted runtime

当前 engine-manager：

```text
process
command
```

增加：

```text
hosted
```

定义：

```text
process
    engine-manager owns process

command
    engine-manager owns daemon lifecycle by command

hosted
    parent application owns lifecycle
    engine-manager only probes / queries it
```

MNN 使用：

```text
hosted
```

---

# 26. hosted engine 可以做什么

允许：

```text
status
health
list_models
loaded_models
HTTP actions
```

禁止：

```text
install
uninstall
start
stop
restart
set-port
launch editing
StopAll
RestoreEnabled
```

这些请求必须：

```text
明确 unsupported
```

而不是 no-op success。

---

# 27. MNN manifest

新增：

```text
services/nvpair-engine-manager/manifests/mnn.json
```

核心：

```json
{
  "engine": "mnn",
  "display_name": "MNN",
  "manifest_version": 1,
  "platforms": {
    "android/arm64": {
      "runtime": {
        "mode": "hosted",
        "port": 14325,
        "bind": "127.0.0.1",
        "ready": {
          "http": "http://127.0.0.1:{port}/healthz",
          "status": 200
        },
        "health": {
          "http": "http://127.0.0.1:{port}/healthz",
          "status": 200
        }
      }
    }
  }
}
```

actions：

```text
list_models
loaded_models
```

---

# 28. Android 必须打包 engine-manager

当前 Android staging 增加：

```text
nvpair-engine-manager
```

涉及：

```text
android/scripts/stage-native-binaries.ps1
NativeBinaryRegistry.kt
BrokerSession.kt
```

Broker 启动参数增加：

```text
--engine-manager-path
```

---

# 29. shared engine identity

修改：

```text
services/shared/engines/engines.go
```

加入：

```text
mnn
```

---

# 30. noderec

新增：

```go
ServiceMNN ServiceKey = "mn"
```

最终 Android mDNS：

```text
mn=14324
```

注意：

```text
14324 = PAIR facade
14325 = private backend
```

绝不能广告 backend。

---

# 31. Proxy MNN Profile

增加：

```text
engine=mnn
```

只声明真实支持的 routes：

```text
GET  /v1/models
POST /v1/chat/completions
```

MNN：

```text
ModelNaming = exactID
```

---

# 32. Broker ownership 增加 hostedEngine

最终：

```text
adoptedEngine
managedEngine
hostedEngine
```

MNN：

```text
Ownership = hostedEngine
HealthProbePath = /healthz
```

---

# 33. 不允许复制第三份 broker 特例

禁止新增：

```text
reconcileAdvertiseMNN()
runAutoAdvertiseMNN()
mnnProxyListenPort()
prepareManagedMNNFacade()
```

应把可泛化部分改成：

```text
reconcileEngineAdvertisement(profile)
```

只保留真正 engine-specific 的：

```text
Ollama OLLAMA_HOST
LM Studio lifecycle quirks
```

---

# 34. M9 Advertisement

只有：

```text
MNN backend healthy
AND
MNN PAIR facade ready
```

才能：

```text
advertise mn=14324
```

否则立即：

```text
withdraw mn
```

---

# 35. M9 Model Discovery

engine-manager：

```text
/v1/models
```

必须返回：

```json
{
  "models": ["qwen3-1.7b"],
  "modelsByEngine": {
    "mnn": ["qwen3-1.7b"]
  }
}
```

PC 必须能发现：

```text
Android
└── mnn
    └── qwen3-1.7b
```

---

# 36. M9 Definition of Done

```text
[ ] generic hosted mode
[ ] hosted lifecycle tests
[ ] mnn manifest
[ ] Android packages engine-manager
[ ] shared engines includes mnn
[ ] noderec includes mn
[ ] proxy mnn facade
[ ] broker hosted ownership
[ ] backend+facade health gates advertisement
[ ] PC discovers Android mnn model
```

---

# 37. M10 — PC → Android Compute

目标：

```text
PC 上 PAIR
↓
自动路由
↓
Android MNN
```

---

# 38. M10 请求链

```text
PC client
↓
PAIR mnn facade
↓
model=qwen3-1.7b
↓
discovery
↓
Android advertises mnn + model
↓
scheduler
↓
Android
↓
cluster mTLS
↓
Android mnn facade :14324
↓
MNN backend :14325
↓
MNN
```

---

# 39. M10 Streaming

必须：

```text
MNN token
↓
MNN SSE
↓
Android proxy
↓
cluster transport
↓
PC proxy
↓
PC client
```

禁止整段 buffer 后再返回。

---

# 40. M10 Cancellation

必须验证：

```text
PC client disconnect
↓
PC proxy cancel
↓
Android proxy disconnect
↓
MNN HTTP cancel
↓
NativeMnn cancel
```

---

# 41. M10 Definition of Done

```text
[ ] PC sees Android mnn
[ ] PC sees Android model
[ ] PC request routes to Android
[ ] Android executes MNN
[ ] SSE arrives before completion
[ ] workload engine=mnn
[ ] workload scheduledOn=Android
[ ] disconnect stops MNN
[ ] next request succeeds
```

完成后：

```text
双向算力 MVP
```

成立：

```text
Phone → PC ✅
PC → Phone ✅
```

但还没有：

```text
单一 URL ✅
```

所以继续 M11。

---

# 42. M11 — Unified Local OpenAI Gateway

这是本次修正最重要的新阶段。

目标：

> Chat App 不再需要知道 Ollama / LM Studio / MNN。

---

# 43. M11 不新增独立 nvpair-gateway 进程

**推荐架构：Unified Gateway 直接实现于现有 `nvpair-proxy` 进程。**

原因：

`nvpair-proxy` 已经拥有：

```text
各 engine discovery overlay
routing
scheduler integration
cluster transports
failover
workload emission
stream proxying
```

如果创建：

```text
nvpair-gateway
```

则必须重新实现：

```text
model discovery
engine routing
node routing
scheduler
failover
cluster transport
```

这会产生第二套数据面。

因此：

```text
Unified Gateway
=
nvpair-proxy 中的 process-scoped local facade
```

不是新 worker。

---

# 44. Unified Gateway 与 engine facade 的区别

现有：

```text
ollama facade
lmstudio facade
mnn facade
```

每一个都：

```text
engine-scoped
```

Unified Gateway：

```text
process-scoped
engine-agnostic
```

它不加入：

```text
engines.All()
```

也不参与：

```text
mDNS engine advertisement
```

---

# 45. M11 Listener

固定：

```text
127.0.0.1:14326
```

只服务本机第三方 App。

Broker 启动 proxy 后调用：

```text
gateway/enable
```

建议 JSON-RPC：

```json
{
  "port": 14326
}
```

返回：

```json
{
  "port": 14326
}
```

这是 process-scoped control method，不带 engine prefix。

---

# 46. M11 推荐目录

在：

```text
services/nvpair-proxy/
```

新增：

```text
gateway.go
gateway_models.go
gateway_routing.go
gateway_test.go
```

不要创建新的 Go module。

---

# 47. Gateway API

M11 必须提供：

```text
GET  /v1/models
POST /v1/chat/completions
```

暂时只做真正跨 engine 已支持的共同协议：

```text
OpenAI Chat Completions
```

不要在 M11 声称支持：

```text
embeddings
images
audio
Anthropic /v1/messages
```

除非所有被选择的目标都满足对应能力。

---

# 48. Unified `/v1/models`

返回整个 PAIR 网络可用模型的 union。

例：

```json
{
  "object": "list",
  "data": [
    {
      "id": "qwen3-1.7b",
      "object": "model",
      "owned_by": "pair"
    },
    {
      "id": "qwen3-8b",
      "object": "model",
      "owned_by": "pair"
    }
  ]
}
```

第三方 Chat App 看不到：

```text
engine
node
IP
port
```

---

# 49. Gateway Model Directory

Gateway 必须基于现有各 facade 的 discovery directory 构建：

```text
model
→
candidate engines
→
candidate nodes
```

例：

```text
qwen3-8b
 ├── ollama
 │    ├── PC-A
 │    └── PC-B
 └── lmstudio
      └── PC-C
```

不能独立重新扫描 LAN。

---

# 50. Explicit Model Routing

M11 首先只保证：

```text
model=qwen3-8b
```

这种显式模型。

逻辑：

```text
1. 查询哪个 engine 提供 qwen3-8b
2. 得到可用 engine candidate
3. 选择 engine
4. 进入对应现有 facade routing
5. facade + scheduler 选择 node
6. 转发
```

---

# 51. 不能通过 localhost HTTP 再打一次自己的 engine facade

不推荐：

```text
gateway
↓ HTTP
127.0.0.1:11434
↓
same nvpair-proxy
```

这样会：

```text
多一次 HTTP hop
难做 cancellation ownership
难做 workload attribution
容易形成 loop
```

正确做法：

把现有 facade 内部的 request routing 提取为可复用函数：

```text
routeRequest(
    facade,
    request
)
```

然后：

```text
engine-specific listener
       ──────┐
             ├→ same routing core
gateway ─────┘
```

---

# 52. Gateway 不复制 Scheduler

Gateway 只做：

```text
model → engine
```

node selection 继续交给：

```text
engine facade + existing scheduler
```

不要在 Gateway 重新设计：

```text
node scoring
latency scoring
GPU pressure scoring
```

---

# 53. 同名模型存在多个 Engine 怎么办

例如：

```text
qwen3-8b
```

同时存在：

```text
PC-A Ollama
PC-B LM Studio
```

M11 必须 deterministic。

推荐顺序：

```text
1. 如果只有一个 engine -> 直接选
2. 如果多个 engine:
      优先存在 loaded instance 的 engine
3. 如果仍多个:
      使用可配置但稳定的 engine preference
4. engine 内部仍由 scheduler 选 node
```

默认 engine preference 建议：

```text
mnn
ollama
lmstudio
```

但这只是**tie-breaker**，不是“质量评分”。

必须集中定义，不得散落 if/else。

例如：

```text
gatewayEnginePreference
```

---

# 54. 为什么 M11 不做 `auto`

因为：

```text
auto
```

不是简单 routing。

它要求回答：

```text
哪个模型更快
哪个模型更强
哪个模型支持该上下文长度
哪个模型支持 vision/tool
模型大小
量化
设备性能
```

M11 没有足够 metadata。

如果现在硬写：

```text
从名字猜 8B > 1.7B
```

会形成脆弱策略。

因此 M11：

```text
一个 URL ✅
显式模型自动找 engine/node ✅
auto model ❌
```

---

# 55. M11 API Key

因为：

```text
14326
```

仅 loopback。

M11 不做真正认证。

对必须填写 API Key 的第三方 App：

推荐填写：

```text
pair-local
```

Gateway：

```text
允许无 Authorization
也允许任意 Bearer header
```

不要把：

```text
pair-local
```

当作真实安全机制。

真正 API Key 在 M13 外部 LAN bind 时实现。

---

# 56. M11 Android UI

最小增加一个：

```text
Local API
```

卡片：

```text
Status: Running

URL:
http://127.0.0.1:14326/v1

Key:
pair-local

Models:
N
```

提供复制按钮即可。

不要做：

```text
完整 API Gateway 设置页
```

---

# 57. M11 手机对话软件验收

手机 Chat App 配：

```text
Base URL:
http://127.0.0.1:14326/v1

Key:
pair-local

Model:
qwen3-8b
```

手机本地没有：

```text
qwen3-8b
```

PC 有。

请求：

```text
Chat App
↓
14326
↓
Gateway
↓
resolve qwen3-8b -> ollama
↓
PAIR
↓
PC
↓
Ollama
↓
stream back
```

必须做到。

这就是用户最关心的：

> **手机上的对话软件自动路由到电脑。**

---

# 58. M11 Definition of Done

```text
[ ] proxy process owns gateway listener
[ ] gateway binds loopback :14326
[ ] /v1/models unions all engines/nodes
[ ] /v1/chat/completions accepts explicit model
[ ] model resolves to engine
[ ] selected engine reuses existing routing core
[ ] node selection still uses existing scheduler
[ ] stream passes through
[ ] cancel passes through
[ ] workload attribution remains correct
[ ] Android UI exposes URL/key
[ ] real third-party Chat App routes to PC
[ ] same endpoint can route to Android MNN when requested model exists there
```

完成 M11 后，真正达到：

```text
single URL
+
automatic device routing
```

---

# 59. M12 — Model Catalog + Auto Model + Model Hub

M11 完成后才做：

```text
auto
auto-fast
auto-balanced
auto-best
```

以及：

```text
ModelScope
HuggingFace
local import
```

---

# 60. M12 为什么要把 Auto Model 和 Model Catalog 放一起

Auto Model 需要 metadata。

统一定义：

```text
ModelDescriptor
```

至少包含：

```text
logicalId
engineModelId
displayName
family
parameterCount
quantization
contextLength
capabilities
source
format
estimatedMemory
compatibility
```

运行时观测：

```text
loaded
node
engine
tokensPerSecond
TTFB
memoryPressure
```

---

# 61. Model Catalog 与 Runtime Inventory 分离

必须区分：

```text
Catalog
=
“世界上/库里有什么模型”

Inventory
=
“当前 PAIR 网络实际可用什么模型”
```

Auto selector 只能从：

```text
Inventory
```

里选。

Catalog 只提供 metadata。

---

# 62. Model Hub

M12 Adapter：

```text
ModelScopeAdapter
HuggingFaceAdapter
LocalImportAdapter
```

统一：

```text
ModelSource
```

不要把站点 API 逻辑写进 UI。

---

# 63. Auto aliases

建议：

```text
auto-fast
auto-balanced
auto-best
```

以及：

```text
auto
```

默认映射：

```text
auto -> auto-balanced
```

---

# 64. Auto selector Pipeline

必须：

```text
Request requirements
↓
Capability filter
↓
Availability filter
↓
Memory / compatibility filter
↓
Policy score
↓
model
↓
engine
↓
existing PAIR node scheduler
```

注意：

```text
AutoModelSelector
```

只选择：

```text
model + engine class
```

不要复制 node scheduler。

---

# 65. auto-fast

目标：

```text
低延迟
```

优先：

```text
already loaded
低 TTFB
高 tokens/s
小模型
本机（如果性能合适）
```

---

# 66. auto-best

目标：

```text
能力优先
```

优先：

```text
capability match
更高模型等级
足够 context
足够内存
```

性能只做可用性约束。

---

# 67. auto-balanced

综合：

```text
quality
latency
loaded state
network cost
device pressure
```

权重必须集中定义、可测试。

禁止散落 magic number。

---

# 68. Auto Model 必须可解释

内部路由记录：

```text
requestedModel = auto-balanced
resolvedModel  = qwen3-8b
resolvedEngine = ollama
resolvedNode   = DESKTOP-A
```

但日志不能记录 prompt。

UI 可以显示：

```text
auto-balanced
→ qwen3-8b · PC
```

---

# 69. M12 Model Hub 下载

只有这时实现：

```text
download
resume
hash
install
delete
```

MNN 模型安装到：

```text
app-private model root
```

不要重新使用公共 `/sdcard/Android/data` 路线。

---

# 70. M12 Definition of Done

```text
[ ] ModelDescriptor domain
[ ] catalog vs inventory separation
[ ] ModelScope adapter
[ ] HuggingFace adapter
[ ] local import
[ ] MNN install
[ ] download resume
[ ] verification
[ ] auto-fast
[ ] auto-balanced
[ ] auto-best
[ ] auto alias
[ ] policies tested
[ ] gateway resolves aliases
```

---

# 71. M13 — External LAN Gateway

M11 的：

```text
127.0.0.1:14326
```

升级为可选：

```text
0.0.0.0:<gateway-port>
```

默认：

```text
OFF
```

---

# 72. M13 必须增加真实 API Key

使用：

```text
Android Keystore
```

生成随机 key。

不再使用：

```text
pair-local
```

外部访问必须：

```text
Authorization: Bearer <real key>
```

---

# 73. M13 必须做

```text
auth
rate limit
selected network
bind controls
API key rotation
copy/revoke UI
```

---

# 74. M14 Reliability

最后：

```text
battery
soak
memory
Wi-Fi reconnect
process restart
cluster recovery
upgrade
signing
APK/AAB
desktop installer
```

---

# 75. 数据流最终形态

## 手机 Chat App → PC

```text
Chat App
↓
127.0.0.1:14326
↓
Unified Gateway
↓
model qwen3-8b
↓
engine Ollama
↓
PAIR scheduler
↓
PC
↓
Ollama
```

---

## PC → 手机

```text
PC App
↓
PAIR
↓
model qwen3-1.7b
↓
engine MNN
↓
scheduler
↓
Android
↓
MNN
```

---

## 手机 → 手机本地

```text
Chat App
↓
14326
↓
qwen3-1.7b
↓
MNN
↓
same phone
```

第三方应用不需要知道差别。

---

# 76. 最终“Wi-Fi 算力”语义

用户只面对：

```text
PAIR Network
```

不是：

```text
Ollama
LM Studio
MNN
PC
Phone
```

逻辑：

```text
model
↓
PAIR finds compute
↓
PAIR executes
```

---

# 77. 文件级施工顺序

## Gate 0

验证：

```text
Android → PC
```

不改架构。

---

## Phase A — M8 Contracts

修改/新增：

```text
MnnModels.kt
MnnInferenceService.kt
MnnModelCatalog.kt
mnn/http/*
tests
```

先写测试。

---

## Phase B — M8 structured chat JNI

修改：

```text
NativeMnn.kt
NativeMnn.cpp
NativeMnnContractInstrumentedTest
```

---

## Phase C — M8 HTTP

实现：

```text
:14325
health
models
chat
SSE
cancel
```

---

## Phase D — M9 hosted runtime

修改：

```text
nvpair-engine-manager
```

先做 generic hosted。

---

## Phase E — M9 identities

修改：

```text
shared/engines
noderec
mnn manifest
proxy engine profile
broker ownership
```

---

## Phase F — M9 Android package

加入：

```text
nvpair-engine-manager
```

---

## Phase G — M10 E2E

PC → Android。

---

## Phase H — M11 Unified Gateway refactor

先重构：

```text
existing facade routing
```

成为可复用 routing core。

必须保证现有 Ollama / LM Studio tests 不变绿。

---

## Phase I — M11 gateway listener

加入：

```text
gateway/enable
127.0.0.1:14326
/v1/models
/v1/chat/completions
```

---

## Phase J — M11 real Chat App acceptance

手机第三方 App → PC。

---

## Phase K — M12 Catalog / Hub / Auto

最后做模型层。

---

# 78. Git Commit 建议

```text
01 test(android): add phone-to-pc routing acceptance
02 fix(...): make existing android-to-pc route green

03 test(mnn): define MNN HTTP contracts
04 feat(mnn): support structured chat messages
05 feat(android): add loopback MNN HTTP engine
06 feat(android): bind MNN engine to runtime service

07 feat(engine-manager): add hosted runtime mode
08 feat(pair): add MNN engine identity
09 feat(android): package engine-manager
10 feat(broker): advertise hosted MNN

11 test(pair): add PC-to-Android MNN E2E
12 fix(...): close M10 E2E defects

13 refactor(proxy): extract reusable engine routing core
14 feat(proxy): add unified local gateway
15 feat(android): expose local PAIR API endpoint
16 test(pair): add phone-chat-app-to-PC acceptance

17 feat(models): add model catalog domain
18 feat(models): add ModelScope adapter
19 feat(models): add HuggingFace adapter
20 feat(models): add local model import
21 feat(router): add auto model policies
```

每个 commit：

```text
必须编译
```

---

# 79. AI Agent 开工固定步骤

每一个 task：

```bash
git status --short
git branch --show-current
git rev-parse HEAD
```

然后读：

```text
AGENTS.md
相关 spec.md
相关 README
相关 tests
```

再写：

```text
Current boundary
Files to modify
Files not to modify
New interfaces
Tests
Rollback point
```

再开始改代码。

---

# 80. AI Agent 禁止事项

未经明确批准禁止：

```text
升级 MNN
升级 NDK
升级 AGP
升级 Kotlin
升级 compileSdk
升级 targetSdk

引入 gRPC
引入 WebSocket
引入第二套 discovery
引入第二套 scheduler
新建 Android routing protocol
新建 nvpair-gateway worker
让 broker 承载 inference body
让 engine-manager own MNN JNI
```

---

# 81. 测试金字塔

每阶段必须有：

```text
Unit
↓
Component
↓
Cross-process
↓
Real device / PC E2E
```

不能只靠：

```text
assembleDebug SUCCESS
```

判断功能完成。

---

# 82. M11 最关键回归矩阵

统一 Gateway 加入后，必须保证原有 facade 仍工作。

```text
Ollama direct facade     ✅
LM Studio direct facade  ✅
MNN direct facade        ✅
Unified gateway          ✅
```

Gateway 是增量能力。

不能破坏：

```text
现有 Ollama compatibility
LM Studio compatibility
```

---

# 83. 路由不允许形成环

必须测试：

```text
Gateway
↓
engine facade
↓
remote node
```

绝不能：

```text
Gateway
↓
local facade HTTP
↓
Gateway
```

或者：

```text
PC Proxy
↓
Android Proxy
↓
PC Proxy
```

每个 upstream 必须明确：

```text
local backend
or
remote engine facade
```

---

# 84. Workload 归属

Unified Gateway 请求仍必须只生成一条逻辑 workload。

不能：

```text
gateway workload
+
engine facade workload
```

重复记两次。

理想：

```text
requested_model
resolved_model
engine
scheduledOn
```

都属于同一 workload。

---

# 85. 日志安全

绝对禁止：

```text
prompt
messages content
assistant response
token chunk
pair PIN
private key
real external API key
```

允许：

```text
request id
model
resolved engine
resolved node
TTFB
duration
token count
error code
```

---

# 86. 当前路线 Definition of Product MVP

真正可以称为产品 MVP，需要：

```text
[✓] Phone → PC
[✓] PC → Phone
[✓] Phone → Phone
[✓] one local URL
[✓] explicit model auto engine/node routing
[✓] streaming
[✓] cancellation
[✓] cluster trust
```

也就是：

```text
M11 DONE
```

**不是 M10。**

M10 是：

```text
双向算力网络技术 MVP
```

M11 才是：

```text
用户可使用产品 MVP
```

---

# 87. 推荐验收场景

## Scenario A

手机：

```text
没有 qwen3-8b
```

电脑：

```text
Ollama qwen3-8b
```

Chat App：

```text
URL  = 127.0.0.1:14326/v1
MODEL = qwen3-8b
```

结果：

```text
PC 执行
```

---

## Scenario B

手机：

```text
MNN qwen3-1.7b
```

电脑：

```text
无 qwen3-1.7b
```

同一个 URL：

```text
MODEL = qwen3-1.7b
```

结果：

```text
手机执行
```

---

## Scenario C

手机、电脑都有：

```text
qwen3-1.7b
```

Gateway：

```text
确定 engine candidate
```

Engine facade / scheduler：

```text
确定 node
```

必须 deterministic。

---

## Scenario D

PC 离线。

手机：

```text
qwen3-1.7b
```

请求该模型：

```text
仍成功
```

请求 PC-only 模型：

```text
标准 model_unavailable
```

不能卡死。

---

# 88. M8 Agent Prompt

```text
Read AGENTS.md and PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md completely.

Current milestone: M8 only.

M6.5 phone-to-PC routing gate must already have been checked.

Goal:
Turn the existing M7 MNN runtime into a loopback-only OpenAI-compatible
HTTP engine on 127.0.0.1:14325.

Architecture:
HTTP -> MnnInferenceService -> MnnEngineHost -> NativeMnn.

Android owns MNN lifecycle.
Do not implement engine=mnn yet.
Do not implement unified gateway yet.
Do not implement Model Hub.

Support:
/healthz
/v1/models
/v1/chat/completions
/internal/models/loaded
/internal/models/load
/internal/models/unload

Streaming must use SSE.
Client disconnect must cancel native generation.
Structured chat messages must use MNN's chat-template implementation,
not hand-written ChatML.

Finish only when M8 Definition of Done is green.
```

---

# 89. M9 Agent Prompt

```text
Read AGENTS.md and PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md completely.

M8 must be green.

Current milestone: M9 only.

Add a generic engine-manager runtime mode:
hosted

Hosted engines are owned by the parent application.
engine-manager may probe/query them but never install/start/stop/restart/set-port them.

Add MNN as a first-class PAIR engine:
engine=mnn
ServiceMNN=mn
facade=14324
backend=14325

Package nvpair-engine-manager in Android.

Do not create MNN-specific discovery/scheduler protocols.
Do not implement unified gateway yet.

M9 ends when a PC discovers the Android node with:
modelsByEngine.mnn
```

---

# 90. M10 Agent Prompt

```text
Read AGENTS.md and PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md completely.

M8 and M9 must be green.

Current milestone: M10 only.

Goal:
Prove PC PAIR can route an OpenAI-compatible streaming request to Android MNN.

Use explicit model ID.
Reuse existing scheduler.
Do not implement auto model.
Do not implement unified gateway yet.

Acceptance:
PC discovers Android MNN model.
PC request reaches Android.
Android MNN generates.
SSE returns before completion.
Client cancellation stops NativeMnn.
Subsequent request succeeds.
```

---

# 91. M11 Agent Prompt

```text
Read AGENTS.md and PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md completely.

M6.5, M8, M9 and M10 must be green.

Current milestone: M11 only.

Goal:
Expose one loopback OpenAI-compatible PAIR endpoint:
127.0.0.1:14326/v1

A third-party chat application must use this one endpoint without knowing
Ollama, LM Studio, MNN, device IPs or engine-specific ports.

Architecture requirement:
Implement the unified gateway inside the existing nvpair-proxy process.
Do NOT create a new nvpair-gateway worker.

The gateway is process-scoped, not an engine.

First refactor the existing engine facade routing path into reusable internal
routing code, keeping all existing direct facade tests green.

Gateway responsibilities:
model -> engine resolution only.

Existing engine facade + scheduler remain responsible for:
engine-specific routing -> node selection -> failover -> transport.

Support:
GET /v1/models
POST /v1/chat/completions

M11 uses explicit real model IDs only.
Do not implement auto/auto-fast/auto-best yet.

Bind only 127.0.0.1:14326.
No real auth yet; local clients may use pair-local as a compatibility key.

Final acceptance:
A real Android third-party chat app configured only with:
URL=http://127.0.0.1:14326/v1
KEY=pair-local
MODEL=<PC-only model>
must automatically route inference to the PC and stream the response back.
```

---

# 92. M12 Agent Prompt

```text
Read AGENTS.md and PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md completely.

M11 must be green.

Current milestone: M12 only.

Goal:
Add model metadata, Model Hub and auto model selection on top of the
already-working unified gateway.

Keep these domains separate:
Catalog = available model metadata/source
Inventory = models actually runnable in the PAIR network

Add:
ModelScope
HuggingFace
local import
download/resume/verify/install
auto-fast
auto-balanced
auto-best
auto -> auto-balanced

AutoModelSelector chooses model/engine policy only.
It must NOT duplicate the existing PAIR node scheduler.

Do not expose the gateway on LAN yet.
```

---

# 93. 架构冻结结论

最终数据路径：

```text
Third-party App
       │
       ▼
PAIR Unified Gateway :14326
       │
       ├─ resolve model → engine
       │
       ▼
existing PAIR engine facade
       │
       ├─ scheduler chooses node
       │
       ▼
cluster transport
       │
       ▼
target PAIR engine facade
       │
       ▼
actual backend
```

MNN：

```text
actual backend
=
Android loopback MNN HTTP :14325
```

Ollama：

```text
actual backend
=
PC loopback Ollama
```

LM Studio：

```text
actual backend
=
PC loopback LM Studio
```

第三方应用永远只看到：

```text
PAIR
```

---

# 94. 最终一句话原则

后续所有代码设计都必须满足：

> **Engine 负责“怎么运行模型”，PAIR 负责“模型在哪以及请求送到哪里”，Unified Gateway 负责“让用户只看到一个 API”。**

