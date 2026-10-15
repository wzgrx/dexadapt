# 查询定义（spec）语法

一个 spec 描述一个模块要用到的全部 Hook 点（符号）。可以用 Kotlin DSL 写在模块代码里，也可以写成 YAML / JSON 文件，
两者是同一个模型、可以互相转换（`dexadapt export`）。

## 文件结构

```yaml
dexadapt: 1              # 格式版本
app: com.xingin.xhs      # 宿主包名（可选，给报告看）
symbols:                 # 符号列表，顺序随意，引用在读入时解析
  - id: feedCopy.overlay
    class: { strings: [feedbackOverlay, dsl/negative_feedback_mask_view] }
  - id: feedCopy.showOverlay
    method:
      owner: $feedCopy.overlay
      params: [java.lang.String, int, $feedCopy.feedbackBean, java.lang.String, android.view.ViewGroup, java.lang.String]
      returns: void
  - id: feedCopy.overlayFrame
    field: { owner: $feedCopy.overlay, type: android.widget.FrameLayout, accessedIn: [$feedCopy.showOverlay] }
```

每个符号有 `id`（全局唯一，习惯写成 `组.名字`）和 `class` / `method` / `field` 三者之一。解析时要求**唯一命中**：
零个或多个候选都算失败，不猜。

## 类型写法

| 写法 | 含义 |
|---|---|
| `java.lang.String`、`int`、`a.b.C[]` | JVM 类型名 |
| `$id` | 另一个类符号解析出的类 |
| `super($id)` | 那个类的直接父类（成员声明在混淆的父类上时用） |
| `*` | 任意类型（只用在方法参数列表里，给混淆的参数占位） |

方法、字段引用（`calls`、`usesFields`、`accessedIn` 等）写 `$id`。

## 类（`class`）

| 字段 | 含义 | 稳定程度 |
|---|---|---|
| `name` | 没混淆的类名（四大组件、SDK 类） | 锚 |
| `strings` | 类里的方法用到的字符串（全部要出现） | 锚 |
| `fieldNames` | 保留下来的字段名（Gson / 序列化模型） | 锚 |
| `sourceFile` | dex 里的 `SourceFile` 属性（很多 App 为崩溃堆栈保留） | 锚 |
| `annotations` | 类上注解里要出现的文字 | 锚 |
| `instantiatedIn` | 在这些方法里被 `new` | 关系 |
| `declares` | 声明了这些方法 | 关系 |
| `fieldOf` | 是某个类里某个字段的类型 | 关系 |
| `samePackageAs` | 和另一个类在同一个包（不含子包） | 关系 |
| `packagePrefix` | 在这个包或子包里 | 形状 |
| `superclass`、`interfaces` | 父类、接口 | 形状 |
| `fieldTypes` | 声明了这些类型的字段 | 形状 |
| `methods` | 声明了这些形状的方法（每项同下面的方法形状） | 形状 |
| `interface`、`abstract` | 是否接口、抽象 | 形状 |

## 方法（`method`）

起点（至少一个）：`owner`（所属类）、`strings`、`packagePrefix`、`calledBy`。

| 字段 | 含义 | 稳定程度 |
|---|---|---|
| `owner` | 所属类（类型写法） | — |
| `name` | 没混淆的方法名 | 锚 |
| `strings` | 方法体用到的字符串（全部） | 锚 |
| `numbers` | 方法体用到的数字常量（全部），整数 | 锚 |
| `annotations` | 方法注解里要出现的文字，例如 `retrofit2.http.GET`、`/api/sns/v1/note/feed` | 锚 |
| `calls` / `callsExact` | 调用了这些方法（`$id` / `owner#name`，后者也认 R8 改到子类上的引用） | 关系 |
| `calledBy` | 被这些方法调用 | 关系 |
| `usesFields` | 读写了这些字段；`onlyTheseFields: true` 时只碰这些字段（区分 getter 和 toString） | 关系 |
| `excludes` | 排除这些方法（同形状兄弟里其它的已能单独找到时） | 关系 |
| `params`、`returns`、`static` | 形状 | 形状 |
| `opcodes` | 指令过滤器：这些操作码按顺序出现（中间可以夹别的），smali 写法 | 形状 |

## 字段（`field`）

`owner`（必填）、`name`、`type`、`static`、`accessedIn`（被这些方法读写，区分同类型的多个字段）。

## 按构建钉死与“没有”

```yaml
  - id: x.y
    class: { strings: [...] }
    pins:                       # 只对这些已知构建生效；本机适配（未知版本）时忽略
      CN_9491803: { class: a.b.C }          # 或 { method: a.b.C#m(int,java.lang.String) } / { field: a.b.C#f }
    absentIn: [PLAY_9481803]    # 确认这些构建没有这个功能
```

钉死的位置仍要符合查询里能核对的条件；实在描述不了时才用，优先给查询加稳定特征。

## 契约（`contract`）

查询只保证唯一命中，不保证命中的还是“原来那个意思”。方法符号可以写契约：运行时应该看到的参数、this、返回值和线程。

```yaml
  - id: feedAd.videoParse
    method: { name: invoke, params: [java.lang.Object], returns: java.lang.Object, strings: [...] }
    contract:
      returns: { type: instanceOf, of: java.util.List }
      minCalls: 2
```

规则（`type` 字段区分）：

| 规则 | 写法 | 满足条件 |
|---|---|---|
| 非空 | `{ type: notNull }` | 不是 null |
| 类型 | `{ type: instanceOf, of: java.util.List }` | 是这个类或子类（`of` 写 `$id` 指类符号） |
| 正则 | `{ type: matches, regex: "[0-9a-f]{24}" }` | `toString()` 整个匹配 |
| 范围 | `{ type: range, min: 0.1, max: 8 }` | 数字（char、boolean 按 0/1）在范围内 |

契约的字段：`args`（参数下标 → 规则）、`receiver`（this）、`returns`、`mainThread`（true / false）、`minCalls`（默认 3）。

运行时（`runtime` 的 `ContractMonitor`）：本机适配出的表（没在电脑上验证过）上，带契约的 Hook 点先**观察**——只调用原方法、
检查契约，不跑模块的代码；满足 `minCalls` 次后放开，有一次不满足就当“未适配”。结果按表保存，同一张表只观察一次。
内置表不观察。契约不算进目录指纹，改契约不会让各手机重扫。电脑上 `Contract.problems` 核对契约和每个内置版本的位置对得上。

## 从别的生态导入

`dexadapt import --from revanced|morphe|qauxiliary <源文件.kt>` 把 ReVanced / Morphe 的 fingerprint、QAuxiliary 的
DexKitTarget 转成上面的写法（按源码写法匹配，不编译）：返回值、参数（前缀写法转成 `*`）、字符串、opcodes、访问标志里的
static / 构造方法、custom 里的方法名和类名、Morphe 的 `string` / `literal` / `methodCall` 过滤器。

- ReVanced 的 opcodes 是连续的一段，这里是“按顺序出现”，更宽；
- 类名按后缀匹配、custom 里别的条件、QAuxiliary 的多个“或”字符串写不出来，会列出来；
- 转出来的查询一定要 `dexadapt resolve` 在真实 APK 上验证唯一命中。

反过来 `dexadapt export --spec x.yaml --to revanced` 把方法符号写成 `fingerprint { }`，写不了的条件（调用关系、
所属类是别的符号、数字常量……）写成注释。

## 怎么写得稳

1. 先找锚：数据类 `toString()` 里的 `Xxx(field=`、日志文字、埋点名、接口路径、源文件名。
2. 用关系把没有锚的成员挂到有锚的成员上（“被那个有日志的方法调用的唯一同形状方法”）。
3. 形状只用来在少数候选里挑一个。
4. 看余量报告（`dexadapt matrix`）：只靠一个条件撑着、尤其只靠形状的查询，下个版本最容易坏。
