# GTIPFinder — GoogleTranslate IP Finder (Android)

Android 版谷歌翻译 IP 测速工具，移植自 [GoodCoder666/GoogleTranslate_IPFinder](https://github.com/GoodCoder666/GoogleTranslate_IPFinder)。

## 快速开始

### 方式一：Android Studio（推荐）
1. 安装 Android Studio Hedgehog (2023.1.1) 或更高版本
2. 打开 `/workspace/android` 目录
3. 等待 Gradle Sync 完成（首次较慢）
4. 连接手机或启动模拟器 → Run

### 方式二：命令行构建
```bash
cd /workspace/android

# 1) 创建 local.properties 指向你的 Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 首次运行前下载 gradle-wrapper.jar
#    Android Studio 打开项目时会自动下载
#    或手动：
mkdir -p gradle/wrapper
curl -sL -o gradle/wrapper/gradle-wrapper.jar \
  https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar

# 3) 生成 Debug APK
./gradlew assembleDebug

# 4) 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin 1.9 |
| UI | Jetpack Compose + Material3 |
| 网络 | OkHttp 4.12 (自定义 DNS 劫持 + TLS/SNI 伪装) |
| 并发 | Kotlin Coroutines |
| 存储 | DataStore Preferences (规划中) |

## 核心原理

与 Python 版一致：
```
OkHttp 自定义 DNS → 让 translate.googleapis.com 解析到目标 IP
                  → TLS 握手时 SNI 仍是 translate.googleapis.com
                  → HTTP Host Header: translate.googleapis.com
                  → 实际 TCP 连接连的是目标 IP
```

## 目录结构

```
android/
├── build.gradle.kts                 # 项目级
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts             # App 级
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/gtipfinder/
│       │   ├── MainActivity.kt
│       │   ├── network/
│       │   │   ├── OkHttpClientFactory.kt   # DNS 劫持客户端
│       │   │   └── IpTester.kt              # 测速引擎
│       │   ├── data/
│       │   │   ├── BuiltinIpDatabase.kt     # 内置 1500+ GWS IP
│       │   │   └── OnlineSyncService.kt     # 在线同步
│       │   ├── scan/CidrUtil.kt             # CIDR 扫描
│       │   ├── hosts/HostsManager.kt        # Hosts 导出
│       │   └── ui/                          # Compose UI
│       └── res/
```

## 版本对齐

| 组件 | 版本 |
|---|---|
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |
| Gradle Wrapper | 8.7 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 (Android 7.0) |
| Compose BOM | 2024.09.00 |
| OkHttp | 4.12.0 |
| Coroutines | 1.8.1 |

## 功能

- [x] 批量测速（并发 + 多次重复取平均）
- [x] 内置 1500+ GWS IPv4 库
- [x] 在线 IP 库同步（ghproxy 镜像 + GitHub 直连）
- [x] CIDR 网段扫描
- [x] 单 IP 稳定性调试
- [x] Hosts 片段生成/导出
- [x] 设置持久化（超时/并发/重复次数）
- [ ] Magisk/KernelSU 自动写入（需 root + su）
- [ ] DataStore 设置持久化（当前为内存）
