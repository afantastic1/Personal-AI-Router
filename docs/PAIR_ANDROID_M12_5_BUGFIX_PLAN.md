<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Android M12.5 Bugfix / Hardening 施工计划

> **项目**：Personal-AI-Router  
> **分支**：`feature/android-build`  
> **审查基线**：`10a7c013dfbcec2f7dfbd1c02c08b37a754a00e1` (`M12`)  
> **文档目标**：将 M8～M12 从“主体代码已存在”收敛为“行为正确、失败可控、端到端可验收”的 Android MVP 基线。  
> **开发方式**：AI Agent 主导编码，因此本文档刻意写得比普通开发计划更严格：冻结架构、规定修改边界、要求先补失败测试、限制一次改动范围，并给出逐阶段验收门槛。

---

# 0. M12.5 的定位

M12 已经完成了大部分主干能力：

```text
Android MNN backend :14325
        ↓
engine-manager hosted mode
        ↓
MNN facade :14324
        ↓
PAIR discovery / scheduler
        ↓
Unified OpenAI Gateway :14326
        ↓
explicit model / auto aliases
        ↓
Model Hub
```

M12.5 **不是新功能里程碑**。

M12.5 的唯一目标是：

> **修掉 M8～M12 已有实现中的正确性、生命周期、降级、配置一致性和验收缺口。**

完成 M12.5 后，才进入：

```text
M13 External LAN Gateway + Real API Key
M14 Reliability / Release
```

禁止在 M12.5 顺手实现 M13。

---

# 1. M12.5 Definition of Done

全部满足才允许标记 M12.5 DONE。

```text
[ ] MNN native 不可用时 /healthz 不再假健康
[ ] MNN 单模型 load/generate 失败不会被误判为整个 runtime 永久死亡
[ ] MNN backend :14325 启动失败不会拖死整个 PAIR
[ ] MNN 不可用时不会发布 mn discovery
[ ] MNN 不可用时 Phone → PC 路由仍可工作

[ ] CPU / OpenCL 有唯一、持久化的用户选择
[ ] 第一次自动加载模型遵守该 backend
[ ] 模型切换后仍遵守该 backend
[ ] 同模型 backend 切换会在下一次请求前正确 reload
[ ] OpenCL unsupported 返回 typed failure，不静默回退 CPU

[ ] NativeMnn load 失败路径无明显 LLM object leak
[ ] MnnEngineHost 状态字段读写遵守统一锁规则
[ ] shutdown 不允许因为 native generation 卡死而无限阻塞 PAIR Stop

[ ] Model Hub 不把“缺少可信 SHA-256”的条目显示为可验证安装
[ ] 下载失败 / hash mismatch 不发布半成品模型目录
[ ] Hugging Face 普通 Git oid 不被误认为 SHA-256
[ ] ModelScope / HF / Local Import 的错误信息可区分

[ ] Go selector 是唯一 authoritative auto selector
[ ] Android 不再复制一套 auto 评分逻辑
[ ] engine 明确不支持的能力不能被模型名称 heuristic 覆盖
[ ] MNN 不会被 auto 选中处理 tools / vision / embeddings

[ ] Android Router UI 显示 ollama / lmstudio / mnn 三个 facade 状态
[ ] broker crash / stop 后三者状态全部 reset

[ ] OpenAI error type 不再把所有错误伪装成 invalid_request_error
[ ] 达到 max_tokens 时 finish_reason 至少能正确区分 length / stop，或明确保留为后续项并有测试覆盖当前语义

[ ] Android JVM unit tests green
[ ] Android lint green
[ ] assembleDebug green
[ ] Go affected packages tests green
[ ] SPDX / git diff checks green

[ ] Real device: Phone → PC
[ ] Real device: PC → Phone MNN
[ ] Real device: Phone → Phone MNN
[ ] Real device: Chat App → :14326 → PC
[ ] Real device: Chat App → :14326 → Android MNN
[ ] Real device: auto alias 至少完成一条跨设备成功路由
[ ] Real device: cancellation 后下一请求成功
```

**注意：**

```text
test file exists ≠ test passed
assembleDebug SUCCESS ≠ M12.5 DONE
instrumentation test 被默认 exclude ≠ acceptance passed
```

---

# 2. 架构冻结：AI Agent 不得改变

M12.5 必须继续遵守 M8～M12 的三条边界。

## 2.1 Android owns MNN lifecycle

MNN JNI / MNN model runtime 的唯一 owner：

```text
Android PairRuntimeService
```

不允许改成：

```text
engine-manager owns MNN
broker owns MNN
proxy owns MNN
```

engine-manager 对 MNN 仍然是：

```text
hosted runtime
probe / query only
```

---

## 2.2 HTTP is inference data plane

以下内容只走 HTTP：

```text
messages
prompt
token stream
completion body
```

禁止为了“修路由”把推理正文塞进 JSON-RPC。

---

## 2.3 PAIR owns routing / discovery / scheduling

仍然只有一套路由核心：

```text
Unified Gateway
    ↓
engine facade
    ↓
existing PAIR scheduler
    ↓
node
```

禁止：

```text
Android 写第二套 node selector
Model Hub 决定目标节点
MNN 自己发现 PC
```

---

# 3. M12.5 明确不做

未经单独批准不得：

```text
升级 MNN
升级 NDK
升级 AGP
升级 Kotlin
升级 compileSdk
升级 targetSdk

增加 gRPC
增加 WebSocket
增加新的 discovery 协议
增加新的 scheduler
增加 nvpair-gateway 独立进程
把 :14326 暴露到 LAN
实现真实 API key
实现多模型同时常驻
实现 continuous batching
实现多 token 并行 decode
实现跨节点联合推理
重写现有 PAIR proxy
```

当前固定端口不变：

```text
14324  MNN PAIR facade
14325  Android MNN loopback backend
14326  PAIR unified loopback OpenAI gateway
```

`14325` 和 `14326` 在 M12.5 继续只允许：

```text
127.0.0.1
```

---

# 4. AI Agent 固定工作纪律

每一个 Phase 开始前必须执行：

```bash
git status --short
git branch --show-current
git rev-parse HEAD
```

必须确认：

```text
branch = feature/android-build
```

然后阅读：

```text
AGENTS.md
docs/PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md
本 Phase 涉及的源文件
本 Phase 涉及的现有 tests
```

每个 Phase 必须按以下顺序：

```text
1. 写/修改失败测试，证明 bug 存在
2. 运行 focused test，确认测试确实失败
3. 写最小生产修复
4. focused test 变绿
5. 跑同模块完整测试
6. 检查 git diff
7. 才进入下一 Phase
```

禁止：

```text
先大规模重构再补测试
删除失败测试
@Ignore / skip 新测试
扩大 timeout 掩盖死锁
catch(Exception) 后返回 success
失败时静默 fallback 到 CPU
为了通过测试关闭 discovery / scheduler
复制一套旧逻辑到新文件
```

---

# 5. Bug 清单与优先级

| ID | 优先级 | 问题 | 主要风险 |
|---|---:|---|---|
| M12.5-01 | P0 | MNN `/healthz` 假健康 | 广播不可用 MNN，远端请求才失败 |
| M12.5-02 | P0 | MNN 启动失败拖死整个 PAIR | 本地 MNN 故障连 Phone→PC 都不可用 |
| M12.5-03 | P0 | CPU/OpenCL 选择未贯通自动加载 | 模型切换后偷偷回 CPU |
| M12.5-04 | P1 | Native load 异常路径资源安全不足 | 反复失败可能泄漏 |
| M12.5-05 | P1 | `MnnEngineHost` 状态访问不完全受锁保护 | 并发状态不一致 |
| M12.5-06 | P1 | Model Hub “verified install” 与实际 checksum 元数据不匹配 | 搜得到但大量装不了；或诱导降低校验 |
| M12.5-07 | P1 | Android / Go 各有一套 AutoModelSelector | UI 与实际 gateway 决策漂移 |
| M12.5-08 | P1 | capability 主要靠模型名猜 | MNN 可能被选去处理不支持的 tools/vision |
| M12.5-09 | P2 | Android Router UI 漏掉 MNN facade | 状态不可观测，调试误导 |
| M12.5-10 | P2 | shutdown 仍有无限等待 native 的路径 | PAIR Stop 可能卡死 |
| M12.5-11 | P2 | OpenAI error / finish_reason 语义不够准确 | 第三方客户端兼容性差 |
| M12.5-12 | Gate | 真机双向 / 单 URL E2E 没有可证明的绿灯 | 无法宣称产品 MVP 稳定 |

---

# 6. Phase A — 修正 MNN Health 语义

对应：

```text
M12.5-01
```

## 6.1 当前问题

当前：

```text
GET /healthz
```

基本总是：

```http
HTTP/1.1 200 OK
```

即使：

```text
NativeMnn.create()
→ native library unavailable
```

`MnnEngineHost` 仍可能以：

```text
UNLOADED
```

起步，而不是保留 runtime 初始化错误。

后果：

```text
MNN native 不可用
    ↓
:14325 HTTP listener 仍存在
    ↓
/healthz = 200
    ↓
engine-manager healthy=true
    ↓
broker 可能把 mn 当成可用 engine
```

---

## 6.2 正确健康语义

必须区分：

```text
Runtime availability
```

和：

```text
Last model/generation operation result
```

**模型加载失败不能自动等价于整个 MNN runtime 不健康。**

例如：

```text
坏模型 config
→ MODEL_LOAD_FAILED
→ MNN runtime 本身仍可继续加载另一个模型
```

因此不要简单写：

```text
state == ERROR → /healthz 503
```

建议新增明确健康模型：

```kotlin
data class MnnHealthStatus(
    val available: Boolean,
    val state: MnnEngineState,
    val error: MnnError? = null,
)
```

建议接口：

```kotlin
interface MnnInferenceService {
    fun health(): MnnHealthStatus
    ...
}
```

`available=false` 至少包括：

```text
NATIVE_LIBRARY_UNAVAILABLE
host/container closed
runtime 初始化失败
```

以下错误默认仍可保持 runtime available：

```text
MODEL_NOT_FOUND
MODEL_CONFIG_INVALID
MODEL_LOAD_FAILED
BACKEND_UNSUPPORTED
INVALID_REQUEST
GENERATION_FAILED
CANCELLED
ENGINE_BUSY
```

除非代码能够证明该错误已经把 native runtime 置于不可恢复状态。

---

## 6.3 `MnnEngineHost` 初始化规则

当前 host 不应自己假设：

```kotlin
state = UNLOADED
lastError = null
```

而应从 runtime 初始状态同步一次：

```text
runtime.getStatus()
runtime.getLoadedModel()
runtime.getMetrics()
```

要求：

```text
NativeMnn initializationError
    ↓
MnnEngineHost initial status
    ↓
MnnInferenceService.health()
    ↓
/healthz
```

信息不能在中间丢失。

---

## 6.4 HTTP contract

健康：

```http
GET /healthz
HTTP/1.1 200 OK
```

示例：

```json
{
  "status": "ok",
  "runtime_state": "unloaded",
  "model_loaded": false
}
```

native 不可用：

```http
GET /healthz
HTTP/1.1 503 Service Unavailable
```

示例：

```json
{
  "status": "unavailable",
  "runtime_state": "error",
  "model_loaded": false,
  "error_code": "native_library_unavailable"
}
```

不要返回：

```text
prompt
路径
异常堆栈
设备隐私信息
```

---

## 6.5 主要修改文件

```text
android/app/src/main/java/com/nv/pair/mnn/MnnModels.kt
android/app/src/main/java/com/nv/pair/mnn/MnnEngineHost.kt
android/app/src/main/java/com/nv/pair/mnn/MnnInferenceService.kt
android/app/src/main/java/com/nv/pair/mnn/http/MnnHttpServer.kt
android/app/src/main/java/com/nv/pair/mnn/http/OpenAiResponseWriter.kt
```

测试：

```text
android/app/src/test/java/com/nv/pair/mnn/MnnEngineHostTest.kt
android/app/src/test/java/com/nv/pair/mnn/MnnInferenceServiceTest.kt
android/app/src/test/java/com/nv/pair/mnn/http/MnnHttpServerTest.kt
```

---

## 6.6 必须增加的测试

```text
initialNativeUnavailableIsPreservedByHost
healthReturns503WhenNativeRuntimeUnavailable
healthReturns200WhenRuntimeIsOperationalAndUnloaded
recoverableModelLoadFailureDoesNotPretendNativeLibraryIsMissing
unsupportedOpenClDoesNotMakeCpuRuntimeGloballyUnhealthy
```

---

# 7. Phase B — MNN 失败必须降级，不得拖死 PAIR

对应：

```text
M12.5-02
```

## 7.1 当前问题

当前启动顺序近似：

```text
PairRuntimeService
↓
MnnRuntimeContainer.start()
↓
失败？
↓
整个 runBrokerLoop 失败
↓
PAIR broker 不启动
```

这违反产品架构：

> MNN 是一个 optional engine，不是 PAIR 的单点依赖。

---

## 7.2 正确启动语义

改成：

```text
PairRuntimeService
│
├─ try start MNN container
│    ├─ success → mnnEnabled=true
│    └─ failure → mnnEnabled=false + typed local warning
│
└─ start PAIR broker regardless
```

如果 MNN 不可用：

```text
Phone → PC
```

仍必须可用。

---

## 7.3 不允许的错误修法

禁止：

```text
catch MNN bind error
然后继续告诉 broker mnn 可用
```

否则会出现：

```text
MNN backend 不存在
但 MNN facade 仍被启用
```

甚至端口被其他进程占用时可能误探测。

---

## 7.4 Android 必须显式控制 proxy engine set

`nvpair-ui-broker` 已支持：

```text
--proxy-engines
```

因此 Android `BrokerSession` 应增加明确参数，例如：

```kotlin
class BrokerSession(
    ...
    private val proxyEngines: List<String> = DEFAULT_PROXY_ENGINES,
)
```

Android runtime 正常：

```text
ollama,lmstudio,mnn
```

MNN container 启动失败：

```text
ollama,lmstudio
```

不要启用：

```text
mnn facade
```

这样可以保证：

```text
本地 MNN 不存在
↓
mnn facade 不存在
↓
mn 不会被健康 gate 发布
```

同时保留：

```text
ollama facade
lmstudio facade
```

用于手机路由到 PC。

---

## 7.5 建议新增 Android runtime 状态

不要把 MNN 错误塞进 PAIR 主 runtime 的：

```text
STARTUP_FAILED
```

建议增加独立状态，例如：

```kotlin
data class MnnLocalEngineStatus(
    val available: Boolean,
    val backend: MnnBackend?,
    val error: String?,
)
```

用于 UI 显示：

```text
PAIR: Running
MNN: Unavailable
```

而不是：

```text
PAIR: Startup failed
```

---

## 7.6 主要修改文件

```text
android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt
android/app/src/main/java/com/nv/pair/rpc/BrokerSession.kt
android/app/src/main/java/com/nv/pair/runtime/PairRuntimeController.kt
```

必要时：

```text
android/app/src/main/java/com/nv/pair/data/...
```

---

## 7.7 必须增加的测试

单元测试：

```text
brokerCommandIncludesConfiguredProxyEngines
brokerCommandExcludesMnnWhenLocalMnnDidNotStart
```

instrumentation：

```text
mnnPortConflictDoesNotPreventPairRuntimeFromRunning
mnnUnavailableDoesNotPublishMnnProxyReady
mnnUnavailableStillAllowsDiscoveryAndPcRouting
```

**端口冲突测试不要用一个返回 200 `/healthz` 的伪 MNN 服务。**

测试目的只是证明：

```text
PAIR survives optional engine failure
```

不是模拟合法 MNN。

---

# 8. Phase C — CPU / OpenCL Backend 配置成为单一真相源

对应：

```text
M12.5-03
```

## 8.1 当前问题

当前 generate 类似：

```kotlin
loaded same model ? keep loaded backend : CPU
```

因此：

```text
用户选择 OpenCL
A 模型 OpenCL
↓
切换到 B
↓
B 自动变 CPU
```

这是行为 bug。

---

## 8.2 M12.5 固定策略

只支持两个用户选择：

```text
CPU
OPENCL
```

暂时不要新增：

```text
AUTO backend
```

默认：

```text
CPU
```

因为 CPU 是当前稳定基线。

---

## 8.3 配置 Owner

建议新增：

```text
MnnSettingsRepository
```

或在现有 DataStore repository 中增加明确 MNN backend key。

推荐 key：

```text
mnn_preferred_backend = cpu | opencl
```

持久化层必须只存稳定 wire string：

```text
cpu
opencl
```

不要存 enum ordinal：

```text
0
1
```

避免 enum 顺序变化破坏旧配置。

---

## 8.4 Runtime 内部 BackendSelection

建议：

```kotlin
class MnnBackendSelection(initial: MnnBackend) {
    private val value = AtomicReference(initial)

    fun current(): MnnBackend
    fun update(next: MnnBackend)
}
```

`MnnRuntimeContainer` 持有它。

`LocalMnnInferenceService` 的 generation 必须：

```text
preferredBackend = backendSelection.current()
↓
ensureLoadedLocked(request.modelId, preferredBackend)
↓
generate
```

绝不能再写：

```text
if not loaded -> CPU
```

---

## 8.5 同模型 backend 切换语义

如果：

```text
qwen3-1.7b loaded on CPU
```

用户选择：

```text
OPENCL
```

下一请求必须：

```text
发现 loaded.backend != preferredBackend
↓
unload
↓
load same model on OPENCL
↓
generate
```

不要要求用户手动换模型触发 reload。

---

## 8.6 OpenCL unsupported

显式选择 OpenCL 时：

```text
OpenCL unavailable
```

必须返回 typed error：

```text
BACKEND_UNSUPPORTED
```

HTTP：

```text
422
backend_unsupported
```

禁止：

```text
用户选 OpenCL
↓
失败
↓
偷偷改 CPU
↓
返回 success
```

因为这会让性能测试和用户设置都失去可信度。

CPU 恢复路径必须仍然可用：

```text
OpenCL unsupported
↓
用户选择 CPU
↓
下一请求成功
```

---

## 8.7 `/internal/models/load`

保留：

```json
{
  "model": "...",
  "backend": "cpu|opencl"
}
```

但语义必须与全局 backend selection 一致。

推荐：

```text
/internal/models/load backend
↓
更新 process-local preferred backend
↓
ensureLoaded(model)
```

是否同时持久化 DataStore：

- 来自 Android UI 的 backend 修改：**必须持久化**
- 内部 HTTP control endpoint：可只改当前 runtime，但必须在文档和测试中明确

不要让同一个参数在不同入口产生无法解释的优先级。

---

## 8.8 UI

在 Models 页或 Runtime 设置区域增加：

```text
MNN Compute Engine
○ CPU
○ OpenCL
```

显示 OpenCL unsupported 时：

```text
OpenCL is not available on this device/runtime.
CPU remains available.
```

不要显示：

```text
GPU
```

如果实际 backend 是：

```text
OPENCL
```

代码和日志仍使用：

```text
OpenCL
```

---

## 8.9 必须增加的测试

```text
defaultBackendIsCpu
backendPreferencePersistsAcrossRepositoryRecreation
firstGenerationUsesSelectedOpenCl
modelSwitchKeepsSelectedOpenCl
sameModelReloadsWhenBackendPreferenceChanges
unsupportedOpenClDoesNotSilentlyFallback
cpuWorksAfterOpenClUnsupported
```

---

# 9. Phase D — NativeMnn 资源与并发状态修复

对应：

```text
M12.5-04
M12.5-05
```

## 9.1 `nativeLoadModel` 必须使用 RAII

当前 C++ 路径中，`createLLM()` 成功后，如果：

```text
set_config()
或
load()
```

抛异常，存在 LLM object 无法进入普通 destroy 路径的风险。

禁止继续使用“裸指针 + 多个 return/catch”管理临时 LLM 生命周期。

推荐：

```cpp
using LlmPtr = std::unique_ptr<
    MNN::Transformer::Llm,
    void(*)(MNN::Transformer::Llm*)
>;
```

示意：

```cpp
LlmPtr candidate(
    MNN::Transformer::Llm::createLLM(path),
    MNN::Transformer::Llm::destroy
);

if (!candidate) {
    return kLoadFailed;
}

if (!candidate->set_config(...)) {
    return kLoadFailed;
}

if (!candidate->load()) {
    return kLoadFailed;
}

session->llm = candidate.release();
```

要求：

```text
所有 load 失败路径
↓
candidate destructor
↓
destroy
```

成功时才：

```text
release()
```

---

## 9.2 不要在异常路径破坏旧状态

加载新模型前是否先 destroy 旧模型，当前策略是：

```text
one loaded model only
```

M12.5 不改成双缓冲。

但必须保证 host 状态与 native 状态一致：

```text
unload old
↓
load new
↓
success -> READY(new)
failure -> ERROR + loadedModel=null
```

禁止 Kotlin 仍声称旧模型 READY，而 native 已 destroy。

---

## 9.3 `MnnEngineHost` 状态锁规则

以下字段：

```text
state
loadedModel
activeRequestId
lastError
metrics
```

必须遵循：

> 除明确声明为 Atomic 的 admission gate 外，所有读写都通过 `stateLock`。

禁止：

```kotlin
if (loadedModel != null) { ... }
```

在锁外读取普通 field。

建议新增私有 snapshot helper：

```kotlin
private fun snapshotLocked(): MnnRuntimeStatus
```

减少每个方法自己拼状态。

---

## 9.4 不允许“双层并发控制互相打架”

当前已有：

```text
LocalMnnInferenceService.activeRequestId
MnnEngineHost.generationAdmission
NativeMnn.activeRequestId
C++ Session.activeRequestId
```

M12.5 不要求一次性删到一层，但必须明确职责：

```text
InferenceService
    API-level single-generation admission

EngineHost
    executor serialization + runtime state

NativeMnn
    JNI lifecycle safety

C++ Session
    native request/cancel identity
```

任何一层失败都必须：

```text
finally release admission
```

不能让一次异常永久变成：

```text
engine_busy
```

---

## 9.5 必须增加测试

```text
failedLoadLeavesNoLoadedModel
exceptionDuringLoadLeavesNoLoadedModel
generationFailureReleasesAdmission
cancellationReleasesAdmission
loadAfterFailureStillWorks
repeatedFailedLoadThenSuccessfulLoadWorks
```

真机 acceptance 至少重复：

```text
load fail / load success
或
load / generate / unload
```

多轮，观察 PSS 不出现明显单调失控。

不要用严格“内存必须完全相同”断言，因为 allocator/cache 会产生正常波动。

---

# 10. Phase E — Model Hub Verification 正确性

对应：

```text
M12.5-06
```

## 10.1 安全原则冻结

绝对禁止为了“让 Hugging Face 能安装”而删除：

```text
SHA-256 verification
```

也禁止：

```text
下载后自己算一个 SHA-256
然后拿它和自己比较
```

这不叫验证。

可信验证必须有：

```text
expected SHA-256
来自下载之前已经可信的 metadata
```

---

## 10.2 当前现实

Hugging Face `siblings` 中：

```text
LFS object
```

通常可能提供：

```text
sha256
```

但普通 Git 文件：

```text
config.json
tokenizer 配置
小文件
```

不一定提供 64 hex SHA-256。

40 位 Git object id：

```text
不是 SHA-256
```

不得升级为：

```text
sha256
```

---

## 10.3 ModelDescriptor 增加安装可用性表达

不要让 UI 只知道：

```text
format == MNN
```

就显示可安装。

建议增加领域表达，例如：

```kotlin
enum class ModelInstallability {
    VERIFIED_INSTALLABLE,
    MISSING_VERIFICATION_METADATA,
    UNSUPPORTED_FORMAT,
    INCOMPLETE_ARTIFACT_SET,
}
```

或者实现等价的纯函数：

```kotlin
fun ModelDescriptor.installability(): ModelInstallability
```

要求：

```text
Catalog search result
!=
Verified install candidate
```

---

## 10.4 安装流程

必须保持：

```text
create staging dir
↓
download verified config
↓
parse config
↓
resolve required artifacts
↓
verify each artifact has trusted SHA-256
↓
download each .part
↓
verify hash
↓
MnnModelManager.resolve
↓
atomic publish to final model dir
```

任何失败：

```text
final target 不存在
staging 被清理
```

---

## 10.5 UI 行为

如果：

```text
MNN format
但 config / required artifact 缺 SHA-256
```

卡片应：

```text
可浏览
不可点击 Verified Install
显示：
Verification metadata unavailable
```

不要等下载到一半才用泛化错误：

```text
Install failed
```

如果只有下载 config 后才能知道某个自定义 artifact 缺 hash，则失败信息必须具体：

```text
Missing trusted SHA-256 for <artifact>
```

---

## 10.6 来源错误必须可区分

至少区分：

```text
catalog request failed
file metadata incomplete
download HTTP error
resume range invalid
checksum mismatch
invalid MNN model
target already installed
```

日志不得包含 token。

---

## 10.7 必须增加测试

```text
hfFortyHexGitOidIsNotSha256
hfLfsSha256IsAccepted
missingConfigHashIsNotVerifiedInstallable
missingRequiredArtifactHashFailsBeforePublish
hashMismatchDeletesPartFile
successfulInstallPublishesAtomically
failedInstallLeavesNoFinalModelDirectory
localImportRemainsIndependentOfRemoteChecksumMetadata
```

---

# 11. Phase F — Auto Model 单一权威实现

对应：

```text
M12.5-07
M12.5-08
```

## 11.1 单一 Source of Truth

真正处理：

```text
model=auto
model=auto-fast
model=auto-balanced
model=auto-best
```

的是：

```text
services/shared/modelselection
+
services/nvpair-proxy/gateway.go
```

因此：

> **Go selector 是唯一 authoritative selector。**

Android：

```text
不得重新计算最终模型/engine 选择
```

---

## 11.2 删除 / 降级 Android AutoModelSelector

当前：

```text
android/.../models/AutoModelSelector.kt
```

不应继续作为一套独立决策器存在。

处理方式：

```text
先用 rg 确认引用
↓
如果只有 UI count / tests 在用
↓
删除 selector
↓
删除 selector-only RuntimeModel / ModelRequirements / ModelSelection 类型
```

如果 Android UI 未来需要显示：

```text
auto-fast
auto-balanced
auto-best
```

只把它们作为：

```text
gateway-exposed aliases
```

不要在 Kotlin 中复制评分权重。

最优方式：

```text
从 GET :14326/v1/models
获取 alias
```

而不是 Android 再维护一份四个 alias 的业务逻辑。

---

## 11.3 M12.5 不做 distributed metadata 大改造

当前 Go gateway 对很多 metadata 只能从 model ID 推断。

M12.5 暂时不新增：

```text
catalog metadata 跨进程协议
catalog metadata 跨节点协议
```

但必须遵守：

```text
Unknown != False
Unknown != fabricated high confidence
```

评分中未知性能信号可继续使用集中定义的 neutral score。

禁止散落新的：

```text
0.5
20
500
```

magic numbers。

---

## 11.4 Capability 必须做“模型能力 ∩ engine 协议能力”

当前仅按名字猜：

```text
tool
vision
embed
```

不够。

至少实现：

```text
effectiveCapabilities
=
modelHeuristicCapabilities
∩
engineProtocolCapabilities
```

其中 MNN 在 M12.5 明确：

```text
chat       = true
tools      = false
vision     = false
embeddings = false
```

因为 Android MNN OpenAI parser 当前明确拒绝这些能力。

所以即使模型名叫：

```text
qwen-tool-xxx
```

也不能让：

```text
tools request
↓
auto
↓
mnn
```

---

## 11.5 对 Ollama / LM Studio 保守处理

不要在 M12.5 声称所有版本、所有模型都支持：

```text
tools
vision
```

可继续使用现有模型 heuristic 作为候选信息，但 engine 层明确“不支持”的能力必须具有否决权。

未来如果要做精确 capability discovery：

```text
单独 milestone
```

不要塞进本次修 bug。

---

## 11.6 Deterministic tie break

Go selector 必须保持 deterministic。

相同 score 至少按：

```text
model id
engine rank
node id
```

稳定排序。

不要使用：

```text
map iteration order
```

作为最终结果。

---

## 11.7 必须增加 Go 测试

```text
autoMapsToBalanced
catalogOnlyModelIsNeverSelected
mnnIsExcludedForToolsRequest
mnnIsExcludedForVisionRequest
mnnIsExcludedForEmbeddingsRequest
mnnCanBeSelectedForChat
sameInputProducesSameSelectionRepeatedly
loadedCandidatePreferenceRemainsDeterministic
unknownSignalsUseNeutralScore
```

gateway：

```text
autoToolsRequestDoesNotRouteToMnn
autoChatRequestCanRouteToMnn
autoAliasRewritesOnlyModelField
explicitModelRoutingIsUnchanged
```

---

# 12. Phase G — Android Router UI 补全 MNN 状态

对应：

```text
M12.5-09
```

## 12.1 当前问题

`RouterRepository` 默认只有：

```text
ollama
lmstudio
```

`monitorProxyStatus()` 也只 poll：

```text
ollama
lmstudio
```

这和 M9 “MNN is a first-class engine” 不一致。

---

## 12.2 修复

唯一列表：

```text
ollama
lmstudio
mnn
```

不要在两个文件各写一份不同顺序。

建议集中：

```kotlin
private val ROUTER_ENGINES = listOf("ollama", "lmstudio", "mnn")
```

或等价稳定来源。

---

## 12.3 Reset

broker crash / session close 后必须：

```text
ollama ready=false
lmstudio ready=false
mnn ready=false
```

禁止 MNN 卡片残留旧：

```text
ready=true
```

---

## 12.4 UI 区分

如果 Phase B 引入：

```text
local MNN backend unavailable
```

可以显示：

```text
MNN facade: unavailable
Local MNN engine: unavailable
PAIR runtime: running
```

不要把三种状态压成一个 boolean。

---

## 12.5 测试

```text
routerRepositoryContainsAllThreeEngines
mnnProxyStatusIsUpdated
allProxyStatusesResetOnBrokerExit
mnnUnavailableDoesNotAffectOtherProxyCards
```

---

# 13. Phase H — Shutdown 必须有界

对应：

```text
M12.5-10
```

## 13.1 当前风险

链路：

```text
PairRuntimeService.stop
↓
MnnRuntimeContainer.close
↓
MnnInferenceService.close
↓
MnnEngineHost.close
↓
executor.submit(runtime.close()).get()
↓
NativeMnn.close
↓
wait activeRequestId == null
```

如果 native：

```cpp
llm->generate(1)
```

永久卡死，Stop 可能永久卡住。

---

## 13.2 M12.5 目标

不是解决：

```text
native driver 永不返回
```

这种进程内不可强杀问题。

M12.5 目标是：

> **PAIR 主 runtime 的 shutdown 不得无限等待 optional MNN engine。**

---

## 13.3 建议策略

`MnnEngineHost.close()`：

```text
1. request cancel active generation
2. submit runtime.close
3. bounded Future.get(timeout)
4. timeout -> log operational error
5. executor.shutdownNow()
6. return control to PairRuntimeService
```

建议 timeout：

```text
5～10 seconds
```

使用 named constant。

不要：

```text
future.get()
```

无 timeout。

---

## 13.4 重要限制

如果 native call 本身不响应取消：

```text
Java/Kotlin 不能安全 delete 正在 native 使用的 LLM
```

因此 timeout 后可能保留 native resource 到进程退出。

这比：

```text
整个 PAIR ForegroundService 永远无法 Stop
```

更可接受。

此限制必须：

```text
日志明确
测试明确
文档明确
```

真正的 hard-kill isolation 属于未来：

```text
M14 / separate MNN process
```

不是本次偷偷改架构。

---

## 13.5 测试

使用 fake runtime：

```text
closeCancelsActiveGeneration
closeReturnsWithinBoundWhenRuntimeIgnoresCancel
pairStopContinuesWhenMnnCloseTimesOut
normalCloseStillUnloadsAndDestroysRuntime
```

测试 timeout 应注入较短 test value，禁止让单测真的等 10 秒。

---

# 14. Phase I — OpenAI Compatibility 修正

对应：

```text
M12.5-11
```

## 14.1 Error type

当前不能把所有错误都输出：

```json
"type": "invalid_request_error"
```

建议映射：

```text
invalid request / unsupported parameter
→ invalid_request_error

model_not_found
→ invalid_request_error 或明确 model error（保持全项目一致）

engine_busy
→ server_error / service_unavailable 类语义

backend_unsupported
→ invalid_request_error

native unavailable
→ server_error

generation internal failure
→ server_error
```

关键要求：

```text
HTTP status
error.type
error.code
```

三者不能互相矛盾。

---

## 14.2 finish_reason

如果能够可靠从 native final context 得到：

```text
EOS / stop
MAX_TOKENS
```

则把结果模型增加：

```kotlin
enum class MnnFinishReason {
    STOP,
    LENGTH
}
```

并传到：

```text
non-stream completion
stream final chunk
```

如果本 Phase 不增加 native finish reason wire，则至少写测试并记录当前 inference 的限制，不要用不可靠 heuristic 假装完全正确。

优先：

```text
正确 > 表面兼容
```

---

## 14.3 SSE 保持

必须继续：

```text
第一 token 在完整请求结束前到达
[DONE] exactly once
client disconnect -> cancel
```

---

# 15. Phase J — Acceptance Gate：把“代码存在”变成“链路已证明”

对应：

```text
M12.5-12
```

这一阶段原则上不做架构开发。

发现问题时：

```text
回到对应 Phase 修根因
```

不要在 acceptance script 里加 hack。

---

# 16. 本地静态 / 单元测试矩阵

## 16.1 Android JVM

Windows：

```powershell
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

任何失败：

```text
M12.5 不得继续标 DONE
```

---

## 16.2 Go

至少运行：

```bash
cd services/nvpair-proxy
go test ./...

cd ../nvpair-ui-broker
go test ./...

cd ../nvpair-engine-manager
go test ./...

cd ../shared
go test ./...

cd ../tests
go test ./...
```

如果修改了其他 module：

```text
对应 module go test ./...
```

也必须执行。

---

## 16.3 Repository checks

仓库根：

```bash
node scripts/spdx-headers.mjs
git diff --check
```

如果改了 JSON-RPC contract：

```bash
cd desktop
npm run service-contracts:check
```

不要手改：

```text
desktop/docs/services-api.md
services/versions.json
CHANGELOG.md
```

---

# 17. Android 真机 MNN Gate

使用已有：

```powershell
cd android

.\scripts\run-mnn-acceptance.ps1 `
  -ModelDir D:\models\qwen3-1.7b-mnn `
  -ModelSwitchDir D:\models\qwen3-0.6b-mnn
```

至少确认：

```text
CPU load
CPU generate
stream
cancel
next request
unload/reload
model switch
OpenCL pass 或 typed BACKEND_UNSUPPORTED
```

如果当前设备 OpenCL unsupported：

```text
这不是失败
```

前提是：

```text
typed BACKEND_UNSUPPORTED
CPU 后续仍成功
```

---

# 18. E2E Scenario A — Phone → PC

PC：

```text
PAIR running
Ollama 或 LM Studio running
PC-only model installed
```

Android：

```text
PAIR running
与 PC 完成 cluster pairing
```

手机本地：

```text
该 model 不存在
```

请求：

```text
Android PAIR facade / gateway
model = PC-only model
stream = true
```

必须：

```text
[ ] PC 收到请求
[ ] Android 不直连 PC backend stock port
[ ] workload engine 正确
[ ] scheduledOn = PC node
[ ] stream 返回手机
```

---

# 19. E2E Scenario B — PC → Phone MNN

Android：

```text
MNN model installed
```

PC：

```text
没有同名 model
```

PC PAIR 请求：

```text
model = Android-only model
stream = true
```

必须：

```text
[ ] PC discovery 看见 modelsByEngine.mnn
[ ] request 路由到 Android
[ ] Android MNN 执行
[ ] engine=mnn
[ ] scheduledOn=Android
[ ] SSE 在 completion 完成前开始返回
```

---

# 20. E2E Scenario C — Phone Chat App → PC

第三方 OpenAI-compatible App 只配置：

```text
Base URL = http://127.0.0.1:14326/v1
Key      = pair-local
Model    = PC-only model
```

必须：

```text
[ ] Chat App 不配置 PC IP
[ ] Chat App 不配置 11434
[ ] Chat App 不配置 1234
[ ] Chat App 不知道 engine 名
[ ] 请求自动到 PC
[ ] 流式响应成功
```

这是当前产品 MVP 最关键场景。

---

# 21. E2E Scenario D — Phone Chat App → Phone MNN

同一个：

```text
http://127.0.0.1:14326/v1
```

只改变：

```text
Model = Android MNN model
```

必须：

```text
[ ] 请求本机 MNN
[ ] 不经过外部 PC backend
[ ] workload 只记录一条逻辑任务
[ ] engine=mnn
[ ] stream 成功
```

---

# 22. E2E Scenario E — MNN 故障降级

人为制造：

```text
MNN backend 无法启动
```

例如安全地占用测试端口/使用测试注入，不修改系统库。

必须：

```text
[ ] PAIR runtime 仍进入 RUNNING
[ ] mnn facade 不 ready
[ ] mn 不 advertise
[ ] ollama/lmstudio routing 仍可用
[ ] Phone → PC 请求成功
[ ] UI 显示 MNN unavailable，而不是 PAIR startup failed
```

---

# 23. E2E Scenario F — Backend 切换

Android 装一个可运行 MNN model。

步骤：

```text
1. 选择 CPU
2. 请求 model
3. 确认 loaded backend=cpu

4. 选择 OpenCL
5. 请求同一个 model
```

如果 OpenCL 支持：

```text
[ ] model reload 到 opencl
[ ] 请求成功
```

如果不支持：

```text
[ ] 返回 BACKEND_UNSUPPORTED
[ ] 没有 silent CPU fallback
[ ] 再切 CPU
[ ] 下一请求成功
```

再切另一个 model：

```text
[ ] 仍遵守当前 preferred backend
```

---

# 24. E2E Scenario G — Auto capability safety

发送普通 chat：

```text
model=auto-balanced
```

允许选择 MNN。

发送 tools request：

```json
{
  "model": "auto-balanced",
  "messages": [...],
  "tools": [...]
}
```

在当前 MNN capability contract 下：

```text
[ ] MNN 不得成为候选
```

如果网络里没有支持 tools 的 engine：

```text
返回 no available model
```

不要：

```text
选 MNN
↓
再由 MNN parser 400
```

selector 应在路由前完成 capability filter。

---

# 25. 日志与隐私要求

绝对禁止记录：

```text
prompt
messages.content
assistant output
token chunk
pairing PIN
private key
future real API key
HF / ModelScope access token
```

允许：

```text
request id
model id
engine
node id
backend CPU/OpenCL
health state
error code
TTFB
duration
token counts
```

错误日志优先：

```text
typed code + operational metadata
```

不要：

```text
println full exception body including request content
```

---

# 26. 错误处理统一原则

禁止：

```text
catch Throwable
return success
```

每个失败必须归入：

```text
用户输入错误
能力不支持
资源忙
模型错误
runtime unavailable
内部错误
```

并保持：

```text
Kotlin error code
HTTP status
OpenAI error.code
Go routing error
UI message
```

方向一致。

---

# 27. 推荐 Commit 顺序

每个 commit 必须独立编译/测试。

```text
01 test(mnn): cover runtime health and native unavailable state
02 fix(mnn): make health reflect runtime availability

03 test(android): cover PAIR startup without local MNN
04 fix(android): degrade when optional MNN engine is unavailable

05 test(mnn): cover persisted backend selection and model switching
06 fix(mnn): make CPU/OpenCL selection authoritative

07 fix(mnn): make native model loading RAII-safe
08 refactor(mnn): enforce locked host state access

09 test(models): cover verification metadata and unsafe HF oid cases
10 fix(models): gate installs on trusted artifact verification

11 test(router): cover auto capability filtering and deterministic selection
12 refactor(android): remove duplicate Android auto selector
13 fix(router): intersect model and engine protocol capabilities

14 fix(android): expose MNN facade/runtime status in UI

15 test(mnn): cover bounded shutdown
16 fix(mnn): bound optional runtime shutdown

17 fix(openai): align error and finish semantics

18 test(pair): close M12.5 Android/PC E2E acceptance
```

不要把所有修复压成：

```text
one giant M12.5 commit
```

---

# 28. 每个 Commit 的最低检查

至少：

```text
git diff --check
focused tests
```

涉及 Kotlin：

```text
testDebugUnitTest
```

涉及 Go：

```text
对应 module go test ./...
```

涉及 native：

```text
assembleDebug
相关 instrumentation / acceptance
```

---

# 29. AI Agent 禁止“顺手优化”列表

在 M12.5 中发现以下诱惑时必须停下，不要擅自实施：

```text
“既然要改 health，我顺便重写 HTTP server”
“既然要改 backend，我顺便做 AUTO GPU”
“既然 HF hash 不全，我把 verification 去掉”
“Android selector 和 Go 不一致，我统一改成 Kotlin”
“shutdown 难处理，我把 MNN 拆成独立进程”
“14326 已经有了，我顺便 bind 0.0.0.0”
“pair-local 不安全，我顺便上 Keystore auth”
```

这些都属于 scope expansion。

---

# 30. 代码质量不变量

M12.5 完成后必须能写出并证明以下不变量。

## Runtime

```text
PAIR can run without MNN.
MNN cannot run without Android ownership.
```

## Health

```text
Advertised MNN implies:
backend healthy
AND
facade ready.
```

## Backend

```text
Every implicit MNN model load uses the selected backend.
```

## Routing

```text
Gateway chooses model/engine.
Existing scheduler chooses node.
```

## Auto

```text
One authoritative selector exists.
```

## Model Hub

```text
Verified Install means every downloaded artifact was checked
against a pre-existing trusted SHA-256.
```

## Shutdown

```text
Optional MNN failure cannot block PAIR shutdown forever.
```

## Security

```text
No prompt / response / secret is logged.
```

---

# 31. 不建议在 M12.5 解决的残余问题

以下可以记录，但不要阻塞本 milestone：

```text
更精确的 distributed model metadata
真实 benchmark telemetry 进入 auto score
长期 tokens/s / TTFT profile
Android thermal / battery aware routing
MNN 独立进程硬隔离
LAN Gateway
real API key
rate limit
key rotation
multi-model residency
multi-request batching
```

这些分别更适合：

```text
M13 / M14 / later routing milestone
```

---

# 32. M12.5 最终验收报告模板

AI Agent 最终不能只说：

```text
Done
```

必须输出：

```text
Baseline commit:
Final commit:

Changed files:

P0 fixes:
- ...

P1 fixes:
- ...

P2 fixes:
- ...

Tests executed:
- command
  result

Android device:
- device / Android version
- CPU result
- OpenCL result

E2E:
- Phone -> PC: PASS/FAIL
- PC -> Phone: PASS/FAIL
- Phone -> Phone: PASS/FAIL
- Chat App -> 14326 -> PC: PASS/FAIL
- Chat App -> 14326 -> MNN: PASS/FAIL
- Auto capability safety: PASS/FAIL
- Cancellation recovery: PASS/FAIL

Known remaining limitations:
- ...
```

任何没有实际执行的项写：

```text
NOT RUN
```

绝不能写：

```text
PASS
```

---

# 33. 给 AI Agent 的最终执行 Prompt

```text
Read AGENTS.md and
docs/PAIR_ANDROID_M8_M12_IMPLEMENTATION_PLAN.md completely.

Then read this M12.5 document completely.

Baseline:
feature/android-build
10a7c013dfbcec2f7dfbd1c02c08b37a754a00e1

Current milestone:
M12.5 bugfix / hardening only.

Do not implement M13.
Do not change the architecture boundaries.

Required invariants:

1. Android owns MNN lifecycle.
2. HTTP carries inference data.
3. PAIR proxy/scheduler remain the only routing core.
4. MNN is optional; PAIR must still route to PC if local MNN fails.
5. A healthy/advertised MNN must correspond to a truly available backend.
6. CPU/OpenCL selection is persistent and authoritative for every implicit model load.
7. Go modelselection is the only authoritative auto selector.
8. Engine capability constraints override model-name heuristics.
9. Verified model install requires trusted pre-download SHA-256 metadata.
10. Optional MNN shutdown must be bounded.

Work one Phase at a time.

For every Phase:
- inspect current implementation and existing tests;
- add a regression test that fails before the fix;
- implement the smallest correct fix;
- run focused tests;
- run the affected module test suite;
- inspect the diff before continuing.

Do not disable, skip, delete, or weaken tests to make the suite pass.
Do not silently fall back from OpenCL to CPU.
Do not remove checksum verification.
Do not create a second router or scheduler.
Do not expose :14326 on LAN.
Do not add real authentication yet.

At the end, run all static/unit checks and the real-device / PC E2E matrix.
Report NOT RUN for anything that was not actually executed.
Only mark M12.5 complete when every required Definition of Done gate is green.
```

---

# 34. 最终原则

M12.5 结束时，这个版本应满足：

> **本地 MNN 好用时，PAIR 可以把它当成正常算力节点；本地 MNN 坏掉时，PAIR 仍然是一个正常的跨设备路由器；任何被宣称为健康、自动选择或已验证安装的状态，都必须有代码和测试可以证明。**
