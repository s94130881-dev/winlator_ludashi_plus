#include <jni.h>
#include <cstdlib>
#include <cerrno>

namespace WinlatorMemory {

static constexpr int DEFAULT_RAM_GB = 8;
static constexpr int DEFAULT_RAM_MB = DEFAULT_RAM_GB * 1024;

static constexpr int MIN_RAM_MB = 1024;
static constexpr int MAX_RAM_MB = 65536;

static int parseEnvInt(const char* name, int defaultValue)
{
    const char* env = std::getenv(name);

    if (env == nullptr || *env == '\0')
        return defaultValue;

    errno = 0;

    char* end = nullptr;
    long value = std::strtol(env, &end, 10);

    if (errno != 0 ||
        end == env ||
        *end != '\0') {
        return defaultValue;
    }

    if (value < MIN_RAM_MB || value > MAX_RAM_MB)
        return defaultValue;

    return static_cast<int>(value);
}

static int getConfiguredRamMB()
{
    // Preferência: RAM em MB
    const char* ramMB = std::getenv("WINLATOR_FAKE_RAM_MB");

    if (ramMB != nullptr && *ramMB != '\0') {
        return parseEnvInt(
            "WINLATOR_FAKE_RAM_MB",
            DEFAULT_RAM_MB
        );
    }

    // Fallback: RAM em GB
    const char* ramGB = std::getenv("WINLATOR_FAKE_RAM_GB");

    if (ramGB != nullptr && *ramGB != '\0') {
        int gb = parseEnvInt(
            "WINLATOR_FAKE_RAM_GB",
            DEFAULT_RAM_GB
        );

        long mb = static_cast<long>(gb) * 1024L;

        if (mb >= MIN_RAM_MB && mb <= MAX_RAM_MB)
            return static_cast<int>(mb);
    }

    return DEFAULT_RAM_MB;
}

static int getConfiguredRamGB()
{
    return getConfiguredRamMB() / 1024;
}

} // namespace WinlatorMemory


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
