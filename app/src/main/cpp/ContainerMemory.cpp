#include <jni.h>
#include <cstdlib>
#include <cerrno>

namespace WinlatorMemory {

static constexpr int DEFAULT_RAM_GB = 8;
static constexpr int DEFAULT_RAM_MB = 8192;

static constexpr int MIN_RAM_MB = 1024;
static constexpr int MAX_RAM_MB = 65536;

static int parseInteger(const char* value, int fallback)
{
    if (value == nullptr || *value == '\0')
        return fallback;

    errno = 0;

    char* end = nullptr;
    long parsed = std::strtol(value, &end, 10);

    if (errno != 0 ||
        end == value ||
        *end != '\0') {
        return fallback;
    }

    if (parsed < MIN_RAM_MB || parsed > MAX_RAM_MB)
        return fallback;

    return static_cast<int>(parsed);
}

static int getConfiguredRamMB()
{
    // Primeiro tenta a configuração em MB.
    const char* ramMB = std::getenv("WINLATOR_FAKE_RAM_MB");

    if (ramMB != nullptr && *ramMB != '\0') {
        return parseInteger(ramMB, DEFAULT_RAM_MB);
    }

    // Se MB não existir, tenta GB.
    const char* ramGB = std::getenv("WINLATOR_FAKE_RAM_GB");

    if (ramGB != nullptr && *ramGB != '\0') {
        int gb = parseInteger(ramGB, DEFAULT_RAM_GB);

        long mb = static_cast<long>(gb) * 1024L;

        if (mb >= MIN_RAM_MB && mb <= MAX_RAM_MB) {
            return static_cast<int>(mb);
        }
    }

    // Padrão absoluto.
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
