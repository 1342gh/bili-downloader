# Bili 下载（Android）

自用 B 站下载器：解析 → 下载 → 转码 / AI 插帧 / 内置播放，界面为 WebView + HTML/CSS。

## 文件

| 文件 | 说明 |
|---|---|
| `Bili下载_v2.6.1.apk` | Android APK（arm64-v8a，Android 10+） |
| `Bili下载_v2.6.2.apk` | 2.6.2 包（带应用内更新） |
| `Bili下载_v2.6.2_cpp.apk` | 2.6.2 + C++ 核心（`libbili.so`），见下 |

## 版本

- 版本名：2.6.1
- versionCode：20
- 大小：23.56 MB

## C++ 版（`Bili下载_v2.6.2_cpp.apk`）

把 Java 业务层的「解析 / WBI 签名 / 取流 / 下载」搬到了 C++（NDK 编译的 `libbili.so`），
UI 仍是原来的 WebView 页面。特点：

- 下载器带**断点续传**：B 站 CDN 单条连接会在中途 reset，靠 Range 从断点接着下；
- 每个地址有 30 秒预算，卡住的节点不会拖住整个任务；
- 保留原 Java 下载器作为回退：C++ 走不通时自动切回，功能不降级；
- 调试入口 `NativeProbeActivity`（`adb shell am start -n com.dsh.biliurl/.NativeProbeActivity --es url BV...`）。

大小 24.2 MB（多出 `libbili.so` 405 KB + `libc++_shared.so` 1.4 MB），签名与其它包一致，可直接覆盖安装。

```
sha256  c8c30606b1f6665e8a6cf9c0c63abc1ed1ab75f2db6f7a921c01d2ed67bad3bf
```

## 应用内更新

App 支持「高级 → 更新」填入 JSON 清单地址：

```json
{
  "versionCode": 21,
  "versionName": "2.6.2",
  "url": "https://example.com/BiliDownload_2.6.2.apk",
  "changelog": "修复 xxx"
}
```

版本号大于当前值时会弹更新横幅，下载后调系统安装器。

本仓库当前的更新清单（直接填这个地址）：

```
https://github.com/1342gh/bili-downloader/releases/latest/download/latest.json
```

对应版本：[`v2.6.7`](https://github.com/1342gh/bili-downloader/releases/tag/v2.6.7)（versionCode 26，ExoPlayer + 流式缓冲）。

用 GitHub Releases 的话：

```
https://github.com/<用户>/<仓库>/releases/latest/download/latest.json
https://github.com/<用户>/<仓库>/releases/latest/download/BiliDownload.apk
```

## 校验

```
sha256  1D5A239819837299AA2A54AF049D265A...   # v2.6.1
```

## 源码

完整源码就在本仓库：

| 路径 | 内容 |
|---|---|
| `app/` | Java 壳 + WebView 界面（`app/assets/web/` 是 HTML/CSS/JS） |
| `native/` | C++ 核心（NDK 编出 `libbili.so`：WBI 签名 / 取流 / 断点续传 / ffmpeg 参数） |
| `build.ps1` | 手工构建链：aapt2 → javac → d8 → jar → zipalign → apksigner（不需要 Gradle） |
| `tools/fetch-libs.ps1` | 拉第三方库（media3 1.4.1 + guava），抽出 `classes.jar` |
| `SOURCE.md` | 构建步骤、环境变量、仓库里缺哪些二进制、实现要点 |

签名 keystore、ffmpeg/rife 二进制、media3 的 jar **不在仓库里**，细节见 `SOURCE.md`。
签名口令走环境变量 `DSH_KS_PASS`，不写进任何文件。