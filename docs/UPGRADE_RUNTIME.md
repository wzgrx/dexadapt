# 接入宿主升级后的自动适配

在模块进程中创建一个 `UpgradeCoordinator`。`hooks` 的 `AndroidUpgradeEvents` 负责模块启动与宿主包变动通知，
也可从已鉴权的宿主桥接调用 `hostOpened`。其回调只投递持久任务，例如使用方已有的 WorkManager。
周期检查与重试走同一队列；Worker 在后台调用 `coordinator.onEvent`，不要在广播线程读取 APK。
模块自身替换事件和进程重启由使用方的 Receiver、Worker 和模块启动路径负责兜底。
Android 可能延后后台任务；模块没有运行或收不到广播时，由宿主首次打开请求或持久任务补查。

1. 用 `AndroidUpgradeEvents.installedIdentity` 或等价来源获取包名、版本号和完整 DEX 摘要。提供
   实际类加载顺序下的 base 和代码 split；不以文件时间、长度、版本码或广播载荷代替代码身份。
   宿主缺失或读包中途换包时，等待下一次事件；不要使用旧表。
2. `DirectoryAdaptationStore` 指向模块私有目录。可用内置表只接受完整身份一致的条目；本机表还必须匹配目录。
   由应用按自身容量与降级策略清理旧完整表。不要把阶段表写入持久库。重启后重投当前安装请求，未完成扫描从头开始。
3. 扫描回调使用 `DexKitScan.verified`，把已启用功能及其必需符号交给 `StagedAdaptation.scan`。
   只有依据完整 DEX 已验证的构建才传 `verifiedBuild`；未知构建不要沿用旧版本的钉子和缺省声明。
4. 消费发布结果时重新确认活动宿主的完整身份，并使用 `BindingTransaction.applyVerified` 或同等保护。
   部分表仅尝试 `ready` 列表中的热安装功能；不要停用还未扫描到的其它功能，或让它们从部分表动态读取未就绪项。
   运行时表仍需按 `requiresObservation` 接入契约观察。只有实际安装或验证成功的功能可同步返回确认；
   如果使用异步观察，另外记录观察通过时间，不要把提交阶段表当作实际生效。
   `restartRequired` 只在完整表就绪后报告，不在扫描阶段重启宿主。

报告的首个恢复耗时从事件入口起算，到安装回调确认第一个功能为止；未确认时为空。桌面测试用真实 APK 验证
解析位置和队列流程；隔离 ART 可执行原包目标，仍不能替代 LSPosed 注入、系统广播投递和界面行为的端到端验收。


### 安装摘要和宿主回执

`InstalledDexIdentityCache` 只在同一安装快照、且文件系统能可靠区分变更时复用完整摘要。
安装或卸载事件调用 `invalidate(packageName)`；失效不等待磁盘读取，也不会把旧代际的摘要交给调用方。
返回空时等待下一次事件，不使用上一份适配表。

跨进程交付使用 `RecoveryReceipt.publish` 生成令牌；宿主绑定和首次实际效果分别确认。先鉴权宿主桥接，
再用当前安装身份和目录调用 `confirm`。只有 `Advanced` 记录新事件或调度失败重试；`Duplicate` 表示已接受的确认，
不重复触发副作用；`Rejected` 不推进状态。使用 `DirectoryAdaptationStore` 保存完整表时令牌会剥离，
读取旧缓存也不会返回旧令牌；模块重启后复用表时重新发布即可。


### 前台快路径（0.11.0）

已鉴权的宿主请求调用 `ForegroundRecovery.select`：`Available` 返回同包、同版本、完整 DEX 和目录匹配的表；
`NeedsScan` 交给唯一持久任务，`Unavailable` 仅做有界重试。返回的表仍由宿主在绑定前完整校验。
宿主把首次启动的单调时钟起点带到桥接；模块重启后，报告允许观察时间早于本次交付。
`InstalledDexIdentityCache.current(packageName, onRead)` 在实际开始一次完整 DEX 摘要时回调，命中不计数。

### 桥接就绪与摘要代际（0.12.0）

消费方在已经鉴权的通道首次确认成功后调用 `RecoveryOutbox.ready()` 并唤醒一次待发箱。
在途消息仍由 `next/complete` 串行推进，原始单调时间不变。断开后保留有限退避，不重放业务写入。
初始续发也应合并到一个在途请求，桥接就绪取消尚未执行的旧计时器。

`SymbolLinker.updateRecoveryToken(expected, token)` 只接受当前表实例；它复制交付元数据并复用链接缓存，
正在执行的快照仍引用原表。内容变化继续走 `replace`，不能用此接口绕过身份、目录和目标校验。

`InstalledDexIdentityCache(snapshot, directory)` 可选使用应用自己的私有目录。首次读取仍计算所有有代码 APK 的
完整 DEX SHA-256；持久记录绑定系统安装版本/更新时间和全部有序文件的可靠身份。每次命中核对读取前后快照，
文件键或 ctime 不可靠时退回完整读取，记录损坏也完整重读。宿主与模块使用各自目录，不接受对方声称的摘要。

### 可信摘要和阶段通知（0.13.0）

`InstalledDexIdentityCache.withTickets(read, publish)` 的读取端只能绑定模块所属的认证远程文件。
文件名由当前可靠安装快照导出；读取前后均验证。完整票据导入后写自己的私有缓存，无法导入时完整计算。
同快照正在读取时可发布有界租约；租约只合并等待，绝不能据此选表。发布端必须核验完整内容后才提供 SHA-256。
不要从宿主提交的数据、公共文件或非认证网络结果构造这个通道。

`android-runtime` 的 `StageChannel` 将通知和表内容分开：订阅经过现有 Provider 宿主 UID 检查，
通知端再次核对模块 UID，`StageNoticeGate` 核对完整代码身份、目录、服务代际及递增序号。
通知只唤醒取表，`fresh` 和绑定事务仍执行；断连重注册并保留轮询。已有表时可以接收手动全扫的完整交付，
启动前的旧提示或已有完整表时的部分提示不会触发重装。

`ScanTiming` 区分完整摘要、ZIP读解压、SHA更新、锁、缓存/票据、原生初始化、阶段预取以及逐符号包容和独占时间。
逐符号样本在后台汇总持久化；不要在主线程或每次查询打印大段日志。旧版与新版比较从首次核验请求计时，
准备阶段的读取也计入总量。新增 Android IPC 模块只依赖 runtime，不引入任何 Hook 框架。

前台首次核验可在认证远程票据暂缺时通过只读 Provider 请求合并到模块现有读取。请求先核对模块签名、
Provider 所属包和 UID；以宿主自己的 Context/AttributionSource 发起，有界等待且不启动握手页面。
模块只响应实际调用 UID 对应的宿主，并要求请求的快照文件名等于自己当下的可靠快照。返回前仍完整核验内容；
不可用时继续本地完整摘要。这个绑定也避免模块后台读取被降调度而让前台无意义等待租约。
