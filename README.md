# vivo主题增强

面向原版 **vivo主题 24.2.8.0** 的 LSPosed 模块，从 [Modded-APKs](https://github.com/Escosis/Modded-APKs) 迁移。支持本地导入、原生预览与 SAF 导出，以及受限的缺失授权材料兼容。本仓库仅提供模块源码。

| 项目 | 值 |
| --- | --- |
| 应用名 | vivo主题增强 |
| 模块包名 / Java 命名空间 | `io.github.escosis.vivothemehelper` |
| 模块版本 | `24.2.8.0-r1` / `24028001` |
| LSPosed 作用域 | `com.bbk.theme`，只勾选 vivo主题 |
| 宿主版本 | `24.2.8.0` / `100024280`，精确绑定 7 个 DEX |
| 绑定的原版 APK SHA-256 | `08783456dc1da09af021bc6bb14fcb90d8a499e13e6e56ddb59b40c10a65918f` |
| 环境 | OriginOS、Root、支持 legacy Xposed API 82 的 LSPosed；模块 `minSdk 28` / `targetSdk 36` |

[发布与使用说明](docs/RELEASE.md) 是 Release 与模块启动页共用的 Markdown，构建时直接作为 `assets/RELEASE.md` 打包。启动页采用 Miuix 风格的原生卡片，优先展示「必看」，适配系统字号和窄屏，随系统切换浅色、深色。HTTPS 链接只在点击时交给浏览器。「打开 vivo主题」使用宿主的系统启动入口；「检查宿主」重新计算已安装宿主的 DEX 哈希，不判断 LSPosed 是否正在运行；「访问项目仓库」在浏览器中打开 [vivo-theme-helper](https://github.com/Escosis/vivo-theme-helper)。

## 构建

1. 用支持 **Android Gradle Plugin 9.2.1** 的 Android Studio 打开本仓库根目录。Gradle JDK 推荐 21，SDK Manager 中安装 Android SDK Platform 36；Wrapper 固定为 Gradle 9.4.1。首次同步允许解析 Google Maven 和 Maven Central 的构建依赖；无须提供宿主安装包、反编译文件或本地签名配置。
2. Gradle Sync 后运行 `:app:assembleRelease`。Release 使用工程默认的未签名配置。若需装到测试设备，请在 Android Studio 用自己的签名生成；不要提交签名文件。

```powershell
.\gradlew.bat :app:assembleRelease
```

Linux / macOS 可用 `sh ./gradlew :app:assembleRelease`。不必安装额外的 Gradle，不要把 `--offline` 用在依赖尚未缓存的首次构建上。[Xposed API 82](app/libs/xposed-api-82.jar) 仅用于 `compileOnly`；运行时由 LSPosed 提供。见[来源与 SHA-256](app/libs/xposed-api-82.provenance.json)。

## 导入、导出与保存位置

- 模块从系统文件选择器接收文件，先在宿主私有缓存中准备，验证后再使用当前用户的原生保存根及内容提供器入库。重复文件不会覆盖；解析或入库失败不会报成功。仅请求所需的 URI 授权，没有全盘存储权限。
- 支持共 12 类 `.itz`。也支持静态图像 `png/jpg/jpeg/webp/heic/heif`、动态壁纸 `mp4`、字体 `ttf`；取决于设备真实解码或解析能力。视频预览采样原文件，字体预览使用实际导入字体。APK 类型动态壁纸、AiPet、AI 字体与新版互动资源不是缺 key 兼容目标。
- 普通字体应用时，宿主按显示名拼出 `显示名.ttf`，解包时不保留目录。直接导入 TTF 时，生成包的中英文显示名与正文 `fonts/显示名.ttf` 使用同一个标题；导入普通字体 `.itz` 时，使用原生解析后的显示名核对唯一、非空、大小写一致的文件名。模块不改写字体包正文、不改已入库资源或原生应用流程。
- 预览页「导出」只处理受支持分类、位于当前保存根的单个文件，用系统文件选择器输出，不发布绕过宿主目录权限的文件提供器。不保证文件管理器能免 Root 查看私有目录。
- 真实目录由宿主 `com.bbk.theme.utils.dc#getResSavePath(int): String` 决定。下表为其使用的 `getDownloadSubDir(int,String)` 空 subtype 分支：基底为当前内部卷，或宿主选择的私有 `DATA_THEME_PATH`（常见 `/data/bbkcore/theme/`，不同用户可变）；之后是 `.dwd/c/o/m/b/b/k/t/h/e/m/e/` 和下列子目录。非 vivo 设备及其他 subtype 另有宿主分支，这不是建议手工写入绝对路径。

| 分类 ID | 资源 | 子目录 |
| ---: | --- | --- |
| 1 | 主题 | `T/` |
| 16 | 妙玩组件 | `Widget/` |
| 2 | 动态壁纸（默认/MP4） | `L/` |
| 9 | 静态壁纸 | `W/` |
| 13 | 行为壁纸 | `B/` |
| 4 | 字体 | `F/` |
| 18 | 图标 | `I/` |
| 5 | 锁屏样式 | `U/` |
| 20 | 控制中心 | `C2/` |
| 7 | 熄屏样式 | `C/` |
| 14 | 视频铃声 | `V/` |
| 12 | 输入法皮肤 | `I2/` |

## 完整 Hook 清单（30 个目标）

签名为绑定宿主的实际重载。`String` 是 `java.lang.String`，`Context`/`Intent` 是 `android.content` 类型，`Bundle` 是 `android.os.Bundle`，`View` 是 `android.view.View`，`Fragment` 是 `android.app.Fragment`。**Before** 表示在原方法前，**After** 表示原方法返回后；未命中所列条件则保留原行为。引导只在 `com.bbk.theme` 内注册；除明确标注的恢复保护外，业务 Hook 均在宿主版本、DEX 与必要 ABI 校验通过后才安装。主进程限制见 UI 表。

### 引导和自有会话恢复（2）

| 目标签名 | 时机、条件与效果 |
| --- | --- |
| `android.app.Application#attach(Context): void` | **Before**。宿主 Context 包名必须为 `com.bbk.theme`，每个进程的初始化只尝试一次。先核验版本名、版本代码及 7 个 DEX 的名称、大小、CRC、SHA-256，再核验所需 ABI 并安装功能；拒绝时仅提示和记日志。只有同安装路径与文件/ZIP 目录标识可复用缓存。 |
| `android.app.Fragment#instantiate(Context,String,Bundle): Fragment` | **Static / Before**。在校验之前注册；仅匹配宿主 Context 和本模块的 `ImportFragment`/`AbandonedSessionFragment` 全名。UI 路由就绪则恢复本模块导入 Fragment，否则用退出占位 Fragment 结束旧会话；传递原 Bundle，不接管宿主 Fragment，不重跑旧操作。 |

### 客户端状态、开屏、升级与整主题（9）

| 目标签名 | 时机、条件与效果 |
| --- | --- |
| `com.bbk.theme.task.MemberInformationQuery$MemberData#isActivated(): boolean` | **Before**。支持宿主内返回 `true`；仅客户端激活判断。 |
| `com.bbk.theme.task.MemberInformationQuery$MemberData#isValid(): boolean` | **Before**。支持宿主内返回 `true`；仅客户端数据有效性判断。 |
| `com.bbk.theme.pay.payment.f#themeHasPayed(Context,String,int): boolean` | **Static / Before**。返回 `true`，改变此客户端购买判断；不修改账户或服务端订单。 |
| `com.bbk.theme.rich.payment.entry.GetAuthorizeEntry#authorizeSuccess(): boolean` | **Before**。返回 `true`，不生成服务端授权内容或有效密钥。其他授权对象的同名方法不拦截。 |
| `com.bbk.theme.utils.ThemeUtils#isTryuseRes(String): boolean` | **Static / Before**。返回 `false`，跳过这个试用标记判断，不重写或清除既有文件。 |
| `c5.a#showSplashIfNeed(): boolean` | **Before**。向常规开屏管理路径返回 `true`，避免继续普通开屏检查/后备广告调用。不更改独立开屏服务。 |
| `c5.a#showVivoAdView(com.bbk.theme.splash.SplashInfo,boolean): void` | **Before**。跳过常规展示方法；不覆盖其他推广通道。 |
| `com.vivo.upgradelibrary.common.upgraderequest.app.c#request(com.vivo.upgradelibrary.upmode.appdialog.AppUpdateInfo): com.vivo.upgradelibrary.upmode.appdialog.AppUpdateInfo` | **Before**。不执行此应用内升级请求体。优先使用传入信息，否则从原生工厂或具体 `NormalAppUpdateInfo` 创建；返回 `stat=200`、`needUpdate=false`、`willShowDialog=false`，构造失败则记日志并返回 `null`。不拦截系统或应用商店升级。 |
| `com.bbk.theme.apply.ApplyThemeUtils#isWholeTheme(com.bbk.theme.common.ThemeItem): boolean` | **Static / After**。原方法无异常、未判为整主题；仅 `category=1`、已下载、样式为空或 `whole`、本地归档含非空非目录 `vivo` 条目时补判 `true`。不改其他样式、无参或单 `boolean` 重载；不代替资源应用。 |

### 原生旧下载链（3）

只在下节全部解析 Hook 安装成功后启用。下载由宿主执行。新版原生传入类别与 AIGC style/source 两个整数，模块不改变它们；不拦截独立资源平台下载，也不增加下载、重命名、复制、解析或入库 Hook。

| 目标签名 | 时机、条件与效果 |
| --- | --- |
| `com.bbk.theme.utils.i2#d(int,int,int,Context,de.a,de.b): void` | **Static / Before + After**。建立同线程、可嵌套隔离的完成作用域，仅处理受支持类型、原生成功、下载 ID 正数、状态 2xx、未删除且路径为完整绝对 `.itz` 的调用，校验宿主 Context、资源 ID 与元数据。结束时撤销票据和作用域，不改原生完成结果。 |
| `com.bbk.theme.rich.payment.utils.j0#getAuthorizeJson(Context,String,int,int): String` | **Static / After**。仅在同一个 `addKeyToZip` 作用域内，Context、包 ID、类型及使用场景 `2` 一致时记录调用次数和原结果是否确为 `null`/空字符串。异常不算空；不改返回值，不调用网络或 Getter。 |
| `com.bbk.theme.rich.payment.utils.j0#addKeyToZip(Context,String,int,String,int): boolean` | **Static / Before + After**。只认同一完成作用域的唯一一次调用，匹配 Context/路径/类别/包 ID/场景 `2`。需为非海外、文件在当前原生下载或保存根下、完整且稳定，同原生基础解析和下载 ID 对应的数据库条目匹配包 ID/资源 ID/类型，并检查目的地、已有文件身份及文件指纹。原返回 `false` 且唯一一次 Getter 确认为空时才放行一次并发解析票据；原返回 `true` 且没有 Getter 时，仅实际 ZIP 内的零字节 `key` 能取得票据，保留原 `true`。拒绝非空 key、不完整文件、异常、歧义和其他失败；不替换下载数据或最终结果。 |

### 缺 key 的原生解析兼容（5）

限定国内路径和支持的 `.itz`，不接受真实非空 key。只在本地 `F,T,F,F,F` 旗标（模块的单次缓存许可或当前用户的原生保存根），或已完成旧下载 `F,F,F,F,T` 旗标（同线程一次性解析票据、目标在保存根）启用。旗标按下表七参数签名中最后五项的次序。

| 目标签名 | 时机、条件与效果 |
| --- | --- |
| `com.bbk.theme.utils.lg#parseResItzFile(String,int,boolean,boolean,boolean,boolean,boolean): com.bbk.theme.common.ThemeItem` | **Before + After**。隔离同次路径、类别与旗标的作用域；原解析仍执行一次。仅当原解析无异常、返回同一个已观测模型且文件和票据仍匹配，给原先与当前样式都为空、确有整主题核心的类别 1 结果补 `whole`。不替换 `null`、异常或解析结果；退出清理作用域。五/六参数包装重载不 Hook。 |
| `com.bbk.theme.utils.lg#k(String,int,boolean,boolean): com.bbk.theme.common.ThemeItem` | **Static / After**。同一解析路径/类别、两个布尔均为 `false`，仅从无异常的首次结果读取原生基础模型；需 ID 非空、类别匹配、非 AiPet/AI 字体，下载票据的两个 ID 也须匹配。只观察，不修改模型或返回。 |
| `com.bbk.theme.utils.lg#d(String,String,String): String` | **Static / After**。同路径、同次调用的 `description.xml` 摘要；记录原生有效十六进制 MD5（算法参数为空）或图标/控制中心的 SHA-256。重复、异常或不匹配不启用兼容；不改变摘要。 |
| `com.bbk.theme.utils.lg#g(int,String,boolean): String` | **Static / After**。同类别/路径且第三参 `false`，记录首次原生核心摘要（按类别 MD5/SHA-256 或宿主允许的空值）；不改变摘要。 |
| `com.bbk.theme.utils.lg#getRuleEntry(String): com.bbk.theme.rich.payment.entry.RuleEntry` | **Static / After**。仅同路径第一次无异常且原结果为 `null`，并已获得基础模型与两项摘要；核验 ZIP 路径、内容、实际 key 缺失/零字节、文件稳定性，以及复算摘要与宿主实算结果。下载还要匹配完整文件 SHA-256 的票据。此时只补一个默认空摘要的本地规则容器；其余字段照抄原生基础模型（包括 `null`），不生成 key 或加密授权。由原生解析决定后续结果；本地一致性检查不等于验证资源的服务端授权。 |

### 导入入口、结果路由和预览导出（11）

仅宿主主进程；确认模块自有 Fragment 恢复及结果路由能安装后才给 UI 增加入口。缺 key 兼容不可用不会禁用 UI，但相应归档仍受原生解析限制。

| 目标签名 | 时机、条件与效果 |
| --- | --- |
| `com.bbk.theme.Theme#onActivityResult(int,int,Intent): void` | **Before**。只消费模块自有请求号中、由该 Activity 的自有等待片段接收，或短时内已处理的重复结果；防止宿主接管返回并退出。其他请求不变。 |
| `android.app.Activity#onDestroy(): void` | **Before**。仅清理由本模块在该 Activity 持有的顶层选择窗口；尚未交接才释放会话，不处理其他窗口。 |
| `com.bbk.theme.mine.widget.LocalDownloadLayout#a(): java.util.ArrayList` | **After**。原方法正常返回列表、且尚无模块条目时在末尾追加导入 DTO，保留原有列表、适配器和离线列数。 |
| `com.bbk.theme.mine.widget.LocalDownloadLayout#itemOnClick(com.bbk.theme.common.MyLocalAndDownload): void` | **Before**。仅名称「本地导入」且 `resType=[-1]` 的 DTO 消费点击，打开原生样式的类型选择；其他项目交宿主。 |
| `com.bbk.theme.mine.widget.LocalDownloadHorScrollLayout#itemOnClick(com.bbk.theme.common.MyLocalAndDownload): void` | **Before**。在线入口使用同一哨兵检查与类型选择，保留其他点击。 |
| `com.bbk.theme.mine.widget.LocalDownloadHorScrollLayout#isCanScroll(): boolean` | **Before**。仅成功注入且带模块网格标记的实例返回 `false`，关闭该区横向翻页。 |
| `com.bbk.theme.mine.widget.LocalDownloadHorScrollLayout#c(String,boolean): void` | **After**。原方法正常完成且存在模块网格时，调用同一个原生红点助手补管第三行及后续行。 |
| `com.bbk.theme.mine.widget.LocalDownloadHorScrollLayout#onFinishInflate(): void` | **After**。原方法正常、原生资源/行/列表一致时在 UI 线程注入，导入放最后，每行至多四项；幂等，不替换宿主原有条目的监听器。 |
| `com.bbk.theme.mine.widget.LocalDownloadHorScrollLayout#onResume(boolean): void` | **After**。原方法正常完成时只对已标记网格重新计算布局，无重复注入。 |
| `com.bbk.theme.fragment.LocalFragment#onViewCreated(View,Bundle): void` | **After**。原方法正常、宿主 `W0` 实例存在时做幂等补注入。 |
| `com.bbk.theme.ImmersionResBasePreview#initView(): void` | **After**。原方法正常、原生分享容器存在且未加入过时显示导出（24dp 图标、11sp 白字）。点击时读取当前已保存资源，仅把支持类别、位于该类别原生保存根下的可读单文件交给 SAF。 |

`dc` 路径计算、`ResDbUtils` 查询和入库、事件总线、`x4.e` 命名助手、`u7.a` 字体容器解析、`h9.f` 原生监听器、`i5.a` 服务查找、对话框、Toast、Fragment 生命周期 override、普通 View listener 都是调用、反射或 Android 回调，**不是额外 Hook**。失败保留原生结果，不运行模块重试。

## 反馈

版本匹配不代表 ROM、权限或引擎一致；注入、资源应用与 ROM 兼容性以设备实际表现为准。反馈请附 LSPosed 的 `VivoTheme24` 日志、宿主版本和设备信息，并删去账号、授权材料及资源链接。
