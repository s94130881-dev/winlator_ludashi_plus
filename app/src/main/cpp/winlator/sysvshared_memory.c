#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <fcntl.h>
#include <stdbool.h>
#include <pthread.h>
#include <sys/ipc.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <jni.h>
#include <android/log.h>

#include <linux/ashmem.h>
#include <linux/memfd.h>

#define LOG_TAG "WinlatorMemory"

#define LOGD(...) \
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define LOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * 26 GiB
 *
 * 26 * 1024 * 1024 * 1024
 * = 27,917,874,176 bytes
 *
 * Isto representa capacidade de memória virtual/backing store.
 * NÃO cria 26 GiB de RAM física no Android.
 */
#define GUEST_MEMORY_GIB 26ULL

#define GUEST_MEMORY_BYTES \
    (GUEST_MEMORY_GIB * 1024ULL * 1024ULL * 1024ULL)

/*
 * Limite máximo permitido para uma região individual.
 *
 * IMPORTANTE:
 * O limite é 26 GiB, mas uma região normal continua usando
 * o tamanho solicitado pelo chamador.
 */
#define MAX_SHARED_MEMORY_SIZE GUEST_MEMORY_BYTES


/*
 * Cria região ASHMEM.
 *
 * size é int64_t para permitir tamanhos superiores a 2 GiB.
 */
static int ashmemCreateRegion(const char *name, int64_t size)
{
    if (size <= 0) {
        LOGE("Invalid ashmem size: %" PRId64, size);
        return -1;
    }

    if ((uint64_t)size > MAX_SHARED_MEMORY_SIZE) {
        LOGE(
            "ashmem size exceeds 26 GiB limit: %" PRIu64,
            (uint64_t)size
        );
        return -1;
    }

    int fd = open("/dev/ashmem", O_RDWR);

    if (fd < 0) {
        LOGE("Unable to open /dev/ashmem");
        return -1;
    }

    char nameBuffer[ASHMEM_NAME_LEN] = {0};

    strncpy(
        nameBuffer,
        name,
        sizeof(nameBuffer) - 1
    );

    nameBuffer[sizeof(nameBuffer) - 1] = '\0';

    int ret = ioctl(
        fd,
        ASHMEM_SET_NAME,
        nameBuffer
    );

    if (ret < 0) {
        LOGE("ASHMEM_SET_NAME failed");
        goto error;
    }

    ret = ioctl(
        fd,
        ASHMEM_SET_SIZE,
        size
    );

    if (ret < 0) {
        LOGE(
            "ASHMEM_SET_SIZE failed for %" PRId64 " bytes",
            size
        );
        goto error;
    }

    LOGD(
        "Created ashmem region: %" PRId64 " bytes",
        size
    );

    return fd;

error:

    close(fd);
    return -1;
}


/*
 * memfd_create wrapper.
 */
static int createMemfd(
    const char *name,
    unsigned int flags
)
{
#ifdef __NR_memfd_create

    return syscall(
        __NR_memfd_create,
        name,
        flags
    );

#else

    return -1;

#endif
}


/*
 * Cria um memfd com tamanho 64-bit.
 *
 * jlong -> int64_t/off_t
 *
 * Permite representar 26 GiB corretamente.
 */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_sysvshm_SysVSharedMemory_createMemoryFd(
    JNIEnv *env,
    jclass obj,
    jstring name,
    jlong size
)
{
    (void)obj;

    if (name == NULL) {
        LOGE("Memory name is NULL");
        return -1;
    }

    if (size <= 0) {
        LOGE(
            "Invalid memory size: %" PRId64,
            (int64_t)size
        );
        return -1;
    }

    uint64_t requestedSize = (uint64_t)size;

    /*
     * Nunca permite ultrapassar 26 GiB.
     */
    if (requestedSize > MAX_SHARED_MEMORY_SIZE) {

        LOGE(
            "Requested memory exceeds 26 GiB: %" PRIu64,
            requestedSize
        );

        return -1;
    }

    const char *namePtr =
        (*env)->GetStringUTFChars(
            env,
            name,
            NULL
        );

    if (namePtr == NULL) {
        return -1;
    }

    int fd = createMemfd(
        namePtr,
        MFD_ALLOW_SEALING
    );

    (*env)->ReleaseStringUTFChars(
        env,
        name,
        namePtr
    );

    if (fd < 0) {
        LOGE("memfd_create failed");
        return -1;
    }

    /*
     * off_t precisa ser capaz de representar
     * o tamanho solicitado.
     */
    off_t fileSize = (off_t)requestedSize;

    if ((uint64_t)fileSize != requestedSize) {

        LOGE(
            "Size cannot be represented by off_t"
        );

        close(fd);
        return -1;
    }

    int result = ftruncate(
        fd,
        fileSize
    );

    if (result < 0) {

        LOGE(
            "ftruncate failed for %" PRIu64 " bytes",
            requestedSize
        );

        close(fd);
        return -1;
    }

    LOGD(
        "Created memfd: %" PRIu64 " bytes",
        requestedSize
    );

    return fd;
}


/*
 * Cria região ASHMEM através do JNI.
 */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_sysvshm_SysVSharedMemory_ashmemCreateRegion(
    JNIEnv *env,
    jobject obj,
    jint index,
    jlong size
)
{
    (void)env;
    (void)obj;

    char name[32];

    snprintf(
        name,
        sizeof(name),
        "sysvshm-%d",
        index
    );

    return ashmemCreateRegion(
        name,
        (int64_t)size
    );
}


/*
 * Mapeia segmento de memória compartilhada.
 *
 * size continua sendo 64-bit.
 */
JNIEXPORT jobject JNICALL
Java_com_winlator_cmod_sysvshm_SysVSharedMemory_mapSHMSegment(
    JNIEnv *env,
    jobject obj,
    jint fd,
    jlong size,
    jint offset,
    jboolean readonly
)
{
    (void)obj;

    if (fd < 0 || size <= 0) {
        LOGE("Invalid mmap parameters");
        return NULL;
    }

    if ((uint64_t)size > MAX_SHARED_MEMORY_SIZE) {
        LOGE("mmap exceeds 26 GiB limit");
        return NULL;
    }

    int protection;

    if (readonly) {
        protection = PROT_READ;
    } else {
        protection = PROT_READ | PROT_WRITE;
    }

    void *data = mmap(
        NULL,
        (size_t)size,
        protection,
        MAP_SHARED,
        fd,
        (off_t)offset
    );

    if (data == MAP_FAILED) {
        LOGE(
            "mmap failed for %" PRId64 " bytes",
            (int64_t)size
        );
        return NULL;
    }

    return (*env)->NewDirectByteBuffer(
        env,
        data,
        (jlong)size
    );
}


/*
 * Desmapeia segmento.
 */
JNIEXPORT void JNICALL
Java_com_winlator_cmod_sysvshm_SysVSharedMemory_unmapSHMSegment(
    JNIEnv *env,
    jobject obj,
    jobject data,
    jlong size
)
{
    (void)obj;

    if (data == NULL || size <= 0) {
        return;
    }

    void *dataAddr =
        (*env)->GetDirectBufferAddress(
            env,
            data
        );

    if (dataAddr == NULL) {
        return;
    }

    munmap(
        dataAddr,
        (size_t)size
    );
}


/*
 * Retorna o tamanho máximo de memória virtual configurado.
 *
 * Java:
 *
 * long getGuestMemoryLimit();
 *
 * Resultado:
 * 27,917,874,176 bytes
 */
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_sysvshm_SysVSharedMemory_getGuestMemoryLimit(
    JNIEnv *env,
    jclass obj
)
{
    (void)env;
    (void)obj;

    return (jlong)GUEST_MEMORY_BYTES;
}
