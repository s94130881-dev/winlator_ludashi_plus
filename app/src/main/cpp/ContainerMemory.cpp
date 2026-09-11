#include <jni.h>
#include <cstdlib>
#include <cstdint>

namespace WinlatorMemory {

static constexpr int DEFAULT_RAM_GB = 8;
static constexpr int DEFAULT_RAM_MB = 8192;

static int getRamMB() {
    const char* env = std::getenv("WINLATOR_FAKE_RAM_MB");

    if (env != nullptr) {
        char* end = nullptr;
        long value = std::strtol(env, &end, 10);

        if (end != env && value >= 1024 && value <= 65536)
            return static_cast<int>(value);
    }

    return DEFAULT_RAM_MB;
}

static int getRamGB() {
    return getRamMB() / 1024;
}

}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getDefaultRamGB(
        JNIEnv* env,
        jobject thiz) {
    return WinlatorMemory::DEFAULT_RAM_GB;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getDefaultRamMB(
        JNIEnv* env,
        jobject thiz) {
    return WinlatorMemory::DEFAULT_RAM_MB;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getConfiguredRamMB(
        JNIEnv* env,
        jobject thiz) {
    return WinlatorMemory::getRamMB();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getConfiguredRamGB(
        JNIEnv* env,
        jobject thiz) {
    return WinlatorMemory::getRamGB();
}
