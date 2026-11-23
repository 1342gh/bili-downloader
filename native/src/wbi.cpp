#include "wbi.h"

#include <cstdio>

#include "md5.h"

namespace bili {
namespace wbi {
namespace {

// 官方前端里那张固定的 64 位置换表
const int kMixinTab[64] = {
    46, 47, 18, 2,  53, 8,  23, 32, 15, 50, 10, 31, 58, 3,  45, 35,
    27, 43, 5,  49, 33, 9,  42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7,  16, 24, 55, 40, 61, 26, 17, 0,  1,  60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6,  63, 57, 62, 11, 36, 20, 34, 44, 52,
};

inline bool isUnreserved(unsigned char c) {
    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
    return c == '.' || c == '-' || c == '*' || c == '_';
}

}  // namespace

std::string mixinKey(const std::string& imgKey, const std::string& subKey) {
    const std::string raw = imgKey + subKey;
    std::string out;
    out.reserve(32);
    for (int idx : kMixinTab) {
        if (idx < static_cast<int>(raw.size())) out.push_back(raw[static_cast<size_t>(idx)]);
        if (out.size() >= 32) break;
    }
    return out;
}

std::string keyFromUrl(const std::string& url) {
    if (url.empty()) return {};
    const size_t slash = url.find_last_of('/');
    std::string name = slash == std::string::npos ? url : url.substr(slash + 1);
    const size_t dot = name.find_last_of('.');
    if (dot != std::string::npos && dot > 0) name.resize(dot);
    return name;
}

std::string urlEncode(const std::string& value) {
    std::string out;
    out.reserve(value.size() * 3);
    for (const unsigned char c : value) {
        if (isUnreserved(c)) {
            out.push_back(static_cast<char>(c));
        } else if (c == ' ') {
            out.push_back('+');
        } else {
            char buf[4];
            std::snprintf(buf, sizeof(buf), "%%%02X", c);
            out.append(buf);
        }
    }
    return out;
}

std::string sign(const std::map<std::string, std::string>& params, const std::string& mixinKey) {
    std::string query;
    for (const auto& [key, value] : params) {  // std::map 已经按 key 升序
        if (!query.empty()) query.push_back('&');
        query += urlEncode(key);
        query.push_back('=');
        query += urlEncode(value);
    }
    return query + "&w_rid=" + md5Hex(query + mixinKey);
}

}  // namespace wbi
}  // namespace bili
