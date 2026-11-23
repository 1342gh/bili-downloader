# bili_core —— Bili 下载的 C++ 核心

用 C++ 重写 App 的业务层（方案 A）：UI 仍是 WebView 里的 `index.html`/`app.js`，
Java 只保留 Activity + JS 桥的壳，解析/签名/取流/下载/转码/插帧全部搬到 C++。

## 为什么不是"纯 C++ App"

原 App 的界面是 HTML/CSS/JS（`assets/web/`），不是 Java 布局；把 UI 也换成 C++
意味着重画一套 NativeActivity 界面，收益为零。所以边界定在：**UI 不动，业务全部下沉**。

## 目录

```
native/
├─ CMakeLists.txt          # 宿主 + 安卓 两种构建
├─ third_party/json.hpp    # nlohmann/json 3.11.3（单头文件）
├─ src/
│  ├─ md5.{h,cpp}          # MD5，给 WBI 签名用（不引 OpenSSL）
│  ├─ wbi.{h,cpp}          # mixin_key 置换表 / URL 编码 / w_rid 签名
│  ├─ http.h               # HTTP 抽象（get / download / 进度回调）
│  ├─ http_winhttp.cpp     # Windows 宿主实现（WinHTTP，零依赖）
│  ├─ http_curl_cli.cpp    # Linux/WSL 宿主实现（调系统 curl，零依赖）
│  ├─ bili.{h,cpp}         # 链接解析 / nav / view / playurl / 画质挑选 / 候选地址排序
│  ├─ download.cpp         # 码流下载：多地址 + 双 UA + 重试 + 诊断汇总
│  └─ ffmpeg_cmd.cpp       # ffmpeg 参数拼装（合并 / 转码 / 重新贴音轨）
└─ tools/bili_probe.cpp    # 宿主机验证工具（自测 + 真实接口 + 下载）
```

## 和 Java 版的对应关系

| 原 Java | 现在 |
|---|---|
| `Wbi.java` | `wbi.cpp`（算法逐字对齐，置换表同上） |
| `BiliApi.java`（解析 / view / playurl / 画质） | `bili.cpp` |
| `BiliApi.download()`（多地址重试） | `download.cpp` |
| `Merger.java`（MediaExtractor/MediaMuxer 搬运） | `ffmpeg_cmd.cpp` 出 `-c copy` 参数，交给打包的 ffmpeg 执行 |
| `MainActivity` 里的 `extOf/mimeOf/audioCodec/videoFilters/describe` | `ffmpeg_cmd.cpp` 同名函数 |
| `WebFetch.java`（借 WebView 发 API） | **不再需要**：实测 C++ 直连 api 返回 200，见下 |

## 已验证（不是"应该能跑"）

宿主 WSL + g++ 13.3 编译，`bili_probe` 实跑真实接口：

```
[md5] md5("") / md5("abc") / md5("消息摘要")        ok
[wbi] keyFromUrl / mixinKey / urlEncode 中文         ok
目标: bvid=BV1GJ411x7h7  p=1
登录: 未登录（最高 480P）
标题: 【官方 MV】Never Gonna Give You Up - Rick Astley
UP  : 索尼音乐中国
cid : 137649199  pages=1
可用画质: 360P 640x360 / 480P 852x480
选中视频: 480P 852x480 avc1.64001F  0.8 Mbps  候选 3 条
选中音频: id=30280 mp4a.40.2  204 kbps  候选 3 条
```

- `mixinKey` 与独立用 PowerShell 算出的 `ea1db124af3c7062474693fa704f4ff8` 一致；
- 核心五个文件用 NDK clang `--target=aarch64-linux-android29 -Wall -Wextra` 语法检查通过，**零警告**；
- 由此确认：**B 站接口不再必须借 WebView**（原来 Java `HttpURLConnection` 报 412 的问题，
  在 C++/curl 这条栈上没有复现）。

## 安卓侧接入（已完成第一刀）

- `NativeBridge.java`：`System.loadLibrary("bili")` + `native probe/download`，
  同时提供 C++ 回调的 `httpGet` / `httpDownload`（HttpURLConnection，短连接 + Range）。
- `http_jni.cpp`：C++ ↔ Java 的 HTTP 后端，`JNI_OnLoad` 里缓存 `NativeBridge` 类和方法 id。
- `jni_bridge.cpp`：`probe()`（解析→nav→view→playurl→选流）与 `download()`（下视频轨+音频轨）。
- `NativeProbeActivity.java`：调试入口，`adb shell am start` 直接跑，结果进 logcat。
- `native/build-android.ps1`：NDK clang 编 `libbili.so`（399 KB，strip 后），
  连 `libc++_shared.so`（1.4 MB）一起被 `build.ps1` 打进 APK。

真机验证（Xperia XZ3 / Android 15 / SDM845）：

```
I biliurl-native: httpGet 200 249B  https://api.bilibili.com/x/web-interface/nav
I biliurl-native: httpGet 200 7207B https://api.bilibili.com/x/web-interface/view?bvid=BV1GJ411x7h7
I biliurl-native: httpGet 200 50289B https://api.bilibili.com/x/player/playurl?...
I biliurl-native: PROBE {"ok":true,"cid":137649199,"qualities":["360P 640x360","480P 852x480"],
  "video":{"id":32,"name":"480P","codecs":"avc1.64001F","urls":3},
  "audio":{"id":30280,"codecs":"mp4a.40.2","urls":3},"ms":877}
```

两个实测结论：
1. **Android 的 HttpURLConnection 也不再 412**（nav/view/playurl 全部 200），
   原来那套 `WebFetch`（借 WebView 发请求 + 阻塞等回调）在 C++ 版里可以彻底不要。
2. 下载必须**断点续传**：B 站 CDN 建连后能跑到 ~2.7 MB/s，但会在任意位置 reset
   （实测 7.7 MB 处被掐）。换成"带 Range 续传 + 短连接"后一次拿满：

```
I biliurl-native: download 206 20872903B (offset 0) in 7776ms <- upos-sz-mirrorhwo1.bilivideo.com
I biliurl-native: download 206 5408198B (offset 0) in 1714ms <- upos-sz-mirrorcoso1.bilivideo.com
I biliurl-native: DOWNLOAD {"ok":true,"quality":"480P","ms":10059,
   "video":{"bytes":20872903,"ok":true},"audio":{"bytes":5408198,"ok":true}}
```

   20.9 MB 视频 + 5.4 MB 音频，10 秒下完。原来的 Java 下载器只对"应用更新"做了续传，
   码流下载没有，这是 C++ 版补上的一个实质改进。

## 构建

宿主机（WSL / Linux，验证用）：

```bash
cd /mnt/d/android-build/native
g++ -std=c++17 -O2 -Wall -Wextra -I src -I third_party \
    src/md5.cpp src/wbi.cpp src/bili.cpp src/download.cpp \
    src/ffmpeg_cmd.cpp src/http_curl_cli.cpp tools/bili_probe.cpp -o /tmp/bili_probe
/tmp/bili_probe --selftest
/tmp/bili_probe BV1GJ411x7h7
```

安卓（待接入 `build.ps1`）：

```bash
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
```

## 主流程接入（已完成）

`MainActivity.download()` 现在先调 `NativeBridge.downloadUrls()`（C++ 断点续传），
失败才回退原来的 Java 下载器。真机跑 1080P+ 的实测日志：

```
下载视频…
  C++ 下载器失败，回退 Java: 超过时间预算（30s），放弃 C++ 下载
  upos-sz-estgoss.bilivideo.com/safari:ERR:SocketTimeoutException timeout（已下 0 字节）
  video.m4s 完成 132.9 MB
下载音频…
  [C++] audio.m4s 完成 5.2 MB（1803ms）
合并音视频…
完成 ✓ …mp4 (138.1 MB)   目录：Movies/BiliUrl
```

同一台机器上，音轨走 C++（1.8 秒），视频轨那个 CDN 节点对 Java 的 TLS 栈不友好
（curl 同样 URL 能到 1.9 MB/s，Java 一直读到超时），于是按设计回退。两条路并存，
哪条能通用哪条，最终文件照常落地。

## 还没搬的部分（下一步）

1. `http_jni.cpp`：安卓侧 HTTP 实现（JNI 回调 Java 的 `HttpURLConnection`，或自带 TLS）
2. JNI 桥 `libbili.so`：暴露给 Java 的 `start / progress / log` 接口
3. `MainActivity` 瘦身成 WebView 壳（约 1500 行 → 预期 300 行）
4. RIFE 插帧：Java 的 `MediaExtractor` 取帧 + ncnn 推理，改 C++ 直接用 ncnn
5. 保存到 MediaStore、通知、应用内更新、搜索/feed、播放器（`PlayerActivity`）、插件宿主

Java 现有代码一行未改，App 目前仍可正常打包运行；C++ 核心是并行新增，不破坏现状。
