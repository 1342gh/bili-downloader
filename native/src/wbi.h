// B 站 WBI 签名（2023 年后 playurl / search 等接口要求 w_rid + wts）。
//
// 算法就三步：
//   1. 从 /x/web-interface/nav 的 wbi_img 里取 img_key、sub_key（取文件名去后缀）；
//   2. 把两个 key 拼起来，按固定 64 位置换表取前 32 位，得到 mixin_key；
//   3. 参数按 key 升序拼成 query，拼上 mixin_key 求 MD5，作为 w_rid 追加回去。
#pragma once

#include <map>
#include <string>

namespace bili {
namespace wbi {

// imgKey + subKey → mixin_key（32 位）
std::string mixinKey(const std::string& imgKey, const std::string& subKey);

// 从 wbi_img 的完整 URL 里取 key：去目录、去 .png 后缀
std::string keyFromUrl(const std::string& url);

// 与 Java URLEncoder.encode(s, "UTF-8") 等价：保留 [A-Za-z0-9._*-]，空格转 +，其余 %XX
std::string urlEncode(const std::string& value);

// 参数按 key 升序编码成 query，并追加 &w_rid=<md5(query + mixin_key)>
// 注意：调用方负责把 wts 放进 params（服务端要求秒级时间戳）。
std::string sign(const std::map<std::string, std::string>& params, const std::string& mixinKey);

}  // namespace wbi
}  // namespace bili
