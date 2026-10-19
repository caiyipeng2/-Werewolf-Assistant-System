# 狼语复盘 · Werewolf Assistant System

原生 Android 狼人杀记录工具：系统音频内录、离线中文转写、截图识别本人座位和身份、板型匹配、可解释身份倾向分析、话术草稿及本地复盘。

## 自动填写本局

1. 在设置中选择实际游戏应用，允许录音、通知、悬浮窗和“使用情况访问”。录音权限是 Android 系统内录所需，应用不会开启麦克风。“使用情况访问”仅用于确认所选游戏在前台。
2. 点击“启动游戏并自动识别”，确认 Android 本次屏幕捕获授权。每次开始捕获仍须系统授权，应用不能静默绕过。
3. 在对局中展示本人身份牌。应用离线识别明确的本人座位、本人身份和已知板型；两次一致结果后固定本人信息。身份牌与座位不必同一张画面出现。
4. 本人位置与当前发言席分别记录。识别结果不会把其他人的发言当作自己的身份，也不会因为后续对局画面改变而修改已固定身份。板型未读到时会继续等待，保留现有配置。
5. 可通过“重新识别”解除自动固定，或“下一局”归档并开始新局。仍支持人工纠错。

截图仅在所选游戏前台时送入本地 OCR，不自动保存、不上传；暂停和停止也会暂停或结束画面识别。应用没有联网权限。对方应用禁录、画面保护、皮肤字体、低清文字或未显示本人身份牌，都可能使自动识别不完整，此时会保留原值并显示状态。

## 其他功能

- 30 种核实配方的预置板型，以及 5–20 人自定义角色组合；来源见 [板型资料](research/boards-sources.md)。
- 按玩家和轮次记录发言，手动标注发言席、事件、存活状态及已知身份。
- 本地规则估计与具体依据；数值不是校准概率，也不能确认他人真实身份。
- 按本人已识别身份生成发言提纲，草稿编辑、复制、悬浮编辑和自动保存。
- 对局归档、TXT/JSON 导出及本地数据管理。

## 构建

Android 10/API 29 起；编译 SDK 35、build-tools 36.0.0、JDK 21、Python 3.10+。本仓库使用 Android SDK 命令行直接构建，不需要 Gradle。当前构建脚本面向 Windows。

```powershell
$env:LANGYU_ANDROID_SDK = 'E:\Android\sdk'
$env:LANGYU_JDK = 'E:\Tools\jdk-21'
python bootstrap.py
python build.py
```

`bootstrap.py` 按 [锁定清单](dependencies.lock.json) 下载官方开源库和模型并验证 SHA-256。只有开发阶段需要网络，模型随 APK 分发。产物为 `outputs/Langyu-1.1.1-universal.apk`，包含 arm64-v8a、armeabi-v7a、x86_64、x86。64 位原生库支持 16KB ELF 对齐。

签名私钥存放于本地 `signing/`，已排除出 Git。首次构建会创建本地签名；要覆盖安装以前由同一开发者生成的 APK，必须保留原私钥。请自行备份，勿提交私钥或生产签名凭据。

## 验证

```powershell
powershell -ExecutionPolicy Bypass -File tests/run-core-tests.ps1
powershell -ExecutionPolicy Bypass -File tests/run-screen-tests.ps1
powershell -ExecutionPolicy Bypass -File tests/run-foreground-tests.ps1
python build-test.py
```

设备测试包位于 `work/verification/Langyu-instrumentation.apk`，仅包含测试代码、合成音频和明确标示为模拟的对局画面，不进入正式 APK。安装后可运行以下独立测试：

```powershell
adb -s <设备序列号> install -r outputs/Langyu-1.1.1-universal.apk
adb -s <设备序列号> install -r work/verification/Langyu-instrumentation.apk
adb -s <设备序列号> shell am instrument -w com.langyu.assistant.tests/com.langyu.assistant.SmokeInstrumentation
adb -s <设备序列号> shell am instrument -w com.langyu.assistant.tests/com.langyu.assistant.OcrInstrumentation
```

设备测试仅证明对应测试画面和系统链路的表现。真实网易对局、各皮肤和设备的适配结果须分别验证，不能由合成测试推断全部兼容。

## 点击“识别本局”没有结果

1.1.1 为每次点击增加即时反馈。暂停、草稿编辑、上一张画面仍在处理、未授权及游戏未在前台等阻断状态会明确显示。首次或手动恢复前台判断会回查有界事件历史，避免已经打开较长时间的游戏被漏判。

若提示“未收到系统截图”，可以点击悬浮窗的“重新授权截图”。应用会停止旧会话，再请求新的系统捕获授权；Android 14 及以上的截图识别模式明确请求默认屏幕，并仍只处理指定游戏在前台的画面。系统保护、设备策略或画面布局限制仍须实机核对。排查记录与验证范围见 [识别按钮问题说明](docs/recognition-button-investigation.md)。

`tests/device_flow.py` 需要显式设置 `LANGYU_TEST_DEVICE`，通过 `LANGYU_ADB` 指定 adb 路径；`LANGYU_TEST_MODEL` 默认为 `moto`，可设为已授权测试设备的型号关键字。仓库不包含个人设备序列号。

## 开源依赖

Vosk 0.3.75、中文模型 `vosk-model-small-cn-0.22`、JNA 5.18.1、Tesseract4Android 4.9.0 和固定版本的 `tessdata_fast`。各依赖的完整许可随 APK 位于 assets。
