# 旁白 (chat-aside) 全量代码审查报告

审查范围：`app/src/main/java/io/github/tangyuan1129/chataside/**` 全部 Kotlin 源码（约 30 个文件）。
优先级：先报告会导致崩溃 / 卡顿 / 分析变慢的问题。

---

## 一、本次新发现并已修复的严重 Bug

### [严重] AccessibilityNodeInfo 节点池泄漏 —— 会拖慢分析、偶发抓取失败/卡顿
- **文件**
  - `capture/ChatAppAdapter.kt`（9 个 DFS 遍历：`findTitleInActionBar`、`findWeChatTitle`、`WeChatAdapter.extract`、`QQAdapter.extract`、`feishuHasReadState`、`collectFeishuBubbleRects`、`FeishuAdapter.extract`、`XAdapter.extract`、`DouyinAdapter.extract`）
  - `capture/ChatCaptureService.kt`（`findEditable`、`findSendButton`，以及 10 处 `rootInActiveWindow` 调用点）
  - `capture/ocr/ScreenCapture.kt`（`shoot` 的窗口截图路径）
- **根因**：`AccessibilityNodeInfo` 来自框架的**共享节点池**。`getChild()` / `rootInActiveWindow` 取出的节点用完必须 `recycle()` 归还，否则池子只减不增。原代码里：
  1. 所有树遍历从 `getChild()` 拿到子节点后从不回收；
  2. 所有 `rootInActiveWindow` 的返回值从不回收；
  3. `findEditable` / `findSendButton` 返回的 `edit` / `btn` 节点也从不回收。
- **影响**：每次 `onAccessibilityEvent` 抓取一屏会产生几十～几百个节点，长期运行（尤其抖音/飞书这类节点多的 App）会**耗尽节点池**。内存压力下表现为 `getChild()` 返回 null、`IllegalStateException: Already wrapped`，进而抓取可靠性下降、偶发卡顿、分析变慢甚至闪退——这正是此前反馈的「分析慢、页面经常消失」的一类真实根因。
- **修复**
  - 9 个 DFS 遍历统一改为「遍历中 `if (node !== root) node.recycle()` + 循环结束后排空栈」；`feishuHasReadState` 用 `break`+排空，且不回收传入的 `bubble`（由调用方 `collectFeishuBubbleRects` 负责回收）。
  - 所有 `rootInActiveWindow` 调用点用 `try/finally`（或 `?.recycle()`）归还根节点。
  - `readInput` / `trySetText` / `fillInput` / `sendInput` 用完 `edit` / `btn` 后立即 `recycle()`。
  - `ScreenCapture.shoot` 的窗口截图路径用 `try/finally` 归还 `rootInActiveWindow`（含提前 `return` 分支）。
- **验证**：`testDebugUnitTest` 全部通过，编译无错。仅有 `recycle()` 的 “deprecated” 告警——属桩标注误报，该 API 仍是归还节点的唯一正确方式，必须保留。

---

## 二、之前会话已确认并修复的问题（当前代码已为修复态）
- **[严重]** 看门狗从不弹告警：缺少 `POST_NOTIFICATIONS` 运行时授权（`MainActivity` 已补申请）。
- **[严重]** 看门狗启动死锁：改为 app 启动 + 开机自启双触发链路，避免「必须先进 app 才收得到告警」。
- **[中等]** 分析结果闪回「分析当前对话」空闲按钮：缺会话身份 `activeConvKey`，已在 `maybeCapture` 内修复，杜绝跨会话/新消息导致的面板清空。
- **[中等]** 抖音私信气泡不出现：宽度/回退阈值过严，已放宽（单行短气泡也能识别）。

---

## 三、真机调优进展与剩余风险（2026-09-24 更新）

- **[已解决] `DouyinAdapter.extract` 的 `totalRows >= 4` 回退误判**：已抓到抖音正式版私信页真实节点树
  （`uiautomator dump`，2026-09-24），据此重写判定——`msg_et` 输入框为私信页铁证，气泡 =
  消息列表内的无 resource-id 文本，联系人横滑条 / 快捷回复 / 时间戳 / 公告等带 ID 的 chrome 全部排除；
  无 `msg_et` 时退回几何兜底并禁用「≥4 行」弱兜底。已装机，待日常使用复验
- **[中等] 抖音lite 走兜底路径**：`msg_et` 与「气泡无 ID」两个信号只在正式版验证过，lite 未抓包
- **[中等] 视频评论区信号未实测**：收紧是按「评论列表单列、评论文本带 ID」推断的，没有真机 dump 佐证
- **[中等] `feishuHasReadState` 仅在「我的气泡带已读条带」时判定为「me」，未真机验证（代码注释已标注）**
- **[轻微]** 单元测试覆盖 `~114` 条，集中在纯逻辑（JevChat / ModelCatalog / SendButtonRules / DoubleTapGate / PowerHints）；无障碍抓取、截图、`takeScreenshot`、OCR 这类依赖真机与框架的部分无自动化覆盖，建议保留真机回归。

---

## 四、结论
- 代码整体质量不错：网络重试（`HttpJson` 仅对 429/529/传输层退避，4xx 立即失败）、OCR（`MlKitOcr`/`ScreenCapture` bitmap 与 HardwareBuffer 均正确关闭）、知识库（`KbStore` 原子写 + 损坏备份、`KbStore.safeRegex` 防御 `ExceptionInInitializerError`）、看门狗与权限链路均健全。
- 本轮最大的实质性问题是**无障碍节点池泄漏**，已全部修复（子节点 + 根节点 + 返回的 `edit`/`btn` 全回收），编译与单元测试通过。
- 剩余工作：抖音/飞书适配在真机上的信号收紧，需要一份实时节点树（`adb` 抓取）来微调。
