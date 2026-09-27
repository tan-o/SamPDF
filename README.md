# SamReader

## Native PDF text extraction

The arm64 Rust/JNI library extracts the PDF text layer and exact glyph coordinates as an independent evidence channel. Rebuild it on Windows with:

```powershell
.\native-layout\build-android.ps1
```

The script targets the app's API 26 baseline and NDK `27.1.12297006`, explicitly links every Android ELF load segment at 16 KB, then copies the release `.so` into the Android `jniLibs` source set.

SamReader 是面向 Samsung 平板与 S Pen 的本地优先论文 PDF 阅读器。

当前产品主线只有一条：

`导入 PDF → 自动本地建立可点击语义层 → 一键 AI 校正并翻译全文 → 点句查看缓存译文 → PDF 墨迹或句子画板`

## 平台约束

- Android 8.0（API 26）及以上，按 Android 16（API 36）行为构建；解析主链不依赖 Android 15 专属 PDF 内容 API。
- APK 只打包现代 Samsung 平板使用的 `arm64-v8a` 架构。
- PDF 页面使用系统 `PdfRenderer` 渲染；文字坐标由 Rust/docling-pdf 与视觉 OCR 两条证据通道提供。
- 所有页面先由 APK 内的完整 FP32 PP-DocLayoutV3 划分 25 类区域。模型同时输出实例掩膜与逻辑阅读顺序，后处理与 PaddleX 为 PaddleOCR-VL 发布的流程一致（`layout_nms` 同类 0.6 / 异类 0.98、整页图片过滤、`large` 类包含框合并、按阅读顺序排序）。PDF 原生文字和 ML Kit OCR 都按字形归属区域（MinerU 的 span→block 模式）：每个字形归属于掩膜覆盖其中心的最高优先级区域，跨栏或末尾带公式编号的源行会被拆开，不用手写双栏排序。完整 FP32 与旧的六矩阵部分 INT8 权重在三页论文回归中检出数和阅读顺序一致，但修复了最小行内公式 mask 的量化偏差，所以精度主线不再打包部分 INT8 模型。
- 正文、摘要、目录、参考文献、题注、标题、作者和页眉页脚是互不混合的语义流。正文、摘要和参考文献跨栏、跨页连续；图片、表格、题注、脚注和页眉页脚是浮动体，不打断正文流；章节标题结束所有打开的流。
- 断句使用 APK 内 14.8 MB 的 WtP BERT mini ONNX 与官方英文 `ersatz` 适配器，按 `wtpsplit` 的 `split` 语义对整条文字流一次推理，再把每个句子映射回字形坐标；不做正则预切分、缩写表或启发式兜底。公式是原子单元：模型读到 `variable`/`equation` 加公式自身的末尾标点，句界只能落在整个公式之后，用户原文仍保存完整 LaTeX。每页结束时每条流只保留最后一句等待下一页，跨页句子因此由同一次推理决定。标题整体保留；不含字母的片段（`•`、`3)`、`(4)` 这类列表标记）并入后一句。
- 题注以独立 `CAPTION` 参与点击；公式走 PDF/Rust 字词坐标与 Pix2Text 1.5 视觉检测/识别双通道，页面以 2400 px 渲染，行内/行间公式分别采用紧邻正文与独立公式的裁剪参数。识别结果规范化为语义 `\[LaTeX\]`：保留积分、分数、根号、矩阵、上下标、重音和运算符，删除字体、粗细、括号尺寸与纯间距命令，再原位装配进句子并交给 DeepSeek。
- 翻译只使用用户自己的 DeepSeek API Key；Key 由 Android Keystore 加密，模型与含 `{text}` 的提示词可编辑。
- 本地解析完成后，Reader 顶栏提供“AI 翻译全文”。后台任务先建立论文级主题/术语档案，再按固定句子 ID 分批请求结构化 JSON；只有 ID、字段、公式和改动幅度校验全部通过，才在同一事务中写入校正原文与中文译文。每批完成即保存进度，网络失败后可续跑。
- 设置页可关闭“AI 辅助修复解析原文”，此时全文任务只写译文；开启后可用 5%–100% 滑块设置校正文相对当前原文的最大字符编辑比例。公式和句子 ID 完整性是不可关闭的安全约束。
- 超过设置阈值或触及公式的建议不再让全文任务失败，而是写入持久化审核队列。Reader 直接渲染原 PDF 页面并按句子坐标裁图（跨页显示多张），同时展示当前解析内容、中文译文和 DeepSeek 建议；删除与新增/替换位置标红。建议原文和译文都可人工编辑，再逐条选择确认修改或保留解析内容。
- 文库、逐字句子区域（含页码）、翻译、PDF 墨迹和句子画板独立保存在 Room/SQLite 中，不修改原 PDF。跨栏、跨页句子仍是同一条 Sentence，每个源行片段一个高亮矩形，可在涉及的每一页精确点击和高亮。
- 跨页句子只在用户实际点击的那一页创建一个翻译悬浮窗，不会因同一句拥有多页坐标而重复显示。
- 每页完成后立即以事务写入页面、布局、证据和本页新闭合的句子；Reader 通过 Room Flow 边看边更新，不等待全文解析。中断后从仍有未闭合句子的页重新解析，并回退到没有已提交句子跨越该页的位置。
- 长时间索引由 WorkManager 前台任务执行，熄屏后继续；用户可选择通知是否显示逐页数字。
- v8 使用单个应用级 Room/SQLite：每篇 PDF 以 `documentId` 分区，SHA-256、文件夹、标签、栏/段落/图片/题注布局、各解析通道的原始证据、公式裁图与 LaTeX、文档上下文与费用、墨迹、生词和加密设置都在同一数据库；PDF 二进制仍作为文件保存。
- Samsung 设备使用专门的 S Pen 输入适配器；非 Samsung 设备使用标准 Android stylus 适配器。绘图业务与存储完全共用。
- Room schema v3 通过显式 `1 → 2 → 3` migration 保留已有文库，并增加全文翻译状态及 AI 校正审核队列。

## 阅读视图（手机适配）

- 手机（宽度 < 600dp）默认打开阅读视图，平板默认 PDF 原版；两者随时切换，并保持阅读位置（PDF 当前页 ↔ 阅读视图顶部句子）。
- 阅读视图把解析结果按版面阅读顺序排成可重排的超文本：标题、作者、带底色的摘要、章节标题、正文段落、参考文献列表、原文目录条目和脚注各有排版。段落由句子起始所在的版面块决定；跨栏、跨页续接的段落保持为一段。图、表、题注和脚注是浮动体，放在下一个段落分界处，不会切断句子。
- 图、表和行间公式直接从 PDF 原页按区域高分辨率裁图显示，内容与原文完全一致；公式按论文正文字号换算为 em 宽度，与阅读字号同比例缩放，并保留公式编号。行内公式用 KaTeX 排版。点图表可全屏缩放并保存。
- 顶部栏保持简洁，阅读控件在底栏：目录（章节跳转）、Aa（字号、行距、衬线/无衬线、系统/浅色/护眼/深色主题、是否逐句显示译文、译文字号与颜色，拖动即时预览）、点句/点词、PDF 原版。
- 已有译文逐句显示在原文下方，字号较小且可调；全文翻译进行中译文会就地出现，不重载页面。
- “点句/点词”开关对 PDF 与阅读视图同时生效：点句选中整句；点词只选中手指下的单词（阅读视图用 `Intl.Segmenter` 分词，PDF 用原生文字层的词框或 OCR 字形框），并打开查词/生词本。三星设备使用 Samsung 词典，其他设备由系统列出可处理文字的词典或翻译应用。
- 原文对照：阅读视图中点句打开的面板最上方是该句在 PDF 中的原图（跨页时逐页显示，句子自己的行高亮），下方是可直接修改保存的解析文本，再下方是译文；“在 PDF 中定位”切回原版并选中该句。

## 构建

使用 Android Studio 内置 JDK 21：

```powershell
$env:JAVA_HOME = 'C:\Users\Feysen\AppData\Local\Programs\Android Studio\jbr'
./gradlew.bat assembleDebug
```

Debug APK 输出到：

`app/build/outputs/apk/debug/app-debug.apk`

连接已开启 USB 调试的 Samsung 平板后可安装：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

项目只维护当前数据库 schema 及必要的显式升级迁移，不包含远程配置或翻译降级实现。
