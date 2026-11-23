// 码流下载：候选地址轮询 + UA 轮换 + 断点重试。
//
// B 站给的码流会有 baseUrl + 若干 backupUrl，官方节点和边缘节点混在一起；实测
// 单条连接经常被 CDN 半路掐断，所以策略是：好节点优先、失败就换、每个地址重试两次。
#pragma once

#include <string>
#include <vector>

#include "http.h"

namespace bili {

struct DownloadResult {
    bool ok = false;
    long long bytes = 0;
    std::string error;  // 失败时是各次尝试的诊断汇总
};

// 依次尝试 urls 里的地址，成功即返回。path 会被覆盖写入。
DownloadResult downloadStream(HttpClient& http, const std::vector<std::string>& urls, const std::string& path,
                               const ProgressFn& progress, const std::string& cookie = std::string());

}  // namespace bili
