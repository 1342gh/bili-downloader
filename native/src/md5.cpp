#include "md5.h"

#include <cmath>
#include <cstdint>
#include <cstring>

namespace bili {
namespace {

struct Md5Ctx {
    uint32_t a = 0x67452301u;
    uint32_t b = 0xefcdab89u;
    uint32_t c = 0x98badcfeu;
    uint32_t d = 0x10325476u;
    uint64_t totalBytes = 0;
    uint8_t buf[64];
    size_t bufLen = 0;
};

inline uint32_t rotl(uint32_t x, int n) { return (x << n) | (x >> (32 - n)); }

// 每轮左移位数，标准表
const int kShift[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

// K[i] = floor(2^32 * |sin(i+1)|)，按定义算，省得抄错常量
inline uint32_t kConst(int i) {
    return static_cast<uint32_t>(std::floor(std::fabs(std::sin(static_cast<double>(i + 1))) * 4294967296.0));
}

void transform(Md5Ctx& ctx, const uint8_t* block) {
    uint32_t m[16];
    for (int i = 0; i < 16; ++i) {
        m[i] = static_cast<uint32_t>(block[i * 4]) |
               (static_cast<uint32_t>(block[i * 4 + 1]) << 8) |
               (static_cast<uint32_t>(block[i * 4 + 2]) << 16) |
               (static_cast<uint32_t>(block[i * 4 + 3]) << 24);
    }

    uint32_t a = ctx.a, b = ctx.b, c = ctx.c, d = ctx.d;
    for (int i = 0; i < 64; ++i) {
        uint32_t f;
        int g;
        if (i < 16) {
            f = (b & c) | (~b & d);
            g = i;
        } else if (i < 32) {
            f = (d & b) | (~d & c);
            g = (5 * i + 1) % 16;
        } else if (i < 48) {
            f = b ^ c ^ d;
            g = (3 * i + 5) % 16;
        } else {
            f = c ^ (b | ~d);
            g = (7 * i) % 16;
        }
        const uint32_t tmp = d;
        d = c;
        c = b;
        b = b + rotl(a + f + kConst(i) + m[g], kShift[i]);
        a = tmp;
    }

    ctx.a += a;
    ctx.b += b;
    ctx.c += c;
    ctx.d += d;
}

void update(Md5Ctx& ctx, const uint8_t* data, size_t len) {
    ctx.totalBytes += len;
    while (len > 0) {
        const size_t take = 64 - ctx.bufLen < len ? 64 - ctx.bufLen : len;
        std::memcpy(ctx.buf + ctx.bufLen, data, take);
        ctx.bufLen += take;
        data += take;
        len -= take;
        if (ctx.bufLen == 64) {
            transform(ctx, ctx.buf);
            ctx.bufLen = 0;
        }
    }
}

std::string finish(Md5Ctx& ctx) {
    const uint64_t bitLen = ctx.totalBytes * 8;

    uint8_t pad[72];
    size_t padLen = 0;
    pad[padLen++] = 0x80;
    // 补到 56 字节（mod 64）
    while ((ctx.totalBytes + padLen) % 64 != 56) pad[padLen++] = 0x00;
    update(ctx, pad, padLen);

    uint8_t lenBytes[8];
    for (int i = 0; i < 8; ++i) lenBytes[i] = static_cast<uint8_t>((bitLen >> (8 * i)) & 0xff);
    update(ctx, lenBytes, 8);

    static const char* hex = "0123456789abcdef";
    std::string out;
    out.reserve(32);
    for (uint32_t word : {ctx.a, ctx.b, ctx.c, ctx.d}) {
        for (int i = 0; i < 4; ++i) {
            const uint8_t byte = static_cast<uint8_t>((word >> (8 * i)) & 0xff);
            out.push_back(hex[byte >> 4]);
            out.push_back(hex[byte & 0x0f]);
        }
    }
    return out;
}

}  // namespace

std::string md5Hex(const std::string& input) {
    Md5Ctx ctx;
    update(ctx, reinterpret_cast<const uint8_t*>(input.data()), input.size());
    return finish(ctx);
}

}  // namespace bili
