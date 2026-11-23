# 源码说明

`Bili 下载` 的完整源码：Java 壳 + WebView 界面 + C++ 核心（`libbili.so`）+ ExoPlayer 播放器。

## 目录

```
app/                     Android 应用
├─ AndroidManifest.xml
├─ java/com/dsh/biliurl/ Java 源码（Activity / JS 桥 / 下载 / 播放 / 缓冲代理）
├─ assets/web/           UI（index.html / app.js / style.css），改界面只动这里
└─ res/                  图标

native/                  C++ 核心（NDK 编译成 libbili.so）
├─ src/                  md5 / wbi 签名 / bili 接口 / 下载 / ffmpeg 参数 / JNI 桥
├─ tools/                宿主机验证工具 bili_probe
├─ third_party/json.hpp  nlohmann/json 单头文件
└─ build-android.ps1     编 libbili.so（arm64-v8a）

build.ps1                打包脚本：aapt2 → javac → d8 → jar → zipalign → apksigner
tools/fetch-libs.ps1     下载第三方库（media3 / guava），抽出 classes.jar
```

## 构建

环境要求：

- JDK 21（`D:\Java\jdk-21`，或用 `DSH_JDK` 指定）
- Android SDK：build-tools 34.0.0 + platform android-34（或用 `DSH_SDK` 指定）
- Node 22+（只用于 `native` 的 cmake 可选流程）
- 签名 keystore（**仓库里不包含**，自己生成或用现有的）

步骤：

```powershell
# 1. 拉第三方库（media3 1.4.1 + guava，约 7 MB jar）
powershell -ExecutionPolicy Bypass -File tools\fetch-libs.ps1

# 2. 编 C++ 核心（可选，没有它也能出包，只是没有 C++ 下载器）
powershell -ExecutionPolicy Bypass -File native\build-android.ps1

# 3. 打包 + 签名（口令走环境变量，不要写进脚本）
$env:DSH_KS_PASS = '你的 keystore 口令'
powershell -ExecutionPolicy Bypass -File build.ps1
```

产物在仓库根目录的 `BiliDownload.apk`。

## 仓库里没有的东西

| 东西 | 原因 |
|---|---|
| `dsh.keystore` 和签名口令 | 私钥，泄露就等于别人能签你的更新包 |
| ffmpeg 可执行文件、`librifejni.so` | 二进制体积大，自己准备或用 `native` 里的源码编 |
| `assets/rife/flownet.bin`、`assets/sample/*` | RIFE 模型和示例视频，约 11 MB |
| `libs/media3/*` | 第三方库，用 `tools/fetch-libs.ps1` 现拉 |

## 几个实现要点

- **请求签名**：B 站 2023 年后的接口要 `w_rid`，算法在 `native/src/wbi.cpp`（置换表 + MD5），宿主机跑 `bili_probe --selftest` 可自测。
- **下载**：`native/src/download.cpp` 多地址轮询 + 双 UA + Range 断点续传，单条连接被 CDN 掐断能接着下。
- **播放**：`StreamProxy.java` 是本地缓冲代理（App 自己下载，播放器读 `127.0.0.1`）；`PlayerActivity.java` 用 ExoPlayer，横屏裁剪铺满、竖屏等比完整。
- **UI 与 Java 的桥**：`assets/web/app.js` 调 `window.Android.*`（`JsBridge.java`），Java 回调 `window.bili.*`。
