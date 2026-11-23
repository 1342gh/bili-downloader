// MD5（RFC 1321）—— 自实现，避免为了一个哈希把 OpenSSL 整条链拖进来。
// WBI 签名只需要它，64 字节分组、小端、输出 32 位小写 hex。
#pragma once

#include <string>

namespace bili {

// 返回 32 位小写十六进制摘要
std::string md5Hex(const std::string& input);

}  // namespace bili
