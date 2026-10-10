# PAIR Android OpenCL 能力探测与默认后端选择 — AI Coding 施工文档（v2）

> 目标分支：`feature/cloud-provider`，仓库 `afantastic1/Personal-AI-Router`  
> 审查时间：2026-10-09  
> 范围：Android App 的 MNN CPU/OpenCL 运行时能力探测、**基于探测的首次默认选择**、手动选择优先级、Kotlin/JNI、后台 Service 生命周期、Compose UI、构建与验收。
> 本版决策：**OpenCL 探测成功则默认 OpenCL，否则默认 CPU；手动选择优先。绝不做每次请求的性能推断或自动优化切换。**  
> 本文是开发计划，不代表功能已经实现或设备实际探测通过。

## 0. 目标与不可变约束

- **目标**：不下载/加载模型即可知道 *当前 APK 的 MNN OpenCL Runtime 是否能够初始化*；UI 可解释结果并重测。
- **不等价于**：GPU 硬件支持 OpenCL、驱动绝对兼容、任意 MNN 模型都可在 GPU 上完整生成。模型加载和实际推理是后续两级验证。
- 不修改 PC 端、跨设备 discovery、云供应商路由，不引入原生驱动副本，不修改系统 `/vendor` 或 linker 命名空间。
- **自动默认决策仅在尚未手动选择时生效**：OpenCL runtime probe 为 AVAILABLE，默认 OpenCL；否则（NOT_COMPILED、UNAVAILABLE、ERROR）默认 CPU。
- **用户选择优先**：手动选择 CPU 时即使 OpenCL 可用也保持 CPU；手动选择 OpenCL 时若后续探测不可用，实际执行安全回退 CPU，显示“偏好 OpenCL，当前暂不可用”，**不静默改写其持久化选择**。
- **不做自动性能推断**：不按模型大小、温度、显存、吞吐或请求特征切换后端；探测并不是一次模型 benchmark。
- 不因为 GPU 探测失败而停止 broker、云路由或局域网互联。
- 不在主线程做 native probe，不将 UI `Composable` 直接连到 JNI；探测完成前只能显示 Unknown/Checking，不能乐观地显示 Available。
- 不为探测加载大模型、不在每个 token / 每次 HTTP 请求时重复探测、不以 `dlopen("libOpenCL.so")` 单独作为最终判据（MNN 可能使用不同厂商的库）。
- 首次探测期间后端决策状态是 `RESOLVING`；本地生成请求如先到达，可**临时使用 CPU**（明确标记为“探测中临时 CPU”），不能将临时 CPU 持久化为用户选择或已决默认值；探测完成仅影响**后续请求**。不卸载正在生成的模型。
- 现有 MNN 原生库构建有 `cpu` 和 `opencl` 两种变体，**CPU 包返回 NOT_COMPILED 是正常且必须覆盖的情况**。

## 1. 已核实源码现状

| 路径 | 目前职责 / 需要注意的内容 |
|---|---|
| `android/app/src/main/cpp/NativeMnn.cpp` | 现有 `CanInitializeOpenClRuntime()` 在 223–237 行；`nativeLoadModel` 257 行之后先根据 `PAIR_MNN_HAS_OPENCL` 检查，再 `createRuntime()`；OpenCL 探测目前只在 *请求加载 OpenCL 模型* 时触发。 |
| `android/app/src/main/java/com/nv/pair/mnn/NativeMnn.kt` | 338–355 行有 JNI 声明；373 行 `System.loadLibrary("pair_mnn")`。无独立 probe 接口。 |
| `android/app/src/main/java/com/nv/pair/mnn/MnnRuntime.kt` | `MnnRuntime` 抽象供 Host 和测试替身使用。 |
| `android/app/src/main/java/com/nv/pair/mnn/MnnSettingsRepository.kt` | 当前只有 `preferredBackend` 和 `mnn_preferred_backend` DataStore 键；**必须区分“没有选择（AUTO）”和“用户明确选了 CPU”**。 |
| `android/app/src/main/java/com/nv/pair/mnn/MnnBackendSelection.kt` | 当前仅以 `AtomicReference` 保存一个 backend；需要与明确的默认决策 owner 协调，不能独立推断 GPU 能力。 |
| `android/app/src/main/java/com/nv/pair/mnn/MnnEngineHost.kt` | 有单线程 executor + `dispatch()`；推理、加载被串行化；探测建议加入同一调度序列，而不是直接从 UI 线程越过 Host 调用 Native。 |
| `android/app/src/main/java/com/nv/pair/mnn/MnnRuntimeContainer.kt` | 持有 runtime、Host、LocalMnnInferenceService、HTTP Server，是向 Service 暴露能力的合适门面。 |
| `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt` | `startOptionalMnnRuntime()` 创建并启动 Container，`_mnnLocalEngine` 表示整体本地服务状况，不表示 OpenCL 已检测通过；Service 是新能力状态及**有效后端决策**的 owner。目前 `onCreate` 还直接 collect `preferredBackend` 后覆盖 selection，须改造。 |
| `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeController.kt` | 暴露 StateFlow 给 UI、接收 UI 操作。 |
| `android/app/src/main/java/com/nv/pair/models/ModelHubScreen.kt` | 第 84–99 行已有 CPU/OpenCL 选择；尚未显示独立能力探测状态。 |
| `android/app/src/main/AndroidManifest.xml` | 未声明可选 vendor OpenCL native library。 |
| `android/app/build.gradle.kts` + `android/app/src/main/cpp/CMakeLists.txt` | `-PpairMnnOpenCL=true` 选择包含 OpenCL 运行时的构建，宏 `PAIR_MNN_HAS_OPENCL` 为 1；默认为 0。 |
| `android/scripts/verify-mnn-runtime-variant.ps1` | 已校验 OpenCL 变体 `libllm.so` 的 `DT_NEEDED` 包含 `libMNN_CL.so`；不要重复发明打包链路。 |

**已有调用链：** `MainActivity -> PairRuntimeController -> PairRuntimeService -> MnnRuntimeContainer -> LocalMnnInferenceService -> MnnEngineHost -> NativeMnn.kt -> NativeMnn.cpp -> MNN`。

**跨层设计原则：** 新的能力探测可以作为 `MnnRuntime.probeOpenCl()` 的独立操作，经 Host 同一串行队列执行。独立 Kotlin 的 `MnnBackendCapability` 类型记录能力；**后端选择由单一、纯函数式策略协调器决策**。不用新建第二套全局 JNI Loader、也不要让探测直接改变正在运行的模型。

## 2. 定义清楚“可用”含义

拆分三个概念，不能复用同一个 `available`：

1. `engineHealth`：本地 `pair_mnn` + HTTP runtime 是否正常（现有 `MnnLocalEngineStatus.available`）。
2. `backendCapability`：当前 APK 内 MNN 指定后端能否创建 runtime（本次新功能）。
3. `modelReadiness`：指定模型是否能在该后端加载并完整推理（已有 `ensureLoaded`/`generate` 负责，禁止本次假设已通过）。

新增文件 `android/app/src/main/java/com/nv/pair/mnn/MnnBackendCapability.kt`：

```kotlin
package com.nv.pair.mnn

enum class ProbeState { UNKNOWN, CHECKING, AVAILABLE, UNAVAILABLE, ERROR }
enum class ProbeReason {
    NOT_COMPILED,
    NATIVE_LIBRARY_UNAVAILABLE,
    RUNTIME_INIT_FAILED, // 包含驱动不可访问、无可用 OpenCL Runtime 等：无法单凭 MNN 精确细分
    PROBE_FAILED,
    RUNTIME_STOPPED,
}

data class BackendCapability(
    val backend: MnnBackend,
    val state: ProbeState,
    val reason: ProbeReason? = null,
    val checkedAtEpochMillis: Long? = null,
)

data class MnnCapabilitySnapshot(
    val cpu: BackendCapability,
    val openCl: BackendCapability,
)
```

约束：
- `AVAILABLE` 对 OpenCL 仅表示 runtime 初始化成功，UI 明确标注“尚未验证具体模型”。
- `UNAVAILABLE` 用于确切不可用；`ERROR` 用于探测异常、无法判定；`UNKNOWN` 不是不可用。
- `reason` 应为稳定类型，切勿让 UI 依赖 C++ `dlerror` 原始文本。
- 默认选择 CPU 只是选择策略，并不意味着 native library 一定可用；若主 JNI 库不存在，显示 Engine 不可用且禁止推理，不得把 CPU 标为实际可用。
- `checkedAtEpochMillis` 仅成功完成探测时更新；离线缓存的结果不能跨不同构建变体当成实时可用。

建议将 native 返回代码从业务模型单独定义，C++ 常量和 Kotlin 显式映射：

| native code | 语义 | Kotlin |
|---|---|---|
| `0` | OpenCL Runtime 初始化成功 | AVAILABLE |
| `1` | 构建不包含 OpenCL | UNAVAILABLE / NOT_COMPILED |
| `2` | MNN 没有生成有效 OpenCL Runtime | UNAVAILABLE / RUNTIME_INIT_FAILED |
| `3` | 发生未预期的 C++ probe 异常 | ERROR / PROBE_FAILED |

`System.loadLibrary()` 失败或 native 方法 `UnsatisfiedLinkError` 在 Kotlin 单独映射为 `ERROR / NATIVE_LIBRARY_UNAVAILABLE`，不依赖 C++ 返回码。Native 状态码数字不要与 `MnnErrorCode` 或 `MnnBackend.ordinal` 混用。

## 2.5 默认后端选择策略（本版关键变更，P0）

**需要同时保存 `选择意图（Choice）`、`探测结果（Capability）`、`当前实际后端（Effective）` 三件事。** 不能继续将 `preferredBackend = CPU` 当成“用户主动选 CPU”，因为当前代码的默认 CPU 值会掩盖全新安装时应自动默认 OpenCL 的要求。

### 2.5.1 唯一决策真相与持久化

新增纯 Kotlin `MnnBackendPolicy.kt`（或等价单一文件）：

```kotlin
sealed interface BackendChoice {
    data object Auto : BackendChoice
    data class Manual(val backend: MnnBackend) : BackendChoice
}

data class EffectiveBackendSelection(
    val choice: BackendChoice,
    val effectiveBackend: MnnBackend,
    val resolving: Boolean = false,
    val unavailableManualChoice: Boolean = false,
)
```

`MnnSettingsRepository` 改为读写 `BackendChoice`，**不能只读一个 CPU 默认枚举**：

- 新 DataStore 键 `mnn_backend_choice_mode`：`auto` / `manual`，缺失表示 `Auto`（适用于新安装）。
- 新键 `mnn_manual_backend`：只有手动选择时写入 `cpu` 或 `opencl`。
- **旧版迁移**：如果新 mode 键缺失、但旧 `mnn_preferred_backend` 已存在，按 `Manual(旧值)` 处理，避免升级后覆盖历史手动选择；如果旧键也不存在，按 `Auto`。迁移应是幂等的，禁止每次启动重写或读取后产生相互矛盾的数据。
- 手动点击 CPU / OpenCL 时写 `Manual(...)`；UI 提供“恢复自动默认”时写 `Auto` 并立即按最近一次有效探测结果重新求值。
- **不要持久化 probe AVAILABLE / ERROR，也不要将自动算出的 effectiveBackend 写成 manual preference**。

纯函数 `resolveBackendChoice(choice, openClCapability, engineHealth)` 用如下矩阵决定结果：

| 用户选择 | OpenCL 探测 | 当前有效后端 | UI 说明 |
|---|---|---|---|
| `Auto` | `AVAILABLE` | **OpenCL** | 已自动默认 OpenCL；仅代表运行时可初始化 |
| `Auto` | `UNAVAILABLE`（含 NOT_COMPILED） | **CPU** | 已自动默认 CPU |
| `Auto` | `ERROR` | **CPU** | 探测失败，保守默认 CPU，允许重测 |
| `Auto` | `UNKNOWN/CHECKING` | **临时 CPU** | 正在探测，默认决策尚未完成 |
| `Manual(CPU)` | 任意探测结果 | **CPU** | 用户指定，探测不可覆盖 |
| `Manual(OPENCL)` | `AVAILABLE` | **OpenCL** | 用户指定 |
| `Manual(OPENCL)` | 其他状态 | **CPU（安全回退）** | 明确提示原偏好未变、当前 OpenCL 不可用；不自动加载 OpenCL |

- 如果主 MNN 运行时不可用，`effectiveBackend=CPU` 只表示回退意图，不代表能运行；外层 Engine Health 必须阻止本地请求，并告知不可用。
- 状态迁移 **只由用户操作或一次明确的 probe 结果触发**，绝对不依据当前请求负载、模型、GPU 性能动态选择；重测可以刷新 `Auto` 的有效默认值，但**不得**覆盖 `Manual`。
- 探测前的“临时 CPU”只是有限窗口的安全策略，不是最终自动决策。它只用于探测尚未完成就到达的请求；不阻塞 PAIR broker/云端路由。正在进行的推理保持原后端，下一次请求才读取新的有效后端。
- UI 应分别显示“选择模式：自动/手动”、“有效后端：CPU/OpenCL”、“OpenCL 探测结果”；不要以 `preferredBackend == OPENCL` 就暗示 GPU 真实可用。

### 2.5.2 单一协调器的调用顺序

```text
启动 PairRuntimeService
  -> 创建 MnnRuntimeContainer，保证 CPU 临时后端可用
  -> 将 OpenCL Capability 置 CHECKING，后台开始探测
  -> 探测完成后产生 AVAILABLE / UNAVAILABLE / ERROR
  -> 从 DataStore 读取 BackendChoice（新安装为 Auto）
  -> resolveBackendChoice(...) 生成 EffectiveBackendSelection
  -> 仅在当前 Service/Container generation 有效时，更新选用后端与 UI StateFlow
  -> 后续 MNN 模型请求使用该有效后端，不在 token/请求过程中再次探测
用户主动操作：
  -> 持久化 Manual(CPU/OpenCL)；立即重新求值
  -> 手动选择在探测完成前也不得被晚到的结果覆盖
用户点“恢复自动默认”：
  -> 清除手动覆盖、保存 Auto；按最近能力快照求值（若未知则临时 CPU）
```

**实现注意：** `PairRuntimeService.onCreate()` 当前监听旧 `preferredBackend`，并在 `applyPreferredMnnBackend()` 中直接将值推给 `MnnRuntimeContainer`。施工时改成收集 `BackendChoice` 并调用同一个 `reconcileBackendSelection()`；探测完成、用户选择、重测完成也调用该协调方法。**不能留下独立的旧 Flow collector 与新 probe 回调争抢 `MnnBackendSelection`。**

- `reconcileBackendSelection()` 需要用 `Mutex`/Service 单线程事件串行化“读取 choice → 结合 capability → 更新 effective → 通知 Container”，防止晚完成的 probe 将手动选择覆盖；对旧 Container 做 generation/identity 校验。
- 绝不在 `MnnRuntime.generate()`、`nativeGenerateChat()` 或 HTTP request parser 内做后端探测或自动判断。
- `MnnBackendSelection.update()` 只接收已决有效后端；不要另写一个会推断 GPU 的选择器。
- 选择改变不强行打断当前模型生成；下次 `ensureLoaded()` 按 effective backend 如有必要重新加载。显示“偏好已更新，将对下一次推理请求生效”。

## 3. 第一阶段：Native/JNI 提取独立探测（P0）

修改 `android/app/src/main/cpp/NativeMnn.cpp`：

- 保留 `CanInitializeOpenClRuntime()` 的 MNN 正确性检查：必须查找 `runtime.first[MNN_FORWARD_OPENCL]` 是否存在且指针非空，防止 MNN 回退到 CPU 造成误报。
- 新增 `extern "C" JNIEXPORT jint JNICALL Java_com_nv_pair_mnn_NativeMnn_nativeProbeOpenCl(JNIEnv*, jobject)`；在 `#if !PAIR_MNN_HAS_OPENCL` 分支 **直接返回 NOT_COMPILED**；否则调用原有探测函数，将结果映射到稳定 probe code。
- 对异常做边界保护，区分“正常的运行时不可用”和“未预期异常”；避免 JNI 异常越过 ABI。
- 复用现有 `MNN::Interpreter::createRuntime`，不要直接访问厂商私有 `.so` 的绝对路径。
- `RuntimeInfo` 是具有自动生命周期的局部对象；不要存入长生命周期 Session，避免探测造成 GPU 资源持续占用。
- 原有 `nativeLoadModel()` 必须保留**实时运行时验证**；不要仅因曾经 probe AVAILABLE 就跳过加载时检查。
- 原生 probe 过程不应更改 `Session*` 的 `llm`、`backend`、`activeRequestId` 和取消标志。
- 注意 MNN/驱动 API 有潜在进程级崩溃/挂死可能；在 App 主进程运行的 P0 探测仅为 best effort，不能声称可以强制中断任意卡死的 native 操作。

修改 `NativeMnn.kt`：

```kotlin
private external fun nativeProbeOpenCl(): Int

// 公开给 MnnRuntime 的类型安全入口（伪签名）
override fun probeOpenCl(): BackendCapabilityResult
```

其中 `BackendCapabilityResult` 设计成 `sealed interface`（Available / Unavailable(reason) / Error(reason)），对 0/1/2/3 做 **穷尽映射**；捕获 `UnsatisfiedLinkError`；不可将任意非 0 都处理为 `NOT_COMPILED`。

修改 `MnnRuntime.kt` 添加 `fun probeOpenCl(): BackendCapabilityResult`，同时更新所有测试替身。可选：使 CPU 探测直接由 `NativeMnn` 初始化结果和 Session 健康度计算，无需调用实际 CPU 模型。

## 4. 第二阶段：Host 序列化与 Container 门面（P0）

修改 `MnnEngineHost.kt`：

- 提供 `fun probeOpenCl(): MnnResult<BackendCapabilityResult>`；通过现有 `dispatch { ... }` 提交到单线程 executor。
- **不允许直接在 executor 的任务内部再次调用 `dispatch`**，否则可能自锁。
- 任务提交前检查 close/closing，返回 typed error；在 Host close 的已有逻辑外不另开裸线程。
- 探测失败不能改变 `state=READY`/`LOADING`/`GENERATING`，不能置 `runtimeAvailable=false`（除非主 MNN 库本身已不可用）。探测结果与 Host 推理状态分开。
- 若正在生成，probe 可以排队，UI 显示 CHECKING；不要取消生成仅为优先进行探测。
- 避免 Service 同时启动大量 probe：通过状态流 + job guard 对并发自动/手动请求去重。

修改 `MnnRuntimeContainer.kt`：

- 新增 `fun probeOpenCl(): MnnResult<BackendCapabilityResult> = host.probeOpenCl()`（需要将当前传给 inference 的 Host 保存为字段，**不得再创建第二个 Host**）。
- 探测使用现有 Container 生命周期；close 后不可继续调用 probe。
- HTTP `MnnHttpServer` 的模型生成路径不因增加探测而改变。

## 5. 第三阶段：Service 探测及有效后端决策、手动重试与竞态（P0）

修改 `PairRuntimeService.kt`：

- 新增 `_mnnCapabilities: MutableStateFlow<MnnCapabilitySnapshot>` 和 `_effectiveBackendSelection: MutableStateFlow<EffectiveBackendSelection>`，只由 Service（或 Service 协调器）写入，暴露只读 `StateFlow`。**切记两者与 Engine Health 是三种独立状态**。
- `startOptionalMnnRuntime()` 创建并成功启动 Container 后，触发 **异步** probe。探测完成**先更新能力、再在统一协调器解析有效后端**；不能因为探测失败就阻塞启动 broker。首次探测未完成期间仅使用标记为 `resolving` 的临时 CPU 后端。
- 在 `serviceScope` 内记录 `openClProbeJob`；使用 `withContext(Dispatchers.IO)` 调用 `container.probeOpenCl()`，不得在 `Dispatchers.Main` 跑探测。
- 手动重测：新增 `ACTION_MNN_REPROBE_OPENCL`，Controller 向 Service 投递 intent；复用同一探测任务机制，不要单独引入第二个对 Service 的静态入口。
- 幂等策略：正在 CHECKING 时重复点击重测不启动第二次 native probe；无需并行创建多个 `MNN::RuntimeInfo`。
- **结果防过期**：发布前验证 `mnnRuntimeContainer === probedContainer` 且本次 probe generation 与当前一致；Service 停止或 Container 更换后旧结果不可写入新状态流。
- Service 停止时取消等待中的 Coroutine，并将能力置为 UNKNOWN / RUNTIME_STOPPED；不要误认为 Coroutine cancellation 能安全强制停止已进入 driver 的 JNI 调用。
- 不保存 `AVAILABLE` 为长期持久配置。App 更新、Runtime 变体改变、设备重启或驱动环境变化都应重新探测。**仅保存用户手动偏好/Auto 模式**；重测更新 Auto 默认结果，不能写 manual。
- 探测使用独立失败类型，不借用 `_mnnLocalEngine.errorCode`；该字段代表整体 MNN Engine 故障。
- `PairRuntimeController.kt` 增加 `val mnnCapabilities: StateFlow<MnnCapabilitySnapshot>`、`val effectiveBackendSelection: StateFlow<EffectiveBackendSelection>`、`fun reprobeOpenCl()`、`suspend fun setManualBackend(backend: MnnBackend)` 和 `suspend fun resetBackendToAuto()`；原 `setPreferredMnnBackend` 根据迁移计划委托到 Manual 行为，不再直接代表有效后端。

**重要的产品边界：** 目前 `PairRuntimeService` 使用 `BrokerSession.proxyEnginesForLocalMnn(mnnAvailable)` 注册本地引擎，它主要依据整体 MNN Health，而非已选择后端是否可实际加载。P0 不应贸然修改 Go Broker 的注册协议。但需明确记录这一旧问题，并在 P1 验收：当用户偏好为不可用 OpenCL 时，避免网关把该后端视为已经具备 *模型级就绪状态*。不要用 OpenCL probe 可用来代替模型加载验证。

## 6. 第四阶段：Android Manifest 与打包（P0）

当前 `AndroidManifest.xml` 未声明 vendor OpenCL 动态库。针对 target Android 12/API 31+，在 `<application>` 内加入：

```xml
<uses-native-library
    android:name="libOpenCL.so"
    android:required="false" />
```

- 必须是 `required="false"`，CPU-only 手机仍能安装 PAIR。
- 这**仅申请访问系统允许公开的厂商库**；不能绕过 vendor namespace 限制，也不保证设备提供这个确切库名。
- 对 Mali/其他供应商，先分析当前测试设备的公开 vendor 库名及 MNN 对应 loader，再按证据追加**可选**声明；不要猜测性地枚举所有私有库名。
- 不把手机 `/vendor/lib64/libOpenCL.so` 复制到 App，也不要要求 root / 修改 SELinux。
- 保留 `-PpairMnnOpenCL=true` opt-in 构建。CPU 构建仍可正常运行并报告 NOT_COMPILED。
- 检验 OpenCL 变体 `libMNN_CL.so` 确实打包，保留 `verify-mnn-runtime-variant.ps1` 关于 `libllm.so` DT_NEEDED 的既有检查。

参照：Android 官方 `<uses-native-library>` 文档、Android native linker namespace 文档。是否能访问 GPU 仍以同一 APK 在真实设备上的 runtime probe 为准。

## 7. 第五阶段：Compose UI（P0）

修改 `ModelHubScreen.kt` 中已有 `MNN Compute Engine` 卡片，不另建一整套 Settings 页面：

- CPU：native runtime 正常时显示“可用”；否则显示“运行时不可用”。
- OpenCL：`UNKNOWN → 未检测`、`CHECKING → 检测中`、`AVAILABLE → 可初始化（未验证模型）`、`UNAVAILABLE/NOT_COMPILED → 当前 APK 未编译 OpenCL`、`UNAVAILABLE/RUNTIME_INIT_FAILED → 当前设备/驱动运行时不可用`、`ERROR → 探测失败，可重试`。
- 增加“重新检测”按钮，只在 Service 运行且无 probe 正在进行时有效。
- 用户仍可手动选择 CPU；OpenCL 选项仅当 `AVAILABLE` 时允许新选择。`UNKNOWN/CHECKING` 显示“检测中”，`UNAVAILABLE`/`ERROR` 提示不可用。已保存的 `Manual(OPENCL)` 若当次不可用，则**保留该持久选择，但有效后端 CPU**，UI 同时显示“手动偏好 OpenCL / 当前安全回退 CPU”，可手动改 CPU；不要在后台假装仍用 OpenCL。
- 增加“自动选择（按能力）/ 恢复自动默认”入口。`Auto` 且 probe `AVAILABLE` 时自动默认 OpenCL，否则 CPU；当用户手动选择 CPU 后即使 probe `AVAILABLE` 也不能覆盖。
- 探测成功与否不可触发 `loadModel`，也不要自动执行测试模型生成；**默认后端变更只在下次推理请求中生效**，不中断当前请求。
- 采用 `collectAsStateWithLifecycle` / 与现有 UI 一致的状态收集模式；不要用独立轮询或 `remember` 自己维护第二份 probe 真相。
- 现有 `MainActivity.kt` / `ModelHubScreen()` 参数增加只读能力状态、有效后端选择/选择模式，以及 `onReprobeOpenCl`、`onResetBackendToAuto` 和手动选择回调。

## 8. 建议的文件修改清单

| 优先级 | 文件 | 类型 |
|---|---|---|
| P0 | `.../mnn/MnnBackendCapability.kt` | 新建，纯 Kotlin 能力状态/错误类型 |
| P0 | `.../mnn/MnnBackendPolicy.kt` | 新建，Auto/Manual 选择、effective 解析纯函数和状态；单测 |
| P0 | `.../mnn/MnnSettingsRepository.kt` | 增加持久化选择模式与旧键迁移；手动优先 |
| P0 | `.../mnn/MnnBackendSelection.kt` | 仅承载生效后端，不独立推断能力 |
| P0 | `.../mnn/MnnRuntime.kt` | 增加 probe 抽象 |
| P0 | `.../mnn/NativeMnn.kt` | 增加 native 声明和类型安全映射 |
| P0 | `android/app/src/main/cpp/NativeMnn.cpp` | 暴露 JNI probe、复用 MNN 检测 |
| P0 | `.../mnn/MnnEngineHost.kt` | 复用 serial executor 执行 probe |
| P0 | `.../mnn/MnnRuntimeContainer.kt` | 提供 Host probe 门面 |
| P0 | `.../runtime/PairRuntimeService.kt` | 能力 StateFlow、有效后端 StateFlow、单一协调器、探测竞态/手动优先、生命周期保护 |
| P0 | `.../runtime/PairRuntimeController.kt` | 将能力/重试/Auto 恢复及手动选择暴露给 UI |
| P0 | `.../models/ModelHubScreen.kt`、`.../MainActivity.kt` | 展示能力、Auto/Manual 模式、实际后端、重测/手动覆盖和恢复自动入口 |
| P0 | `android/app/src/main/AndroidManifest.xml` | 可选厂商 OpenCL 库声明 |
| P0 | `.../test/java/com/nv/pair/mnn/MnnEngineHostTest.kt` | 更新 fake + 探测/并发测试 |
| P0 | `.../test/java/com/nv/pair/mnn/MnnBackendPolicyTest.kt` | 新建，覆盖 Auto/Manual × probe 状态全部组合 |
| P0 | `.../test/java/com/nv/pair/mnn/MnnSettingsRepositoryTest.kt` | 旧键迁移、Auto/Manual 持久化、并发写入和恢复自动 |
| P0 | `.../test/java/com/nv/pair/mnn/MnnRuntimeContainerTest.kt` | 更新 fake + 关闭后访问测试 |
| P0 | `.../androidTest/java/com/nv/pair/mnn/NativeMnnContractInstrumentedTest.kt` | 无模型测试 native probe 和 CPU variant |
| P1 | `.../runtime/PairRuntimeServiceInstrumentedTest.kt` | 重测、stop/restart 后旧结果不污染 |
| P1 | 本地 MNN ready/网关引擎广告路径 | 确保未验证 GPU / 加载失败不被误报为模型 ready |
| P2 | 独立 `:mnn_probe` Android 进程 | 设备厂商 driver 可能导致原生 SIGSEGV/永久阻塞时，为生产版引入隔离探测（不属于首期最小方案） |

注：表中 `.../` 是以 `android/app/src/main/java/com/nv/pair/` 或对应 test 目录为根的简写，Agent 修改前必须确认真实路径。

## 9. 必须覆盖的测试矩阵

### JVM 单元测试

1. native `0 / 1 / 2 / 3 / UnsatisfiedLinkError` 各自映射到正确的结果与文案键。
2. `Auto + AVAILABLE => OpenCL`、`Auto + NOT_COMPILED/UNAVAILABLE/ERROR => CPU`、`Auto + CHECKING => 临时 CPU（resolving）`。
3. `Manual(CPU) + AVAILABLE => CPU`，探测和重测都不能覆盖手动 CPU。
4. `Manual(OPENCL) + AVAILABLE => OpenCL`；后续 probe 变不可用 => **有效 CPU 且保留 Manual(OPENCL)**；恢复可用后重新生效。
5. 无旧 preference 键的新安装 => `Auto`；存在旧 `mnn_preferred_backend` 的安装 => `Manual(旧值)`；模式迁移幂等。
6. 手动选择/恢复 Auto 与迟到 probe 并发时，最终选择确定、无状态回退；in-flight generation 不被中断，下一请求生效。
7. Service 没有启动：`UNKNOWN`，绝不显示 Available；启动后 CHECKING -> 终态。
8. CPU 模式 APK -> OpenCL NOT_COMPILED；且 CPU 模型加载/生成回归不受影响。
9. 同时触发自动探测和两次“重试”：只执行一个 native probe。
10. 探测中停止 Service、更换 Container：旧结果不能覆盖新实例的状态。
11. 正在生成时触发探测：不会打断生成；probe 在 Host executor 排队；不并发调用 MNN initialization 和 model generation。
12. Host/Container close 后请求 probe：返回 typed INVALID_STATE 或等价状态，不出现 native use-after-free。
13. 探测不可用不能将现有 READY Engine 标成 ERROR，也不能触发模型 unload。
14. CPU-only 健康、HTTP SSE streaming、取消请求、close timeout、ModelHub 下载删除等现有用例均需回归。

### Android 真机

| 构建 / 设备条件 | 预期 |
|---|---|
| CPU APK，新安装（Auto） | OpenCL = NOT_COMPILED，**有效默认 CPU**；CPU / PAIR 仍正常 |
| OpenCL APK，驱动不可访问或 MNN 初始化失败，Auto | OpenCL = RUNTIME_INIT_FAILED；**默认 CPU**；进程级故障不能保证 catch 防崩溃 |
| OpenCL APK，已知兼容设备，新安装 Auto | Probe = AVAILABLE；**自动默认 OpenCL**；无需模型；仍要单独测试模型加载/生成 |
| OpenCL APK，兼容设备，手动改 CPU 后重启及重测 | 保持 Manual(CPU)，有效 CPU，不被探测改为 OpenCL |
| OpenCL APK，历史保存 OpenCL 偏好但此次不可用 | 显示 Manual(OpenCL) + 有效 CPU 安全回退，不改写偏好；恢复可用后 Manual(OpenCL) 生效 |
| 从老版本升级且曾手动选 CPU | 迁移为 Manual(CPU)，即使新包 OpenCL 可用仍保留 CPU |
| 重复启动/停止 Service、重测 | 无重复启动 native probe、无 stale state、无线程泄漏 |

**注意**：只在有经验证支持的设备上将 `AVAILABLE` 作为硬断言；未知手机的 `RUNTIME_INIT_FAILED` 可能是正确业务结果而非测试失败。驱动 SIGSEGV 不属于 `try/catch` 可恢复错误，不得在首期文档中承诺“永不崩溃”。

## 10. 构建与验收命令（Windows PowerShell，在 `android/` 目录）

```powershell
# JVM unit tests
.\gradlew.bat :app:testDebugUnitTest

# CPU 变体
.\gradlew.bat :app:assembleDebug

# OpenCL 变体（依赖本仓库 WSL+Android NDK 的 MNN 构建环境）
.\gradlew.bat :app:assembleDebug -PpairMnnOpenCL=true

# 连接真机执行 instrumentation tests（按需限定 test class）
.\gradlew.bat :app:connectedDebugAndroidTest -PpairMnnOpenCL=true

# 部署后观察服务和 JNI 日志
adb logcat -s PAIR-MNN PAIR-Runtime linker
```

首次构建需先确认 `android/local.properties` / SDK / WSL / 脚本约定的 NDK 可用；不可因机器缺少编译环境就修改 pinned MNN revision。测试前清理可能的 variant 缓存误用，检查 APK 打包的 `.so` 与当前目标 variant 一致。

验收证据应附：构建命令及退出码、使用的设备型号/Android 版本/GPU/ABI、CPU vs OpenCL APK variant、probe 代码和 UI 截图、模型加载/生成测试结果、无法重现时的诊断日志（脱敏）。

## 11. 分批交给 Coding Agent 的执行顺序

- **PR A（Native + Runtime + 决策纯函数）**：定义类型和 JNI probe、Host 序列化、Container 门面；新增 Auto/Manual 纯函数及单测；确保 CPU APK 仍能编译。完成后不要改 UI。
- **PR B（Settings + Service + UI）**：DataStore 选择意图及迁移、两个 StateFlow、自动初始默认/手动优先/重新探测、Stop 竞态保护、UI Auto/Manual、显示有效后端。特别测试历史 CPU/OpenCL 偏好。
- **PR C（构建/验收）**：Manifest optional native library、双 variant build、设备矩阵和回归检查；完善操作日志与文档。
- **后续 Issue（不阻塞本次）**：不可用后端对应 gateway readiness / engine advertisement；native driver 崩溃隔离进程；细分厂商驱动诊断。

**产品功能验收准则（本版新增）**：全新安装 OpenCL APK 且 probe 成功，默认显示并使用 OpenCL；全新安装 CPU APK 或 OpenCL probe 失败，默认 CPU；用户手动选 CPU 后任何自动探测都不能改成 OpenCL；用户手动选 OpenCL 后不可用时仅安全回退有效 CPU、明确提示、保留偏好；恢复 Auto 后再按最新能力默认；无每请求性能推断、无后台自动 benchmark。

**构建提醒**：当前不加 `-PpairMnnOpenCL=true` 构建的是 CPU APK，因此无论手机 GPU 实际硬件如何，都只会默认 CPU。要达到“有能力就默认 OpenCL”，正式发布/试用的 APK 必须包含经过验收的 OpenCL MNN 变体，并正确声明可选厂商库；构建 flag 与设备 runtime 两级都需满足。

**Agent 代码质量约束**：保留现有 SPDX 头和 Kotlin/C++ 格式规范；Kotlin 类型表达状态，不用裸字符串拼条件；原生接口只做 ABI、字符串/枚举转换和 MNN 调用，不在 JNI 写 UI/存储业务；拒绝“捕获所有异常并假装 CPU 成功”；不在请求路径做重复 OpenCL 初始化；保持可测试依赖注入；先检查相关单测/测试替身，再修改接口；每批变更明确列出可证实通过的测试与未验证项目。

## 12. 边界说明

- 当前 MNN 代码通过 `Interpreter::createRuntime()` 找不到 `MNN_FORWARD_OPENCL` 就判失败；MNN 可能自动建立 CPU 回退，所以结果必须检查**特定 OpenCL 键**。
- Android 新版 `<uses-native-library android:required="false">` 只帮助应用请求厂商公开的非 NDK native library。厂商没有声明公开、权限策略不允许、驱动 ABI 或 OpenCL 内核编译不支持，都可能使 probe 失败。
- `System.loadLibrary("pair_mnn")` 成功不意味着 OpenCL 成功；`createRuntime()` 成功不意味着所选模型会真正跑在 GPU 上。必要时未来增加“GPU 实际推理测试”并采集后端命中和性能指标。
- 推荐首期使用**运行时初始化级能力**作为 UI 选择门槛；真实模型级探测仍由 `loadModel / generate` 承担。
- **首期非隔离 native probe 不能保证执行超时或从厂商驱动挂死恢复**。不能用 `withTimeout` 假装可强行打断阻塞 JNI；如果真机发现稳定性风险，升级为独立进程探测后再默认启用。

### 参考链接

- [PAIR / feature/cloud-provider](https://github.com/afantastic1/Personal-AI-Router/tree/feature/cloud-provider)
- [Android `<uses-native-library>` 官方文档](https://developer.android.com/guide/topics/manifest/uses-native-library-element)
- [Android vendor native library 命名空间](https://source.android.google.cn/docs/core/permissions/namespaces_libraries)
- [MNN Interpreter::createRuntime 实现](https://github.com/alibaba/MNN/blob/master/source/core/Interpreter.cpp)
