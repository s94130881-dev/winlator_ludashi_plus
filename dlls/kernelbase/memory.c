#include "config.h"

#include <stdlib.h>
#include <stdint.h>
#include <errno.h>

#include "wine/port.h"
#include "winbase.h"
#include "winternl.h"

#define WINLATOR_DEFAULT_RAM_MB 8192ULL
#define WINLATOR_MIN_RAM_MB     1024ULL
#define WINLATOR_MAX_RAM_MB     65536ULL

static ULONGLONG winlator_get_ram_mb(void)
{
    const char *env;
    char *end;
    unsigned long long value;

    env = getenv("WINLATOR_FAKE_RAM_MB");

    if (!env || !*env)
        return WINLATOR_DEFAULT_RAM_MB;

    errno = 0;
    value = strtoull(env, &end, 10);

    if (errno || end == env || *end != '\0')
        return WINLATOR_DEFAULT_RAM_MB;

    if (value < WINLATOR_MIN_RAM_MB ||
        value > WINLATOR_MAX_RAM_MB)
        return WINLATOR_DEFAULT_RAM_MB;

    return (ULONGLONG)value;
}

BOOL WINAPI DECLSPEC_HOTPATCH GlobalMemoryStatusEx(MEMORYSTATUSEX *status)
{
    ULONGLONG ram_mb;
    ULONGLONG total_phys;
    ULONGLONG avail_phys;

    if (!status)
    {
        SetLastError(ERROR_INVALID_PARAMETER);
        return FALSE;
    }

    ram_mb = winlator_get_ram_mb();

    /*
     * WINLATOR_FAKE_RAM_MB = 8192
     *
     * 8192 MB * 1024 * 1024
     * = 8589934592 bytes
     */
    total_phys = ram_mb * 1024ULL * 1024ULL;

    /*
     * Keep available memory below total memory.
     * This is a reported value, not real Android RAM.
     */
    avail_phys = total_phys / 2;

    status->dwLength = sizeof(*status);
    status->ullTotalPhys = total_phys;
    status->ullAvailPhys = avail_phys;

    status->ullTotalPageFile = total_phys;
    status->ullAvailPageFile = total_phys / 2;

    status->ullTotalVirtual = total_phys;
    status->ullAvailVirtual = total_phys / 2;

    status->ullAvailExtendedVirtual = 0;

    status->dwMemoryLoad =
        (DWORD)(((total_phys - avail_phys) * 100ULL) /
                total_phys);

    return TRUE;
}

BOOL WINAPI DECLSPEC_HOTPATCH GetPhysicallyInstalledSystemMemory(
        ULONGLONG *memory)
{
    if (!memory)
    {
        SetLastError(ERROR_INVALID_PARAMETER);
        return FALSE;
    }

    /*
     * Windows API returns this value in KB.
     *
     * 8192 MB = 8388608 KB
     */
    *memory = winlator_get_ram_mb() * 1024ULL;

    return TRUE;
}
