#include <jni.h>
#include <cstdlib>
#include <cerrno>
#include <climits>

namespace WinlatorMemory {

static constexpr int DEFAULT_RAM_GB = 8;
static constexpr int DEFAULT_RAM_MB = 8192;

static constexpr int MIN_RAM_MB = 1024;
static constexpr int MAX_RAM_MB = 65536;

static int getConfiguredRamMB()
{
    const char* env = std::getenv("WINLATOR_FAKE_RAM_MB");

    if (env == nullptr || *env == '\0')
        return DEFAULT_RAM_MB;

    errno = 0;

    char* end = nullptr;
    long value = std::strtol(env, &end, 10);

    if (errno != 0 ||
        end == env ||
        *end != '\0' ||
        value < MIN_RAM_MB ||
        value > MAX_RAM_MB) {
        return DEFAULT_RAM_MB;
    }

    return static_cast<int>(value);
}

static int getConfiguredRamGB()
{
    return getConfiguredRamMB() / 1024;
}

}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getDefaultRamGB(
        JNIEnv* env,
        jobject thiz)
{
    return WinlatorMemory::DEFAULT_RAM_GB;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getDefaultRamMB(
        JNIEnv* env,
        jobject thiz)
{
    return WinlatorMemory::DEFAULT_RAM_MB;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getConfiguredRamMB(
        JNIEnv* env,
        jobject thiz)
{
    return WinlatorMemory::getConfiguredRamMB();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getConfiguredRamGB(
        JNIEnv* env,
        jobject thiz)
{
    return WinlatorMemory::getConfiguredRamGB();
}
