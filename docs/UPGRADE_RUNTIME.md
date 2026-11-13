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
