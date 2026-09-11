#include <jni.h>

static constexpr int DEFAULT_RAM_GB = 8;
static constexpr int DEFAULT_RAM_MB = 8192;

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getDefaultRamGB(
        JNIEnv* env,
        jobject thiz) {
    return DEFAULT_RAM_GB;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_container_ContainerMemory_getDefaultRamMB(
        JNIEnv* env,
        jobject thiz) {
    return DEFAULT_RAM_MB;
}
