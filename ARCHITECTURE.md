# Architecture

## 产品逻辑

SamReader 采用成熟阅读器已经验证的模式：文库是入口，Reader 是纵向连续阅读表面，所有全局功能集中在单一顶栏；句子操作卡片锚定在原文上方。PDF 是不可变显示源，语义、翻译和墨迹是可查询的数据层。

## 模块边界

- `ui`：Compose 页面、输入路由和瞬时交互状态。
- `data`：Room schema、DAO 和面向用例的 repository。
- `document`：PDF 渲染、版面/公式 ONNX 推理、原文裁决、区域 OCR、分栏句子重建与后台索引。
- `native-layout`：Rust/JNI arm64 基于 `docling-pdf` 的字词坐标抽取。
- ONNX Runtime：执行 PP-DocLayoutV3，输出 25 类语义区域、实例掩膜与逻辑阅读顺序。
- ML Kit：只在某个语义区域缺少可靠 PDF 原生文字时执行 Latin OCR；其 Line、Element、Symbol 坐标不能覆盖可读的 PDF 字符流。
- Pix2Text MFR 1.5：只接收 PP-DocLayoutV3 已判定为行内/行间公式的裁图并恢复 LaTeX，不再运行第二个全页公式检测器。
- WtP BERT mini：逐字符语义断句；英文论文使用官方 `en/ersatz` 127 维逻辑回归适配器。
- DeepSeek：唯一翻译后端；OkHttp 负责 API 调用，不设置提供商抽象或降级翻译。

## 持久化

- 只使用一个应用级 `samreader-v8.db`，不为每篇 PDF 创建数据库。
- Document 是聚合根；Sentence、上下文档案、翻译用量/费用、PDF 墨迹和句子画板通过 ID 关联。
- `page_evidence` 保留 Rust PDF、布局、OCR、公式检测和公式识别的原始观察；每条记录包含模型 ID、置信度和坐标，公式原始裁图以 PNG BLOB 保存。
- 模型、提示词、画笔样式及加密后的 API Key 也存入 SQLite。Keystore 只保存不可导出的 AES 密钥。
- PDF 原文件保持为应用私有文件，避免把大 BLOB 塞入 SQLite。

## 上下文翻译

导入立即启动本地解析并打开 Reader；AI 不阻塞导入。本地解析完成后，用户可在 Reader 顶栏一键启动付费全文翻译。任务先生成论文级主题/方法/术语档案，再按稳定句子 ID 分批提交原文及批次前后文。DeepSeek 只返回包含 `id`、`corrected_source` 和 `zh_translation` 的 JSON；模型没有数据库操作权。用户可关闭原文校正，或把基于字符编辑距离的允许改动比例设为 5%–100%。阈值内建议随译文事务落库；超阈值或改动公式的建议进入 `ai_correction_reviews`，不阻塞后续批次。Reader 使用 `PdfRenderer` 直接渲染原 PDF 页面并按句子坐标裁图，跨页句子显示多张原图；解析内容、建议原文和译文一并展示，建议原文与译文都允许人工编辑后确认。JSON 的 ID 集合与空值校验仍是不可绕过的整批约束。逐句翻译仍带入档案、相邻句和全文词项相关句，以统一术语。

## 输入与墨迹

- Samsung 硬件走 `SamsungSpenInputAdapter`，读取 S Pen 压力、历史采样、悬停、侧键、笔尾橡皮和掌触取消。
- 非 Samsung 硬件走标准 Android stylus adapter；两者只做事件归一化，共用 `InkSurface`。
- 画布在落笔时禁止父级滚动拦截，抬笔或取消后恢复；画笔关闭时 S Pen 不被画布消费，按导航手势处理。
- 笔画、形状、笔画橡皮和区域橡皮都保存为归一化 SQLite 墨迹，不回写 PDF。

## 导入与索引

1. SAF 选择 PDF。
2. 复制到应用管理的文库目录，避免外部 URI 失效。
3. 立即写入 Document，并进入 Reader 可打开状态。
4. WorkManager 将每页渲染一次；PP-DocLayoutV3 在同一次推理中输出 25 类语义区域、200×200 实例掩膜及逻辑阅读顺序。
5. Rust/docling-pdf 先抽取 PDF 原生行与词坐标。每个语义区域独立选择唯一文字源：内部可读的原生文字直接成为不可被 OCR 否决的 canonical text；只有缺字、无字或乱码区域才接收 ML Kit OCR。两路观察仍分别写入调试证据，但不做字符串投票或混拼。
6. 正文、摘要、目录、参考文献、题注、标题、作者、页眉页脚分别形成带类型的文字流并写入 SQLite；不同类型永不相接。语义 block 采用布局模型的阅读顺序；block 内部严格保留 ML Kit 的 `TextBlock -> Line` 或 Rust/PDF 的来源顺序，禁止再用矩形相交或左边界重排。布局模型把目录列表误标为普通文字时，`CONTENTS` 标题、同栏归属和下一章节边界只负责提升 block 类型；目录按 OCR 行形成独立条目，不进入普通句子流。
7. 题注作为独立且可点击的 `CAPTION`。PP-DocLayoutV3 的所有类别使用当前 PDF 的同一个用户阈值，Pix2Text MFR 只转录已经通过布局阈值的公式框。文字、OCR 和公式分别保留为带来源与坐标的 typed span：实例掩膜/中心点决定唯一 block，公式只消费被其框完整拥有的 PDF 字形 span；部分重叠的可读 PDF 原文保持权威。纯图片 OCR 的同一行可按 Symbol 坐标切成正文/公式/正文，公式只替换自己连续拥有的字符范围并原位插入 LaTeX。显示公式、行内公式和 `formula_number` 的布局标签继续保留到句子组装层；公式编号只作结构属性，不进入原文。最终按来源顺序只装配一次，不检查公式字符、不按 token 数猜测，也不做字符串替换。canonical text、悬浮窗、高亮、上下文和 DeepSeek 请求始终读取同一份数据。
8. 每个同类型文字流先形成正文与显示公式两种 typed atom，再经 WtP BERT mini 的 512 字符重叠窗口推理。用户原文保存规范化语义 `\[LaTeX\]`；字体族、字重、括号尺寸与纯间距命令在入库前删除，积分、分数、根号、矩阵、上下标、重音和运算符保持不变。模型通道把行内公式投影为 `variable`、把显示公式投影为自然语言 `equation`，编号公式携带结构化编号标记，并保留公式末尾真实的句号、逗号、分号或冒号。边界概率只在投影后最后一个有效字符上读取，不能落在尾随空格，也不能删除数学标点。随后应用官方英文 `ersatz` 适配器判断句界；实测 `ud` 会误切 `Fig. 2` 和句中公式，因此不并存第二套适配器。圆括号、方括号、花括号和 `\[LaTeX\]` 保证公式内部不可切断。跨页把上一页流尾与下一页同类型流头重新联合评分，因此 `[Fock (1959), p.`、`173.4.2`、`Fig. 2` 和作者首字母由上下文模型处理。
9. 只有本地布局库完成后才显示“AI 翻译全文”。前台 WorkManager 任务先生成论文上下文，再分批校正与翻译；每批事务落库并更新已完成句数，失败时从最后一个完整批次继续。
10. 每页完成后在一个 SQLite 事务中发布页面、布局、证据和已闭合句子；唯一可能跨页的尾句等下一页后再发布。AI 校正只更新 `correctedText`，保留原识别文本和原页面坐标。
11. WorkManager 以前台 `dataSync` 任务运行长时间端侧解析与全文翻译，通知显示逐页或逐句进度；每批落库后即使进程随后被回收，已完成内容也不需要重算。

## 端侧模型决策

- ML Kit GenAI Prompt API 已支持图片输入，但依赖 AICore/Gemini Nano、只能在应用前台推理，且官方设备列表不包含当前 Galaxy Tab S9（SM-X810），因此不作为当前解析主链。
- Gemma 3n 可经 LiteRT-LM 在 Android 端处理图像，但模型权重大，不塞入 APK。未来只作为用户主动下载的版面增强器，消费已经裁出的图表候选区；基础布局索引不依赖它。
- PP-DocLayoutV3 是唯一布局入口：完整 FP32 模型约 140.1 MB，以 800×800 输入同时完成 25 类检测、像素级实例分割与阅读顺序预测。实测局部 INT8 使小型行内公式掩膜退化，因此当前只打包 FP32，不保留旧量化模型或几何排序兼容层。
- WtP BERT mini ONNX 为 14.8 MB，适配器约 2.7 KB；四窗口小批次、关闭 CPU arena，适合 8 GB 设备后台逐页执行。当前不做 INT8：先保留基准精度，只有真实论文回归集证明断点误差可接受时才以量化版本替换。
- Pix2Text MFR 1.5 是公式转录器：PDF 页面以 2400 px 宽渲染后，PP-DocLayoutV3 的 `inline_formula` 使用窄水平/垂直边距以隔离相邻正文，`display_formula` 使用均匀边距；最多 2 个裁图批处理。识别器只输出语义 LaTeX，不复刻字体。已删除会在普通英文上大量误报的 Pix2Text MFD 全页检测模型。关闭 ONNX CPU arena、串行执行文档索引并在任务结束释放公式会话，以降低 8GB 设备的峰值内存。
- 推理由官方 ONNX Runtime Android API 执行；Rust 只提取 PDF 原生字词坐标，不复制神经网络运行时，也不提供规则布局 fallback。

## 许可证边界

- PP-DocLayoutV3、docling-pdf 与 Pix2Text MFR 分别按其 Apache-2.0/MIT 许可证记录来源和哈希；APK 不再包含 AGPL 的 Pix2Text MFD 权重。

## 不采用的方案

- 不使用仍处于 alpha 的 AndroidX PDF Viewer 作为核心依赖。
- 不使用 AGPL PDF 引擎，避免未来闭源发布的许可证阻碍。
- 不在点击时临时 OCR；所有页面在导入后进入持久索引。
- 不把批注直接写入 PDF；导出属于独立用例，避免每次书写重写整份文件。
- 不保留 ML Kit Translate 或其他翻译 fallback。
- 不打包尚未经过 Android ONNX 等价验证的 PP-FormulaNet 转换权重。官方基准中 `PP-FormulaNet_plus-S` 的英文 BLEU 高于现有轻量公式模型且体积可接受，但 Paddle 到 ONNX 的公开转换仍有结果不一致记录；未经同一公式回归集验证的第三方导出不是成熟依赖。
- 不设置旧 API fallback，也不增加远程配置层；仅为当前受支持的 Room schema 提供显式迁移。
