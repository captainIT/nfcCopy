# NFC Copy · 门禁卡读写 (Android)

一个用 **Kotlin + Jetpack Compose** 写的 Android app，用于：

1. **读卡**：识别 13.56MHz 卡片类型、UID、ATQA/SAK；对 MIFARE Classic (M1) 卡用默认密钥字典尝试解密并 dump 全部扇区。
2. **写卡**：把读到的数据克隆写入一张 **空白魔术卡（CUID / Gen2 / Gen1a，UID 可改卡）**。

> 用途仅限复制**你自己拥有、且有权复制**的卡片（如自家门禁、公司发给你本人的卡）。不要用于任何未经授权的场景。

---

## ⚠️ 必读：能做到什么，做不到什么

| 卡类型 | 手机能读？ | 手机能复制到「空白魔术卡」？ |
|---|---|---|
| MIFARE Classic (M1)，默认密钥 | 能，完整 dump | 能（目标必须是魔术卡） |
| MIFARE Classic (M1)，非默认密钥 | 只能读 UID/头，扇区读不出 | 数据不全，通常失败 |
| 125kHz 低频卡（EM4100/HID，白色厚卡） | **完全不能**（手机无低频天线） | 不能 |
| CPU 卡 / 加密卡 / 二代身份证 | 只能识别存在，读不了内容 | 不能 |
| NTAG / Ultralight / NDEF | 能读 | 本 app 暂未做写入 |

关于「把卡装进手机模拟」：普通未 root 手机的 HCE **无法**模拟 M1 卡，也**不能自定义 UID**，所以本项目走的是「读原卡 → 写到实体魔术卡」这条真实可行的路线，而不是手机模拟。

**目标卡必须是魔术卡**：普通空白 M1 卡的 Block0（含 UID）出厂只读，永远无法写入卡号。购买时认准 “UID 可改 / CUID / FUID / Gen2 / Gen1a / 魔术卡”。

---

## 运行环境要求

- **一台带 NFC 的真机**（Android 8.0 / API 26 及以上）。模拟器无法刷实体卡。
- Android Studio（自带 Android SDK 与 Gradle）。本机已装 **JDK 17**。

## 如何构建运行

### 方式 A：Android Studio（推荐）

1. `Open` 打开本目录 `nfcCopy`。
2. 首次会自动下载 SDK/依赖并生成 `local.properties`（指向你的 SDK）。
3. 手机开启「开发者选项 → USB 调试」和系统 **NFC**，USB 连接。
4. 点 ▶ Run 安装到手机。

### 方式 B：命令行

需要先设置 Android SDK。在项目根目录新建 `local.properties`：

```
sdk.dir=/Users/你的用户名/Library/Android/sdk
```

然后：

```bash
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew installDebug         # 连接真机后直接安装
```

## 使用步骤

1. 打开 app，确保系统 NFC 已开启。
2. **读卡页**：把门禁卡贴到手机背面 NFC 区域 → 显示类型/UID/扇区数据。
   - 点「导出 JSON」可把 dump 分享/备份。
   - 点「用于写入」把当前卡数据设为写入源，并跳到写卡页。
3. **写卡页**：换上空白魔术卡贴到手机 → 自动写入。
   - 「写入扇区尾块」开关默认关闭：只克隆数据、保留目标卡默认密钥，**更安全**。开启会连密钥/权限位一起写，若访问位写错可能锁死扇区，谨慎使用。
4. 写完后回读卡页重新读魔术卡，核对 UID 与数据是否一致。

## 工程结构

```
app/src/main/java/com/example/nfccopy/
├── MainActivity.kt          # NFC reader mode 入口，分发 Tag
├── MainViewModel.kt         # 读/写模式状态机
├── model/CardDump.kt        # 卡片数据模型 + JSON 导入导出
├── nfc/
│   ├── MifareKeys.kt        # M1 默认密钥字典
│   ├── CardReader.kt        # 识别 + 逐扇区解密 dump
│   └── CardWriter.kt        # 写魔术卡（Gen2 标准写 + Gen1a 后门兜底）
├── util/Hex.kt              # 十六进制工具
└── ui/                      # Compose 界面 + 主题
```

## 技术说明

- 读卡用 `NfcAdapter.enableReaderMode`（前台读卡模式），支持 A/B/F/V。
- M1 解密：对每个扇区遍历默认密钥字典，`authenticateSectorWithKeyA/B` 成功后 `readBlock`。
- 写 Block0(UID)：先按 Gen2 方式（认证后 `writeBlock(0, …)`），失败再尝试 Gen1a 后门指令（`0x40/0x43/0xA0`，取决于手机 NFC 控制器是否支持，非 100% 可用）。
