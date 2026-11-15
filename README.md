# Bili 下载（Android）

自用 B 站下载器：解析 → 下载 → 转码 / AI 插帧 / 内置播放，界面为 WebView + HTML/CSS。

## 文件

| 文件 | 说明 |
|---|---|
| `Bili下载_v2.6.1.apk` | Android APK（arm64-v8a，Android 10+） |

## 版本

- 版本名：2.6.1
- versionCode：20
- 大小：23.56 MB

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

用 GitHub Releases 的话：

```
https://github.com/<用户>/<仓库>/releases/latest/download/latest.json
https://github.com/<用户>/<仓库>/releases/latest/download/BiliDownload.apk
```

## 校验

```
sha256  1D5A239819837299AA2A54AF049D265A...
```