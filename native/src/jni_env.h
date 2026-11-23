// JNI 初始化入口（在 http_jni.cpp 里实现）：缓存 JavaVM、NativeBridge 类和回调方法。
#pragma once

#include <jni.h>

namespace bili {
namespace jni {

void initJni(JavaVM* vm);

}  // namespace jni
}  // namespace bili
