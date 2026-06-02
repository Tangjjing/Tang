# Tang · 多模型 AI 助手（Android）

> 作者 **Lxl** · 版本 **v1.12** · 原生 Kotlin + Jetpack Compose

一个**只需 API Key、无需登录账号**的安卓 AI 助手。所有模型一律平等 —— 它只是一个把多家大模型聚到一起、并深度联动手机系统的平台。填入你自己的 API Key 即可使用；**密钥用 EncryptedSharedPreferences 加密存储在本机，不经过任何第三方服务器**。

支持的聊天供应商（可增删、可一键从 `/models` 拉取）：**DeepSeek · 智谱(GLM) · Kimi · Claude(经 OpenRouter)**，以及任何 OpenAI 兼容接口。

## ✨ 功能

- 💬 多轮对话 + 流式「打字机」输出，推理模型展示可折叠**思维链**，实时显示用时 / 思考时间 / token 消耗
- 🧠 **模型管理**：两级（供应商 → 型号）选择，底部弹层切换；每个模型可单独填接口地址与 Key；一键拉取供应商支持的全部模型
- 🗂️ 多会话历史（Room 本地存储）
- 🧩 **记忆系统**
  - 手动建条目，按**类别**分组注入（`[个人信息]`/`[编码偏好]`/`[饮食]`…）
  - **自动记忆**：每轮对话后用便宜的小模型判断「这是不是关于你的长期事实」，自动归类记下（去重 / 合并 / 容量按使用近度裁剪），对话里轻提示「🧠 已记住…」
  - Agent 工具 `save_memory` / `read_memory` / `forget_memory`
- 🤖 **Agent 模式（工具调用）**：联网搜索、读写手机文件、运行 JS、剪贴板、分享、打开 App、定位、读写日历（静默直插，无系统弹窗）、设提醒 / 闹钟、查天气等；三档执行模式（全部确认 / 仅副作用确认 / 完全放权）
- 🔍 **web_search**：意图判断 + 中英双语 + 标题重排 + 抓正文；后端支持百度千帆 / 博查 / 秘塔 AI 搜索（这些只用于搜索，不作聊天模型）
- 🔔 **通知助理（实验）**：监听白名单 App 通知 → AI 判断待办 / 到点提醒；可**替我回消息**（含微信无障碍自动发送）；带时间的「命令型」消息可**自动加进系统日历**（可撤销）
- 🌤️ **天气**：`get_weather` 工具 + 后台监控（早间恶劣天气 / 天气突变推送），数据源和风天气 QWeather（可选）/ Open-Meteo（免费回退）
- 📎 **附件解析**：PDF / DOCX / PPTX / XLSX 提取文本；图片在非多模态模型上走本地离线 OCR
- 🎨 极简单色 UI，浅色 / 深色 / 跟随系统

## 🔑 API Key 怎么填

打开 App → 顶部模型名 → 「管理 / 添加供应商」，给对应模型粘贴它自己的 Key（如 DeepSeek 在 [platform.deepseek.com](https://platform.deepseek.com) 申请，形如 `sk-…`）。也可「添加供应商」填一个 OpenAI 兼容接口地址 + Key，自动拉取它支持的全部模型。

## 🛠️ 构建

```
build.bat              # 或： .\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

需要 **JDK 17**（AGP 8.x 仅支持 17）。本仓库在 `gradle.properties` / `gradlew.bat` 里固化了三处本机环境修复：JDK 17 路径、AF_UNIX selector 临时目录修复（否则 Windows 默认 TEMP 会让 NIO selector 自管道失败）、以及阿里云 Maven 镜像（国内网络拉不到 Google Maven）。**在其它机器构建时，请按需修改 `gradle.properties` 里的 `org.gradle.java.home` 与 `gradlew.bat` 里的路径。**

## 📲 安装

把 APK 传到手机点开安装（需允许「未知来源」），或 `adb install -r app-debug.apk`。最低 Android 8.0（API 26），目标 API 35。

## 🧱 技术栈

Kotlin 2.0.21 · Jetpack Compose（Material 3）· MVVM（ViewModel + StateFlow）· OkHttp + SSE · kotlinx.serialization · Room · EncryptedSharedPreferences · WorkManager（后台天气）· AlarmManager（到点提醒）。

---

> 非官方、个人项目，与任何模型厂商无关。所有数据仅存本机。
