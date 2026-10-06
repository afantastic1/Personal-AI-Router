# PAIR Android / Mobile Compute Node 完整开发施工文档

> 文档状态：Architecture Frozen / Implementation Plan  
> 适用项目：NVIDIA Personal-AI-Router Android 移植 + MNN Compute Node 扩展  
> 上游基线：`NVIDIA/Personal-AI-Router` `develop`  
> 当前 Android 基线：`compileSdk=36` / `targetSdk=36` / `minSdk=29`  
> 初始 ABI：`arm64-v8a`  
> 开发平台：Windows + Android ARM64 真机  
> 文档用途：供人类开发者、Claude Code、Codex、OpenCode 等 AI 编程 Agent 逐阶段施工

---

# 1. 项目最终目标

本项目不是简单地把 PAIR 的桌面 UI 移植到 Android，也不是单纯制作一个手机端本地 LLM App。

最终目标是：

> 把 Android 手机变成 PAIR 网络中的完整节点，使手机既可以通过 PAIR 使用其他设备的模型和算力，也可以把自己的 MNN 推理能力作为算力节点提供给 PC、平板、AI Box、其他手机和 Agent。

最终网络形态：

```text
                         PAIR Cluster
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
   Windows PC            Android Phone            AI Box
        │                     │                     │
  Ollama / LM Studio          MNN               MNN / Ollama
        │                     │                     │
      Models                Models                Models
```

每个设备统一称为：

```text
PAIR Node
```

一个 Node 可以拥有三类能力：

```text
Router Capability
Compute Capability
Model Capability
```

例如：

```text
Gaming PC
├── Router: Yes
├── Engine: Ollama
└── Models: Qwen 32B

Android Phone
├── Router: Yes
├── Engine: MNN
└── Models: Qwen 1.7B

Laptop
├── Router: Yes
├── Engine: None
└── Models: None

AI Box
├── Router: Yes
├── Engine: MNN / Ollama
└── Models: Qwen 14B
```

即使 Laptop 没有本地模型，它也仍然可以：

```text
Laptop AI App
      ↓
Laptop PAIR
      ↓
PAIR Routing
      ↓
Gaming PC / Phone / AI Box
      ↓
Inference
```

---

# 2. 产品定义：双阶段 MVP

整个开发过程必须严格拆成两个核心 MVP，禁止同时开发全部能力。

## 2.1 MVP-1：Android Router Node

目标：

```text
Android Client
      ↓
Android PAIR
      ↓
LAN / mTLS
      ↓
PC PAIR
      ↓
Ollama / LM Studio
      ↓
Model
```

也就是：

> 手机使用 PC 的算力。

MVP-1 阶段 Android 不需要 MNN，也不需要本地模型。

验收流程：

```text
Android 启动 PAIR
→ 发现 PC
→ Android 与 PC 配对
→ Android 获取 PC 的模型列表
→ Android PAIR 暴露 localhost API
→ Android 本机应用请求该 API
→ PAIR 将请求路由到 PC
→ PC Engine 完成推理
→ Streaming response 回到 Android
```

MVP-1 完成后，Android 是一个：

```text
Requester / Router Node
```

---

## 2.2 MVP-2：Android Compute Node

目标：

```text
PC Client
    ↓
PC PAIR
    ↓
PAIR Routing
    ↓
Android PAIR
    ↓
MNN Engine
    ↓
Android CPU / GPU
```

也就是：

> PC 使用手机算力。

最终：

```text
Android → PC inference   YES
PC → Android inference   YES
```

MVP-2 完成后，Android 是一个真正的：

```text
PAIR Compute Node
```

---

# 3. 当前项目状态

截至当前开发阶段，已经完成：

```text
[✓] Android Studio 工程创建

[✓] Empty Activity + Jetpack Compose

[✓] compileSdk = 36

[✓] targetSdk = 36

[✓] minSdk = 29

[✓] AGP = 8.11.2

[✓] AndroidX 依赖调整到 API 36 兼容版本

[✓] assembleDebug 成功

[✓] Go Android ARM64 交叉编译成功

[✓] nvpair-node-settings 已验证为 AArch64 ELF

[✓] ELF Magic:
    7F 45 4C 46

[✓] Machine:
    AArch64 / ARM64

[✓] APK assets 打包测试成功
```

但以下方案仅为实验：

```text
assets/bin/
→ copy to filesDir
→ chmod +x
→ ProcessBuilder
```

正式架构中废弃。

原因：

Android 10+ 不应依赖在 App 可写 home 目录中放置并执行任意二进制。

正式方案将在 M1 中重新建立。

---

# 4. 上游 PAIR 当前架构基线

当前 PAIR `develop` 后端构建 12 个 Go binary：

```text
nvpair-ui-broker
nvpair-proxy
nvpair-node-info
nvpair-node-scanner
nvpair-manual-nodes
nvpair-workload-manager
nvpair-errors
nvpair-engine-manager
nvpair-node-settings
nvpair-cluster-manager
nvpair-job-scheduler
nvpair-tui
```

共享代码：

```text
services/shared/
```

当前核心结构：

```text
UI / TUI
   ↓
nvpair-ui-broker
   ↓
broker-supervised workers
```

Android 必须复用这个模型。

---

# 5. 当前 PAIR Proxy 架构

旧版 PAIR 曾经有不同 Engine 的独立 proxy。

当前版本已经统一为：

```text
                    nvpair-proxy
                         │
               ┌─────────┴─────────┐
               │                   │
               ▼                   ▼
        Ollama facade        LM Studio facade
```

未来 MNN 必须扩展为：

```text
                     nvpair-proxy
                          │
           ┌──────────────┼──────────────┐
           │              │              │
           ▼              ▼              ▼
       Ollama         LM Studio          MNN
       facade           facade          facade
```

禁止新建：

```text
nvpair-mnn-proxy
```

除非未来 upstream 架构发生重大变化。

---

# 6. 当前官方 Engine

当前 PAIR 正式 engine identity：

```text
ollama
lmstudio
```

共享定义位于类似：

```text
services/shared/engines/
```

这些 identity 不是 UI 标签，而是跨进程协议。

它们参与：

```text
engine-manager RPC
discovery
modelsByEngine
workload engine field
scheduler
proxy facade
port management
```

因此 MNN 不能只在 Android UI 里增加一行。

必须在 MVP-2 阶段正式扩展：

```text
engine = "mnn"
```

---

# 7. 最重要的架构原则

PAIR Runtime 行为的 Source of Truth 始终是：

```text
services/
```

Android Kotlin 层只负责：

```text
Android Runtime Host
Android Lifecycle
JSON-RPC Client
Foreground Service
Native UI
MNN Runtime Host
```

Android 禁止重新实现：

```text
mDNS discovery logic
cluster membership logic
routing logic
scheduler
model owner selection
failover
cluster certificates
pairing cryptography
worker supervision
```

原则：

> 如果功能属于“PAIR 做什么”，优先修改 Go services；如果功能属于“Android 如何承载/展示 PAIR”，修改 Android。

---

# 8. 总体软件架构

```text
┌────────────────────────────────────────────────┐
│                Android Compose UI              │
│                                                │
│ Overview                                       │
│ Nodes                                          │
│ Models                                         │
│ Jobs                                           │
│ Endpoints                                      │
│ Settings                                       │
│ Diagnostics                                    │
└───────────────────────┬────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────┐
│           Android Application Layer            │
│                                                │
│ PairRepository                                 │
│ PairRuntimeController                          │
│ PairRuntimeService                             │
│ BrokerSession                                  │
│ JsonRpcClient                                  │
│ NetworkMonitor                                 │
│ MulticastLockManager                           │
│ NativeBinaryRegistry                           │
└───────────────────────┬────────────────────────┘
                        │
                 JSON-RPC over stdio
                        │
                        ▼
┌────────────────────────────────────────────────┐
│              nvpair-ui-broker                  │
└───────────────────────┬────────────────────────┘
                        │
               broker-owned workers
                        │
       ┌────────────────┼──────────────────────┐
       │                │                      │
       ▼                ▼                      ▼
 node-scanner        nvpair-proxy        cluster-manager
       │                │                      │
       ├─ scheduler     ├─ facade              ├─ trust
       ├─ workload     │                       └─ pairing
       ├─ errors       └─ routing
       └─ settings
```

MVP-2 再加入：

```text
┌──────────────────────────────────────────────┐
│            Android App Process               │
│                                              │
│ MnnEngineHost                                │
│      │                                       │
│      ▼                                       │
│ MnnHttpServer                                │
│      │                                       │
│      ▼                                       │
│ NativeMnn JNI                                │
│      │                                       │
│      ▼                                       │
│ MNN Runtime                                  │
│ CPU / OpenCL / other supported backend       │
└──────────────────────────────────────────────┘
```

---

# 9. 控制面与数据面必须分离

## 9.1 控制面

```text
Compose
   ↓
ViewModel
   ↓
PairRepository
   ↓
BrokerApi
   ↓
JsonRpcClient
   ↓
nvpair-ui-broker
```

用于：

```text
runtime state
nodes
cluster
settings
jobs
errors
engine state
proxy state
models metadata
```

---

## 9.2 推理数据面

Inference 请求不能经过 Kotlin JSON-RPC。

正确路径：

```text
AI Client
    ↓ HTTP
PAIR Proxy
    ↓
Local / Remote Engine
    ↓
Streaming Response
```

例如 MVP-1：

```text
Android Agent
   ↓
127.0.0.1:<PAIR proxy>
   ↓
Android nvpair-proxy
   ↓
PC nvpair-proxy
   ↓
PC Ollama
```

Android UI 不参与 token forwarding。

---

# 10. Broker 是唯一 Runtime 入口

Android 只能直接启动：

```text
nvpair-ui-broker
```

禁止：

```text
Android → scanner
Android → scheduler
Android → proxy
Android → cluster-manager
```

正确：

```text
Android
   ↓
nvpair-ui-broker
   ↓
worker tree
```

只有 Broker 管理 workers。

这样可以避免：

```text
double supervision
duplicate workers
port conflicts
inconsistent lifecycle
```

---

# 11. stdout / stderr 是协议边界

Broker stdio 模式：

```text
stdout = newline-delimited JSON-RPC 2.0
stderr = log stream
```

绝对禁止：

```kotlin
redirectErrorStream(true)
```

正确：

```text
broker.stdout
   ↓
JsonRpcReader

broker.stderr
   ↓
RuntimeLogReader
```

否则任何日志都会破坏 JSON-RPC framing。

---

# 12. Android 推荐目录

```text
Personal-AI-Router/
│
├── services/
├── desktop/
├── docs/
│
└── android/
    ├── README.md
    ├── UPSTREAM_BASE.txt
    │
    ├── docs/
    │   ├── PAIR_ANDROID_IMPLEMENTATION_PLAN.md
    │   ├── architecture.md
    │   ├── runtime.md
    │   ├── mnn-engine.md
    │   ├── testing.md
    │   └── upstream-delta.md
    │
    ├── scripts/
    │   ├── build-pair-services.ps1
    │   ├── stage-native-binaries.ps1
    │   └── verify-native-binaries.ps1
    │
    └── app/
        └── src/main/
            ├── AndroidManifest.xml
            │
            └── java/com/pair/android/
                │
                ├── PairApplication.kt
                ├── MainActivity.kt
                │
                ├── runtime/
                │   ├── PairRuntimeService.kt
                │   ├── PairRuntimeController.kt
                │   ├── PairRuntimeState.kt
                │   ├── PairProcess.kt
                │   ├── NativeBinaryRegistry.kt
                │   ├── RuntimeEnvironment.kt
                │   └── BrokerRestartPolicy.kt
                │
                ├── rpc/
                │   ├── JsonRpcClient.kt
                │   ├── BrokerApi.kt
                │   ├── BrokerSession.kt
                │   ├── RpcRequest.kt
                │   ├── RpcResponse.kt
                │   └── RpcNotification.kt
                │
                ├── data/
                │   ├── PairRepository.kt
                │   ├── UiPreferencesRepository.kt
                │   └── model/
                │
                ├── network/
                │   ├── MulticastLockManager.kt
                │   ├── NetworkMonitor.kt
                │   └── LocalNetworkPermissionController.kt
                │
                ├── mnn/
                │   ├── MnnEngineHost.kt
                │   ├── MnnRuntime.kt
                │   ├── MnnModelManager.kt
                │   ├── MnnHttpServer.kt
                │   └── NativeMnn.kt
                │
                └── ui/
                    ├── navigation/
                    ├── overview/
                    ├── nodes/
                    ├── models/
                    ├── jobs/
                    ├── endpoints/
                    ├── settings/
                    └── diagnostics/
```

初期不要引入：

```text
Hilt
Room
复杂 multi-module
过度 Clean Architecture
```

---

# 13. Upstream 基线冻结

每个大阶段开始前：

```powershell
git status --short
git branch --show-current
git rev-parse HEAD
```

将基线写入：

```text
android/UPSTREAM_BASE.txt
```

例如：

```text
PAIR upstream:
NVIDIA/Personal-AI-Router

Base branch:
develop

Base commit:
<sha>

Android:
compileSdk 36
targetSdk 36
minSdk 29
ABI arm64-v8a
```

一个 milestone 施工过程中：

```text
禁止自动 git pull
禁止自动 rebase
禁止自动 merge upstream
```

完成并测试后再同步上游。

---

# 14. 文档与源码优先级

如果信息冲突：

```text
当前 checkout 的 Go source
        >
同 commit 的 service README/spec
        >
generated service API documentation
        >
本施工文档
        >
AI 记忆
```

Agent 禁止靠“印象”修改 PAIR。

---

# 15. PC 开发环境

开发阶段 PC 不需要正式安装 PAIR Installer。

推荐：

```text
PC = PAIR source runtime
Android = Debug APK
```

PC repo：

```text
D:\code\Personal-AI-Router
```

---

# 16. PC 完整 Desktop 模式

主要联调方式：

```powershell
cd D:\code\Personal-AI-Router\desktop

npm install
npm start
```

用途：

```text
Android ↔ PC discovery
cluster pairing
nodes UI
models
jobs
proxy
MNN remote node
```

---

# 17. PC services-only 模式

调试后端：

```powershell
cd D:\code\Personal-AI-Router\services

.\build.bat
```

产物：

```text
services\build\bin\
```

运行 TUI：

```powershell
.\build\bin\nvpair-tui.exe
```

或者 Broker：

```powershell
.\build\bin\nvpair-ui-broker.exe
```

测试：

```powershell
'{"jsonrpc":"2.0","id":1,"method":"ping"}' |
    .\build\bin\nvpair-ui-broker.exe
```

---

# 18. 禁止 PC 同时跑正式版与源码版 PAIR

避免：

```text
installed PAIR
+
source PAIR
```

同时运行。

可能产生：

```text
port conflict
duplicate mDNS records
duplicate brokers
duplicate workers
engine ownership conflict
cluster confusion
```

开发时：

```text
正式安装 PAIR = 退出
源码 PAIR = 运行
```

---

# 19. Go Android 构建系统

创建：

```text
android/scripts/build-pair-services.ps1
```

作为 Android Go binary 唯一构建入口。

统一：

```powershell
$env:GOOS="android"
$env:GOARCH="arm64"
$env:CGO_ENABLED="0"
```

构建脚本必须：

```text
读取需要的 service list
读取版本信息
逐 module build
输出 Android ARM64 ELF
验证 ELF
stage 到 Android native libs
```

禁止开发者长期手工逐目录编译。

---

# 20. M1 Native Executable Packaging

这是当前正在进行的阶段。

当前 assets 方案废弃。

第一步先使用已经可编译的：

```text
nvpair-node-settings
```

做 native executable smoke test。

---

# 21. Android native executable 正式打包方向

将：

```text
nvpair-node-settings
```

stage 为类似：

```text
libnvpair_node_settings.so
```

注意：

`.so` 只是 APK native packaging 所需的命名形式。

文件本身仍应为：

```text
AArch64 PIE executable
```

而不是 JNI dynamic library。

APK 安装后通过：

```kotlin
applicationInfo.nativeLibraryDir
```

获取安装路径。

禁止：

```text
/data/app/...
```

硬编码。

---

# 22. Generated jniLibs

不要长期手工维护：

```text
app/src/main/jniLibs
```

推荐：

```text
app/build/generated/pairJniLibs/
└── arm64-v8a/
```

Gradle 将 generated directory 加入 native libs source set。

流程：

```text
Go source
   ↓
Android cross compile
   ↓
stage-native-binaries.ps1
   ↓
build/generated/pairJniLibs/arm64-v8a
   ↓
Gradle
   ↓
APK
```

---

# 23. M1 技术 Gate

必须在真实 ARM64 手机上证明：

```text
APK
 ↓
nativeLibraryDir
 ↓
PAIR executable
 ↓
ProcessBuilder
 ↓
process starts successfully
```

验收：

```text
[ ] binary present

[ ] ELF header correct

[ ] machine AArch64

[ ] process starts

[ ] stdout readable

[ ] stderr readable

[ ] stdout/stderr separated

[ ] process can exit gracefully

[ ] no executable copied to filesDir
```

如果该方案在目标 Android / OEM 上不可持续：

停止 M2。

评估 Plan-B：

```text
Go monolithic runtime
→ shared library
→ JNI host
```

禁止退回：

```text
filesDir + chmod + exec
```

---

# 24. NativeBinaryRegistry

创建：

```text
NativeBinaryRegistry.kt
```

它是 native executable path 的唯一入口。

接口：

```text
broker()
scanner()
proxy()
clusterManager()
settings()
scheduler()
workloadManager()
errors()
manualNodes()
engineManager()
nodeInfo()
```

其他代码不得自行拼：

```kotlin
File(nativeLibraryDir, "lib...")
```

---

# 25. Runtime Environment

启动 Broker 时明确设置：

```text
HOME
XDG_CONFIG_HOME
XDG_CACHE_HOME
TMPDIR
NVPAIR_LOG_LEVEL
```

建议：

```text
HOME=<filesDir>

XDG_CONFIG_HOME=<filesDir>/pair-config

XDG_CACHE_HOME=<cacheDir>/pair-cache

TMPDIR=<cacheDir>/pair-tmp
```

目的是让 PAIR 原有 per-user data path 尽量保持工作。

优先：

```text
环境适配
```

而不是：

```text
为 Android 修改每个 Go service 的存储代码
```

---

# 26. Cluster Identity 与备份

PAIR cluster state 包含：

```text
node identity
private key
certificate
trusted peers
cluster identity
```

属于设备身份。

初始版本建议：

```xml
android:allowBackup="false"
```

防止这些文件被备份并恢复到另一台设备。

未来如果需要备份 UI preferences：

再建立精确 backup rules。

---

# 27. M2 Broker + Scanner

M1 通过后开始。

交叉编译：

```text
nvpair-ui-broker
nvpair-node-scanner
```

Android stage：

```text
libnvpair_ui_broker.so
libnvpair_node_scanner.so
```

Broker 启动必须显式指定：

```text
--scanner-path <absolute nativeLibraryDir path>
```

不要依赖 upstream 默认 sibling filename，因为 Android 中 binary 名字被包装过。

---

# 28. M2 需要创建的 Android 类

```text
runtime/PairProcess.kt

rpc/JsonRpcClient.kt

rpc/BrokerSession.kt

rpc/BrokerApi.kt
```

第一阶段不做复杂 UI。

只要：

```text
process
RPC
logcat
```

工作即可。

---

# 29. Broker Ready 规则

以下不代表 runtime ready：

```text
ProcessBuilder.start()
```

必须等待 Broker：

```text
app:ready
```

才能认为：

```text
PAIR Runtime = RUNNING
```

startup timeout：

```text
15 seconds
```

收到 ready 后调用：

```text
ping
```

验证 RPC path。

---

# 30. M2 验收标准

```text
[ ] broker starts

[ ] scanner starts

[ ] app:ready received

[ ] ping succeeds

[ ] broker version available

[ ] stderr logs visible separately

[ ] malformed JSON frame does not crash UI

[ ] process exit is detected

[ ] pending calls fail on disconnect
```

---

# 31. JsonRpcClient 设计

只能存在一个正式：

```text
JsonRpcClient
```

禁止各模块直接：

```text
process.outputStream.write(...)
```

---

# 32. JSON-RPC Writer

所有 outgoing request：

```text
Channel<RpcOutbound>
    ↓
single writer coroutine
    ↓
stdin
```

每条消息：

```json
{"jsonrpc":"2.0","id":1,"method":"ping"}
```

结尾：

```text
\n
```

然后：

```text
flush
```

---

# 33. JSON-RPC Reader

stdout 只有一个 reader：

```text
BufferedReader
  ↓
readLine()
  ↓
JSON parse
```

分类：

```text
id + result
→ success response

id + error
→ error response

method without id
→ notification
```

未知字段不得导致进程崩溃。

---

# 34. Request ID

使用：

```text
AtomicLong
```

例如：

```text
1
2
3
4
...
```

---

# 35. Pending Requests

维护：

```text
Map<Long, CompletableDeferred<...>>
```

流程：

```text
call()
  ↓
new id
  ↓
pending[id] = deferred
  ↓
write request
  ↓
reader gets response
  ↓
remove pending
  ↓
complete deferred
```

Broker 退出时：

```text
fail all pending requests
```

统一异常：

```text
BrokerDisconnectedException
```

---

# 36. RPC Timeout

建议：

```text
normal control RPC = 5s

cluster pairing = 30s

broker startup ready = 15s
```

禁止无限等待。

---

# 37. Broker Notification Router

建立：

```text
BrokerNotificationRouter
```

负责：

```text
app:ready

discovery:nodes-changed

workloads:*

cluster:*

nodes:*

engine:*

errors:*

proxy notifications
```

未知 notification：

```text
log method name
ignore safely
```

禁止打印完整敏感 payload。

---

# 38. Logging 安全规则

禁止记录：

```text
prompt
messages
response body
pairing PIN
API key
Authorization
Cookie
private key
certificate key material
```

允许：

```text
RPC method
request id
latency
node id
engine
model id
job id
PID
exit code
service version
```

---

# 39. M3 Foreground Runtime

创建：

```text
PairRuntimeService
PairRuntimeController
PairRuntimeState
BrokerRestartPolicy
MulticastLockManager
```

Service 是整个 PAIR runtime 的 Android 生命周期 owner。

---

# 40. Foreground Service 类型

使用：

```text
connectedDevice
```

Manifest 规划：

```text
INTERNET
ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE
CHANGE_WIFI_MULTICAST_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_CONNECTED_DEVICE
```

Android 13+：

```text
POST_NOTIFICATIONS
```

Service：

```xml
<service
    android:name=".runtime.PairRuntimeService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

---

# 41. Runtime State Machine

禁止多个 Boolean 拼状态。

使用：

```text
STOPPED
  ↓
STARTING
  ↓
WAITING_READY
  ↓
RUNNING
  ↓
STOPPING
  ↓
STOPPED
```

异常：

```text
STARTING / WAITING_READY
    ↓
STARTUP_FAILED

RUNNING
    ↓
CRASHED
    ↓
RESTART_BACKOFF
    ↓
STARTING
```

另有：

```text
desiredRunning
```

表示用户期望。

---

# 42. Broker Restart Policy

Android 只 restart：

```text
broker
```

不单独 restart worker。

建议：

```text
1s
2s
4s
8s
16s
```

一个 unhealthy streak：

```text
最多 5 次
```

稳定运行：

```text
60 秒
```

后 reset。

---

# 43. MulticastLock

PAIR discovery 使用 mDNS：

```text
UDP 5353
```

Android Wi-Fi 可能过滤 multicast。

因此：

```text
PairRuntimeService start
→ MulticastLock.acquire()
```

停止：

```text
PairRuntimeService stop
→ MulticastLock.release()
```

MulticastLock 生命周期跟 Runtime，而不是 Activity。

---

# 44. Android 17 Local Network Permission

当前：

```text
targetSdk = 36
```

不要提前申请：

```text
ACCESS_LOCAL_NETWORK
```

未来升级：

```text
targetSdk >= 37
```

再实现：

```text
Manifest
+
runtime permission flow
```

可提前创建：

```text
LocalNetworkPermissionController
```

但 target 36 时是 no-op。

---

# 45. Foreground Notification

运行时展示：

```text
PAIR is running

Nodes: N
Status: Connected
```

按钮：

```text
Open
Stop
```

禁止展示：

```text
prompt
PIN
API key
response content
```

---

# 46. Shutdown 顺序

用户 Stop：

```text
desiredRunning=false
        ↓
send broker shutdown if supported
        ↓
close broker stdin
        ↓
broker gracefully shuts workers
        ↓
wait broker exit
        ↓
release MulticastLock
        ↓
stopForeground
        ↓
stopSelf()
```

禁止首选：

```text
destroyForcibly()
```

---

# 47. App 进程意外死亡

由于 Broker 通过 stdio 被 Android parent 持有：

```text
Android dies
→ pipe closes
→ Broker gets EOF
→ Broker shuts down workers
```

这是使用 stdio 而不是独立 Unix socket 的重要优势。

后续可结合：

```text
START_STICKY
```

和：

```text
desiredRunning
```

恢复服务。

---

# 48. M4 Discovery

实现：

```text
discovery:subscribe
discovery:get-nodes
discovery:nodes-changed
```

PairRepository 持有当前节点列表。

---

# 49. Discovery 初始化竞态

不能简单：

```text
subscribe
getNodes
apply getNodes forever
```

因为 notification 可能先于 snapshot response 到达。

推荐维护：

```text
nodesGeneration
```

流程：

```text
subscribe

generationBefore = nodesGeneration

getNodes()

if generation still unchanged:
    apply response
else:
    ignore stale response
```

`discovery:nodes-changed` 本身携带完整 snapshot，因此可以作为权威 push state。

---

# 50. M4 测试环境

```text
Android Phone
+
Windows PC
+
same Wi-Fi
```

PC 从源码运行 PAIR。

验收：

```text
[ ] Android sees PC

[ ] hostUuid correct

[ ] node name correct

[ ] IP correct

[ ] trusted state correct

[ ] modelsByEngine visible

[ ] PC offline detected

[ ] PC online again detected

[ ] Wi-Fi reconnect recovers
```

---

# 51. M5 Cluster Pairing

新增 Android Go binary：

```text
nvpair-cluster-manager
nvpair-node-settings
```

Android 不自己实现配对协议。

通过 broker 调：

```text
cluster:*
nodes:*
settings/*
```

---

# 52. M5 UI

支持：

```text
Create Cluster

Invite Node

Respond to Invite

Pairing PIN

Pending Invites

Cancel Invite

Leave Cluster

Remove Member
```

PIN 只显示给用户，不进入日志。

---

# 53. M5 验收

```text
[ ] Android ↔ PC pairing works

[ ] trust store generated

[ ] mTLS works

[ ] Android restart preserves trust

[ ] PC restart preserves trust

[ ] leave works

[ ] remove works

[ ] PIN never logged
```

---

# 54. M6 Android Router Node

增加：

```text
nvpair-proxy
nvpair-job-scheduler
nvpair-workload-manager
nvpair-errors
```

初期可以暂缓：

```text
nvpair-node-info
nvpair-engine-manager
nvpair-manual-nodes
```

因为 Android 在 MVP-1 是 requester/router-only。

---

# 55. Android Proxy

当前 PAIR 一个：

```text
nvpair-proxy
```

可以 hosting 多 engine facade。

MVP-1 至少启用：

```text
ollama
lmstudio
```

Endpoint port 必须从 runtime status 获取。

禁止 UI 假设：

```text
11434 永远存在
```

---

# 56. MVP-1 Inference Flow

PC：

```text
PAIR source
+
Ollama
+
Qwen
```

Android：

```text
PAIR Android
```

请求：

```text
Android Client
     ↓
Android local PAIR proxy
     ↓
PAIR model-owner routing
     ↓
PC
     ↓
Ollama
     ↓
Qwen
```

---

# 57. M6 验收

```text
[ ] PC models appear on Android

[ ] Android proxy facade ready

[ ] /v1/models works

[ ] /v1/chat/completions works

[ ] streaming works

[ ] workload visible

[ ] destination is PC

[ ] PC offline gives clear error/failover

[ ] Kotlin does not forward inference body/token stream
```

---

# 58. MVP-1 Definition of Done

全部满足：

```text
[ ] Android runtime starts

[ ] broker ready

[ ] foreground background lifecycle correct

[ ] mDNS discovery works

[ ] Android ↔ PC pairing works

[ ] trust survives restart

[ ] remote models visible

[ ] Android local proxy ready

[ ] Android → PC inference works

[ ] streaming response works

[ ] Wi-Fi reconnect works

[ ] broker crash recovery works
```

完成后：

```text
Android Router Node = DONE
```

---

# 59. MVP-2 起点：不要直接改 PAIR

MVP-1 稳定后先实现独立 MNN Runtime。

先证明：

```text
Android App
→ MNN
→ Local Model
→ Inference
```

完全工作。

PAIR integration 后做。

---

# 60. M7 Android MNN Runtime

模块：

```text
MnnRuntime
MnnEngineHost
NativeMnn JNI
MnnModelManager
```

第一阶段：

```text
CPU
```

先通过。

第二阶段：

```text
OpenCL
```

如果设备支持。

不要让 GPU 成为第一验收 gate。

---

# 61. M7 MNN Runtime API

建议内部 Kotlin API：

```text
loadModel(modelPath, backend)

unloadModel()

generate(request)

generateStream(request)

cancel(requestId)

getStatus()

getLoadedModel()

getMetrics()
```

状态：

```text
UNLOADED
LOADING
READY
GENERATING
ERROR
```

不要把 MNN native state 暴露给 UI。

---

# 62. M7 验收

```text
[ ] CPU model load

[ ] generation

[ ] streaming tokens

[ ] cancel

[ ] repeated inference

[ ] unload

[ ] reload

[ ] model switching

[ ] memory usage reasonable

[ ] OpenCL optional path handled

[ ] unsupported backend gives clear fallback/error
```

---

# 63. M8 MNN Local HTTP Engine

不要让 PAIR Go 直接调用 JNI。

建立：

```text
MnnHttpServer
```

绑定：

```text
127.0.0.1
```

最小 API：

```text
GET /health

GET /v1/models

POST /v1/chat/completions
```

后续：

```text
POST /v1/completions

POST /v1/embeddings
```

---

# 64. MNN Internal Control API

可建立：

```text
/internal/status

/internal/start

/internal/stop

/internal/models/load

/internal/models/unload
```

全部：

```text
loopback only
```

不要开放到 LAN。

---

# 65. 为什么 MNN 使用 HTTP 边界

正确：

```text
PAIR
 ↓ HTTP
MNN Engine Host
 ↓ JNI
MNN
```

而不是：

```text
PAIR Go
 ↓ JNI bridge
Kotlin
 ↓ JNI
MNN
```

HTTP 边界让 MNN Runtime：

```text
可独立测试
可复用
可迁移到 AI Box / Linux ARM
与 PAIR core 低耦合
```

---

# 66. M8 验收

```text
[ ] /health

[ ] /v1/models

[ ] /v1/chat/completions

[ ] streaming HTTP

[ ] cancellation

[ ] only loopback bind

[ ] app restart recovery

[ ] MNN Runtime crash does not corrupt PAIR state
```

---

# 67. M9 正式加入 engine=mnn

此阶段才修改 PAIR Core。

目标：

```text
engine = "mnn"
```

禁止：

```text
MNN pretending to be Ollama

MNN pretending to be LM Studio
```

---

# 68. MNN Engine 需要修改的区域

至少审计：

```text
services/shared/engines/

services/shared/noderec/

services/nvpair-proxy/

services/nvpair-engine-manager/

services/nvpair-ui-broker/

services/nvpair-job-scheduler/

services/tests/

desktop bridge/contracts

desktop UI if displaying engine names
```

具体以当前源码为准。

---

# 69. MNN Engine Identity

在 shared engine registry 增加：

```text
Name = "mnn"
DisplayName = "MNN"
```

还需要：

```text
DiscoveryService
FacadePort
EnginePortBase
PortFile
```

端口不要在本文提前锁定数字。

M9 开始前先做：

```text
PAIR port map audit
```

然后统一分配。

禁止不同模块各自写 magic number。

---

# 70. MNN Proxy Profile

当前 `nvpair-proxy` 使用 table-driven engine profile。

MNN 增加第三个 profile。

推荐：

```text
ModelNaming = exactID
```

路由：

```text
GET /v1/models

POST /v1/chat/completions

POST /v1/completions

POST /v1/embeddings
```

初期不要求：

```text
/api/generate
/api/chat
```

即：

```text
MNN = OpenAI-compatible engine
```

---

# 71. Discovery ServiceMNN

在 noderec/service key 中增加 MNN。

要求：

```text
compact
unique
not conflict
```

Android 节点最终应能广告：

```text
services:
  mnn

modelsByEngine:
  mnn:
    - qwen...
```

---

# 72. Engine Manager 与 MNN

当前 engine-manager 是 manifest-driven。

应优先复用：

```text
manifest
```

而不是在 Go 中写大量：

```text
if engine == "mnn"
```

---

# 73. MNN Control Adapter

因为 MNN Host 运行在 Android App Process，engine-manager 无法像普通 process engine 一样直接管理 JNI runtime。

推荐增加小型 helper：

```text
nvpair-mnn-control
```

职责：

```text
start
stop
status
load
unload
```

helper 通过：

```text
127.0.0.1 internal control HTTP
```

调用 Android MnnEngineHost。

然后 manifest 可以利用：

```text
runtime.mode = command
```

结构：

```text
engine-manager
      ↓
nvpair-mnn-control
      ↓
localhost control HTTP
      ↓
MnnEngineHost
```

---

# 74. MNN Manifest

例如：

```text
mnn.json
```

支持平台：

```text
android/arm64
```

提供：

```text
runtime
ready
health
list_models
loaded_models
```

Model inventory 真相源：

```text
MNN Engine
```

不是 discovery hardcode。

---

# 75. PC 端对 MNN 的要求

MVP-2 时 PC 不需要安装 MNN Runtime。

但 PC PAIR 必须理解：

```text
mnn engine identity
mnn discovery
mnn proxy facade
```

所以 PC 运行：

```text
我们的 PAIR fork
```

PC 上：

```text
local MNN runtime = unavailable
```

但：

```text
MNN requester facade = available
```

从而：

```text
PC Client
   ↓
PC MNN PAIR facade
   ↓
Android
   ↓
Android MNN
```

---

# 76. M10 PC → Android Inference

Android：

```text
PAIR fork
+
MNN Engine
+
Qwen
```

PC：

```text
PAIR fork
```

PC 不要求本地 MNN。

---

# 77. M10 Flow

```text
PC Client
  ↓
PC nvpair-proxy / MNN facade
  ↓
model owner filtering
  ↓
scheduler ordering
  ↓
Android node
  ↓
mTLS
  ↓
Android nvpair-proxy
  ↓
Android local MNN backend
  ↓
MNN
```

---

# 78. M10 验收

```text
[ ] PC discovers Android MNN service

[ ] PC sees modelsByEngine.mnn

[ ] PC /v1/models contains phone model

[ ] PC request routes to Android

[ ] Android executes MNN

[ ] stream returns to PC

[ ] workload destination is Android

[ ] Android screen-off still works

[ ] repeated requests stable

[ ] cancellation works

[ ] model unload updates availability

[ ] PC has no local MNN runtime and still can request Android MNN
```

---

# 79. MVP-2 Definition of Done

必须同时：

```text
Android → PC inference PASS

PC → Android inference PASS
```

完成后：

```text
Android Compute Node = DONE
```

---

# 80. 当前 PAIR 跨 Engine 限制

即使一个 `nvpair-proxy` 进程托管多个 facade：

```text
ollama
lmstudio
mnn
```

也不意味着已经有：

```text
one endpoint
→ auto choose any engine
```

Engine facade 仍有自己的：

```text
routing surface
model naming
protocol behavior
```

因此：

```text
Unified API
```

必须单独实现。

---

# 81. M11 Model Hub

MVP-2 完成后开始。

来源：

```text
ModelScope

HuggingFace

Local Import
```

职责：

```text
search

catalog

metadata

download

resume

checksum

conversion

deployment

delete
```

不负责 routing。

---

# 82. ModelArtifact

统一数据模型：

```text
ModelArtifact
├── id
├── name
├── source
├── repoId
├── revision
├── format
├── size
├── quantization
├── architecture
├── contextLength
├── license
├── downloadState
├── localPath
├── mnnCompatible
└── runtimeMetadata
```

---

# 83. Download State Machine

```text
NOT_INSTALLED
    ↓
QUEUED
    ↓
DOWNLOADING
    ↓
VERIFYING
    ↓
PREPARING
    ↓
INSTALLED
```

异常：

```text
PAUSED
FAILED
CANCELED
```

必须支持：

```text
resume
```

---

# 84. 模型存储

模型不能放 APK。

推荐：

```text
models/
  <model-id>/
    metadata.json
    config.json
    tokenizer...
    weights...
```

需要明确：

```text
App reset
PAIR reset
Model delete
App uninstall
```

各自行为。

---

# 85. M12 Unified API Gateway

目标：

```text
URL
KEY
MODEL
```

供：

```text
TAVO
Agent
Third-party App
```

统一使用。

不要把这个功能硬塞进现有 PAIR native proxy。

新增：

```text
PAIR API Gateway
```

---

# 86. Unified Gateway Architecture

```text
Client
   ↓
Unified OpenAI Endpoint
   ↓
Auth
   ↓
Model Policy
   ↓
Engine Resolver
   ↓
PAIR engine facade
   ↓
PAIR node routing
```

分工：

```text
Gateway:
choose engine/model

PAIR:
choose node
```

---

# 87. Auto Model

支持：

```text
model = "auto"
```

但：

```text
Auto Model != PAIR Scheduler
```

PAIR Scheduler：

```text
model known
→ choose node
```

Auto Model：

```text
task / policy
→ choose model
```

两层必须隔离。

---

# 88. Auto Model 第一阶段

不要一开始做复杂 AI classifier。

先做：

```text
auto-fast

auto-balanced

auto-best
```

配置型策略。

例如依据：

```text
available model
engine
node state
latency class
model size
user preference
```

---

# 89. M13 External LAN Gateway

PAIR native plaintext proxy 必须保持：

```text
loopback only
```

不要修改为：

```text
0.0.0.0
```

给普通 LAN Client 访问。

如果要让没有 PAIR 的其他设备调用 Android：

新增：

```text
PAIR LAN Gateway
```

---

# 90. LAN Gateway Architecture

```text
Remote Device
    ↓
API Key / TLS
    ↓
PAIR LAN Gateway
    ↓
localhost Unified API
    ↓
PAIR
```

默认：

```text
disabled
```

用户显式开启：

```text
Allow other devices
```

---

# 91. Gateway Security

至少：

```text
API key

rate limit

selected network

bind control

request size limit

timeout

sanitized audit log
```

API key：

```text
Android Keystore
```

不能明文保存在：

```text
SharedPreferences
DataStore
settings.json
```

---

# 92. UI 最终信息架构

底部导航建议：

```text
Overview
Nodes
Models
Jobs
Settings
```

Diagnostics 可放 Settings 内。

---

# 93. Overview

显示：

```text
PAIR Running / Stopped

This Device

Router Capability

Compute Capability

MNN Backend

Cluster status

Available nodes

Available models

API endpoints

Active jobs
```

---

# 94. Nodes

每个节点：

```text
name

hostUuid

online

trusted

clustered

IP

engines

modelsByEngine

loadedByEngine

workload
```

操作：

```text
Pair

Remove

Details
```

---

# 95. Models

页面：

```text
Installed

Model Hub

Downloads
```

Model card：

```text
name

source

size

format

quantization

installed

loaded

backend compatibility

download progress
```

---

# 96. Jobs

显示：

```text
model

engine

node

state

start time

duration

latency
```

不要展示：

```text
prompt
response content
```

---

# 97. Settings

```text
Service

Cluster

MNN Backend

Models Storage

Routing

Unified API

LAN Access

Logs

Diagnostics

About
```

---

# 98. Diagnostics

必须显示：

```text
Android version

device ABI

kernel/page size

app version

PAIR core version

PAIR core git SHA

nativeLibraryDir

broker binary exists

worker binary exists

broker PID

broker uptime

runtime state

last exit code

MulticastLock state

network transport

cluster state

node count

proxy facade status

proxy ports

MNN status

MNN backend

loaded model

last startup error
```

提供：

```text
Copy sanitized diagnostics
```

---

# 99. Debug Build 显示 Git SHA

Android Debug Diagnostics 应包含：

```text
PAIR Core Commit
```

PC：

```powershell
git rev-parse --short HEAD
```

Android 与 PC 联调时确认：

```text
same / compatible core revision
```

避免协议漂移误判为网络 Bug。

---

# 100. node-info Android 支持

MVP-1 可以不实现完整 node-info。

禁止假 telemetry：

```text
GPU = 0
Memory = 0
```

未知就：

```text
unknown
```

PAIR scheduler 对 missing/stale telemetry 使用 neutral behavior。

MVP-2 后再开发：

```text
AndroidNodeInfoProvider
```

收集：

```text
CPU
RAM
GPU capability
temperature if available
backend
memory
```

不得依赖 root。

---

# 101. 16 KB Page Size

项目包含：

```text
Go ELF
JNI
MNN native libs
```

发布前必须支持 16 KB page-size Android。

验收至少：

```text
ELF alignment

APK native alignment

broker executes

scanner executes

proxy executes

MNN loads

inference works
```

至少在：

```text
16 KB page-size emulator/device
```

测试一次。

---

# 102. ABI 策略

首发只支持：

```text
arm64-v8a
```

不要同时维护：

```text
armeabi-v7a
x86
x86_64
```

需要 Emulator CI 时再增加：

```text
x86_64
```

并单独构建 Go：

```text
GOARCH=amd64
```

---

# 103. Android 后台稳定性

必须测试：

```text
Activity close

screen off

Wi-Fi off/on

Wi-Fi switch

network loss

broker crash

worker crash

PC offline

PC online

low memory

long generation

stream cancel

app reopen

service restart
```

---

# 104. Soak Test

最低：

```text
30 min screen-off
```

MVP-2 后增加：

```text
multi-hour soak
```

记录：

```text
process restarts

memory growth

CPU idle usage

battery usage

network recovery
```

---

# 105. Android DataStore 职责

只保存 Android UI / product preference：

```text
theme

onboardingComplete

desiredRuntimeRunning

diagnostics preference

LAN gateway enabled

user UI choices
```

不保存：

```text
cluster trust truth
node list truth
routing state truth
jobs truth
engine state truth
```

---

# 106. 不使用 Room 复制 PAIR 状态

禁止：

```text
Go state
↓
copy to Room
↓
UI state
```

否则容易：

```text
Room stale
≠
PAIR current state
```

UI 使用：

```text
StateFlow
```

基于 Broker 当前状态。

---

# 107. PairRepository

建议暴露：

```text
StateFlow<RuntimeState>

StateFlow<List<Node>>

StateFlow<ClusterState>

StateFlow<List<Job>>

StateFlow<List<PairError>>

StateFlow<List<Model>>

StateFlow<EndpointState>

StateFlow<MnnState>
```

ViewModel 不直接访问 Broker process。

---

# 108. Build Variant

```text
debug
release
```

Debug：

```text
diagnostics rich
symbols preserved where useful
debug log optional
git SHA visible
```

Release：

```text
log info
security defaults
release signing
diagnostics sanitized
```

不要太早 aggressive R8。

---

# 109. Dependency Version Policy

当前：

```text
compileSdk 36
targetSdk 36
AGP 8.11.2
```

依赖全部固定版本。

禁止：

```text
+
latest
dynamic versions
```

如果 library 要求 API 37：

优先：

```text
选择兼容 API 36 的版本
```

而不是自动升级整个 Android toolchain。

---

# 110. Agent 开发总规则

所有 AI Agent 必须：

```text
一次只做一个 milestone
```

或：

```text
一个明确 subtask
```

禁止一次：

```text
实现完整 Android PAIR
```

---

# 111. Agent 开始任务前

必须执行：

```text
git status --short

git branch --show-current

git rev-parse HEAD
```

然后读取：

```text
relevant Go source

relevant service README/spec

current Android source

current build files
```

然后输出：

```text
Current state

Files to modify

Why

Acceptance criteria
```

才开始写代码。

---

# 112. Agent 修改后

必须执行：

```text
format

compile

unit tests

relevant Go tests

Android tests

git diff review
```

输出：

```text
Changed files

Behavior changed

Tests run

Tests passed

Known limitations

Next task
```

---

# 113. Agent 禁止事项

绝对禁止自动：

```text
upgrade compileSdk

upgrade targetSdk

upgrade AGP

bulk-upgrade AndroidX

modify protocol without RFC

open plaintext PAIR proxy to LAN

rewrite mDNS in Kotlin

rewrite scheduler

rewrite pairing

launch workers directly from Android

merge stdout/stderr

parse logs as readiness

hardcode /data/app

hardcode proxy ports

exec from filesDir

Thread.sleep to fix races

swallow exceptions

disable SELinux

invent fake telemetry

make MNN pretend to be Ollama

make MNN pretend to be LM Studio

delete tests to get green
```

---

# 114. Upstream Delta

M1-M6 目标：

```text
尽量不修改 services/
```

M9 才允许较系统地修改 PAIR Core。

所有 upstream 变化记录到：

```text
android/docs/upstream-delta.md
```

每条：

```text
file

reason

Android requirement

desktop impact

wire impact

tests
```

---

# 115. Wire Contract 修改规则

如果 Agent 认为需要改：

```text
JSON-RPC method

payload

notification
```

不得直接编码。

先写 RFC：

```text
problem

existing behavior

proposed contract

producer

broker relay

desktop impact

Android impact

TUI impact

compatibility

test plan
```

批准后再改。

---

# 116. 测试层级

## Go Unit Tests

修改任何 service：

```bash
go test ./...
```

从该 module 目录运行。

---

## Cross-process Tests

涉及：

```text
broker relay
RPC
worker interaction
cluster
proxy
```

运行：

```text
services/tests
```

相关测试。

---

## Android Unit Tests

至少覆盖：

```text
JSON-RPC parser

pending request map

timeout

notification routing

runtime state machine

restart policy

DTO mapping

generation race logic
```

---

## Android Fake Broker Integration Tests

模拟：

```text
app:ready

ping response

notifications

RPC error

malformed frame

process exit

timeout

late response

out-of-order notification
```

---

## Real Device Tests

ARM64 真机：

```text
broker
scanner
cluster
proxy
MNN
```

---

## Multi-device Tests

至少：

```text
Android
+
Windows PC
```

未来：

```text
Android
+
Linux
+
macOS
```

---

# 117. M0-M14 总路线

```text
M0 Android Baseline                    DONE
        ↓
M1 Native Go Executable Packaging      NOW
        ↓
M2 Broker + Scanner
        ↓
M3 Foreground Runtime
        ↓
M4 Android ↔ PC Discovery
        ↓
M5 Cluster Pairing
        ↓
M6 Android Router Node
        ↓
=========== MVP-1 ===========
        ↓
M7 MNN Runtime
        ↓
M8 MNN Local HTTP Engine
        ↓
M9 PAIR engine=mnn
        ↓
M10 PC → Android Compute
        ↓
=========== MVP-2 ===========
        ↓
M11 Model Hub
        ↓
M12 Unified API + Auto Model
        ↓
M13 External LAN Gateway
        ↓
M14 Reliability / Release
```

---

# 118. M0 — Android Baseline

状态：

```text
DONE
```

验收：

```text
[✓] Android project builds

[✓] Compose launches

[✓] API 36 compatible dependencies

[✓] ARM64 target selected
```

---

# 119. M1 — Native Go Executable Packaging

当前任务。

施工：

```text
1. 停止使用 assets 作为 executable runtime

2. 创建 generated native staging directory

3. 构建 nvpair-node-settings Android/arm64

4. stage:
   libnvpair_node_settings.so

5. Gradle package

6. 安装 APK

7. NativeBinaryRegistry 找到 binary

8. ProcessBuilder 执行

9. stdout/stderr 分离

10. graceful stop

11. smoke test

12. assembleDebug
```

验收全部通过才进入 M2。

---

# 120. M2 — Broker + Scanner

施工：

```text
1. compile nvpair-ui-broker

2. compile nvpair-node-scanner

3. package both

4. build PairProcess

5. build JsonRpcClient

6. start broker with --scanner-path

7. parse app:ready

8. ping

9. report version

10. detect crash
```

---

# 121. M3 — Foreground Runtime

施工：

```text
1. PairRuntimeService

2. notification channel

3. connectedDevice FGS

4. desiredRunning

5. runtime state machine

6. restart policy

7. MulticastLock

8. lifecycle restore

9. shutdown
```

---

# 122. M4 — Discovery

施工：

```text
1. discovery subscribe

2. initial snapshot

3. race handling

4. PairRepository node state

5. Nodes UI minimal

6. same-LAN real device test
```

---

# 123. M5 — Cluster

施工：

```text
1. package cluster-manager

2. package node-settings

3. cluster UI

4. invite flow

5. response flow

6. persistent trust

7. remove/leave
```

---

# 124. M6 — Router

施工：

```text
1. package nvpair-proxy

2. package scheduler

3. package workload-manager

4. package errors

5. enable Ollama facade

6. enable LM Studio facade

7. endpoint status

8. remote models

9. Android → PC inference

10. streaming
```

M6 完成 = MVP-1。

---

# 125. M7 — MNN Runtime

施工：

```text
1. integrate MNN JNI

2. CPU backend

3. model load

4. generation

5. streaming

6. cancellation

7. unload

8. backend abstraction

9. optional OpenCL
```

---

# 126. M8 — MNN HTTP

施工：

```text
1. loopback server

2. health

3. models

4. chat completions

5. SSE/chunk streaming

6. cancellation

7. internal control

8. lifecycle
```

---

# 127. M9 — MNN PAIR Engine

施工：

```text
1. shared engine descriptor

2. discovery service key

3. proxy profile

4. engine-manager manifest

5. control adapter

6. broker support

7. scheduler compatibility

8. tests

9. PC build

10. Android build
```

---

# 128. M10 — PC Uses Phone Compute

施工：

```text
1. PC fork recognizes mnn

2. Android advertises mnn

3. Android advertises model

4. PC sees model

5. PC sends request

6. PC proxy picks Android

7. Android MNN executes

8. stream returns
```

M10 完成 = MVP-2。

---

# 129. M11 — Model Hub

施工：

```text
1. model catalog domain

2. ModelScope adapter

3. HuggingFace adapter

4. local import

5. download manager

6. resume

7. verify

8. install

9. MNN deployment

10. model UI
```

---

# 130. M12 — Unified API

施工：

```text
1. gateway process/service

2. OpenAI-compatible endpoint

3. auth

4. explicit model routing

5. engine resolution

6. model aliases

7. auto-fast

8. auto-balanced

9. auto-best
```

---

# 131. M13 — LAN Gateway

施工：

```text
1. disabled-by-default setting

2. external bind

3. API key generation

4. Android Keystore

5. auth middleware

6. rate limit

7. selected network

8. gateway UI

9. external client test
```

---

# 132. M14 — Reliability / Release

施工：

```text
1. soak tests

2. page-size tests

3. battery tests

4. memory tests

5. Wi-Fi recovery

6. upgrade path

7. release signing

8. PC installer compatibility

9. Android release APK/AAB

10. user documentation
```

---

# 133. MVP-1 最终测试矩阵

PC：

```text
Windows
PAIR source
Ollama
Qwen
```

Android：

```text
PAIR Android
```

测试：

```text
Android starts runtime

Android discovers PC

PC discovers Android where applicable

Pairing works

Trust persists

Android sees PC models

Android endpoint ready

Android calls OpenAI API

PAIR routes to PC

PC Ollama runs

stream comes back
```

---

# 134. MVP-2 最终测试矩阵

PC：

```text
PAIR fork with MNN engine identity
No local MNN required
```

Android：

```text
PAIR fork
MNN
Qwen
```

测试：

```text
PC discovers Android

PC sees engine=mnn

PC sees Android model

PC calls local MNN facade

PAIR routes to Android

Android MNN generates

tokens stream to PC
```

---

# 135. 最终产品方向

本项目最终不是：

```text
Android LLM App
```

也不是：

```text
Mobile Ollama clone
```

而是：

```text
Personal AI Router
       ↓
Personal Compute Network
```

用户不需要关心：

```text
这个模型跑在哪台设备
```

用户只需要：

```text
URL
KEY
MODEL
```

PAIR 网络负责：

```text
discover
trust
model availability
routing
scheduling
failover
```

而不同节点提供：

```text
PC GPU
Phone NPU/GPU/CPU
AI Box
NAS
未来机器人
```

---

# 136. 当前唯一下一步

当前不要：

```text
继续做 UI

开始 MNN

开始 Model Hub

开始 Unified API

开始 LAN Gateway
```

当前唯一任务：

```text
M1 — Native Go Executable Packaging
```

目标：

```text
nvpair-node-settings
        ↓
Android ARM64 PIE ELF
        ↓
native APK packaging
        ↓
applicationInfo.nativeLibraryDir
        ↓
ProcessBuilder
        ↓
真机成功执行
```

M1 成功后：

```text
M2
=
nvpair-ui-broker
+
nvpair-node-scanner
+
app:ready
+
ping
```

这是整个项目真正开始运行 PAIR Android Runtime 的第一个核心节点。

---

# 137. 给 AI Agent 的首条施工 Prompt

可直接将以下内容交给编程 Agent：

```text
Read android/docs/PAIR_ANDROID_IMPLEMENTATION_PLAN.md completely before modifying code.

We are currently implementing M1 only.

Do not implement M2 or later milestones.

Goal:
Prove that an Android ARM64 Go executable can be packaged as native code in the APK,
resolved via applicationInfo.nativeLibraryDir, launched with ProcessBuilder,
read through separate stdout/stderr streams, and stopped cleanly.

Use nvpair-node-settings as the smoke-test binary.

Before changing code:
1. Run git status --short.
2. Run git branch --show-current.
3. Run git rev-parse HEAD.
4. Inspect the current Android Gradle files.
5. Inspect the nvpair-node-settings Go module.
6. Explain the exact files you intend to modify.
7. Define acceptance tests.

Constraints:
- Do not execute binaries from filesDir.
- Do not use assets as the final executable location.
- Do not merge stdout and stderr.
- Do not upgrade compileSdk, targetSdk, AGP, Kotlin or AndroidX unless explicitly requested.
- Do not modify PAIR wire protocols.
- Do not implement Broker/Scanner yet.
- Do not add Hilt or Room.
- arm64-v8a only.

After implementation:
1. Build the Go binary.
2. Verify ELF/AArch64.
3. Build APK.
4. Install on real ARM64 Android device.
5. Resolve path through nativeLibraryDir.
6. Launch it.
7. Capture stdout and stderr separately.
8. Shut it down cleanly.
9. Run assembleDebug.
10. Show git diff and summarize changed files, tests, and remaining risks.
```

---

# 138. References / Upstream Files To Read Before Core Changes

在后续修改 PAIR Core 前，Agent 至少要检查当前版本的：

```text
README.md

AGENTS.md

docs/architecture.mdx

docs/developing.mdx

services/readme.md

services/nvpair-ui-broker/README.md

services/nvpair-proxy/README.md

services/nvpair-engine-manager/MANIFEST.md

services/shared/engines/

services/shared/noderec/

services/tests/
```

源代码优先于本文。

---

# 139. 架构冻结结论

最终开发顺序冻结为：

```text
Android Host
  ↓
PAIR Runtime
  ↓
PAIR Router Node
  ↓
MNN Runtime
  ↓
MNN PAIR Engine
  ↓
Android Compute Node
  ↓
Model Hub
  ↓
Unified API
  ↓
LAN Gateway
  ↓
Release
```

除非遇到明确的平台不可行性，否则不要改变这个顺序。
