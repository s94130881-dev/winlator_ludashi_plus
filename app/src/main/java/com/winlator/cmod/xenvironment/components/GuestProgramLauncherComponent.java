package com.winlator.cmod.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Process;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.box64.Box64PresetManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.FrameGenManager;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;

public class GuestProgramLauncherComponent
        extends EnvironmentComponent {

    private static final String TAG =
            "GuestProgramLauncher";

    /*
     * ============================================================
     * DEFAULT VIRTUAL RAM
     * ============================================================
     *
     * Default:
     *
     *     8 GB
     *     8192 MB
     *
     * IMPORTANT:
     *
     * This DOES NOT create physical RAM.
     *
     * It tells the patched Wine runtime what amount of RAM
     * should be reported through Windows memory APIs.
     *
     * The actual physical memory available to Linux/Android
     * does not change.
     */

    private static final int DEFAULT_RAM_GB = 8;

    private static final int DEFAULT_RAM_MB =
            DEFAULT_RAM_GB * 1024;

    /*
     * Maximum supported fake/virtual RAM value.
     */
    private static final int MAX_RAM_MB =
            65536;

    /*
     * Minimum supported value.
     */
    private static final int MIN_RAM_MB =
            1024;

    private String guestExecutable;

    private static int pid = -1;

    private String[] bindingPaths;

    private EnvVars envVars;

    private WineInfo wineInfo;

    private String box64Preset =
            Box64Preset.COMPATIBILITY;

    private String fexcorePreset =
            FEXCorePreset.INTERMEDIATE;

    private Callback<Integer> terminationCallback;

    private static final Object lock =
            new Object();

    private final ContentsManager contentsManager;

    private final ContentProfile wineProfile;

    private Container container;

    private final Shortcut shortcut;

    /*
     * ============================================================
     * WINE INFO
     * ============================================================
     */

    public void setWineInfo(
            WineInfo wineInfo) {

        this.wineInfo = wineInfo;
    }

    public WineInfo getWineInfo() {
        return wineInfo;
    }

    /*
     * ============================================================
     * CONTAINER
     * ============================================================
     */

    public Container getContainer() {
        return container;
    }

    public void setContainer(
            Container container) {

        this.container = container;
    }

    /*
     * ============================================================
     * RUNTIME CONTENT
     * ============================================================
     */

    private ContentProfile resolveInstalledRuntimeProfile(
            ContentProfile.ContentType type,
            String version) {

        ContentProfile exact =
                contentsManager.getProfileByEntryName(
                        type + "-" + version
                );

        if (exact != null
                && exact.remoteUrl == null) {

            return exact;
        }

        ContentProfile bestMatch =
                null;

        for (ContentProfile profile :
                contentsManager.getInstalledProfiles(type)) {

            String entryName =
                    ContentsManager.getEntryName(profile);

            int separator =
                    entryName.indexOf('-');

            String versionId =
                    separator >= 0
                            ? entryName.substring(
                                    separator + 1
                            )
                            : profile.verName;

            if (version.equals(versionId)
                    || version.equals(profile.verName)) {

                if (bestMatch == null
                        || profile.verCode
                        > bestMatch.verCode) {

                    bestMatch = profile;
                }
            }
        }

        return bestMatch;
    }

    private boolean applyRuntimeContent(
            ContentProfile.ContentType type,
            String version,
            Context context,
            String bundledAsset,
            File destination) {

        ContentProfile profile =
                resolveInstalledRuntimeProfile(
                        type,
                        version
                );

        if (profile != null) {

            return contentsManager.applyContent(
                    profile
            );
        }

        return TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                bundledAsset,
                destination
        );
    }

    /*
     * ============================================================
     * BOX64
     * ============================================================
     */

    private void extractBox64Files() {

        ImageFs imageFs =
                environment.getImageFs();

        Context context =
                environment.getContext();

        String box64Version =
                container.getBox64Version();

        if (shortcut != null) {

            box64Version =
                    shortcut.getExtra(
                            "box64Version",
                            shortcut.container
                                    .getBox64Version()
                    );
        }

        Log.d(
                TAG,
                "box64Version: "
                        + box64Version
        );

        File rootDir =
                imageFs.getRootDir();

        if (!box64Version.equals(
                container.getExtra(
                        "box64Version"
                ))) {

            boolean applied =
                    applyRuntimeContent(
                            ContentProfile.ContentType
                                    .CONTENT_TYPE_BOX64,
                            box64Version,
                            context,
                            "box64/box64-"
                                    + box64Version
                                    + ".tzst",
                            rootDir
                    );

            if (applied) {

                container.putExtra(
                        "box64Version",
                        box64Version
                );

                container.saveData();

            } else {

                Log.e(
                        TAG,
                        "Unable to apply Box64 version "
                                + box64Version
                );
            }
        }

        File box64File =
                new File(
                        rootDir,
                        "/usr/bin/box64"
                );

        if (box64File.exists()) {

            FileUtils.chmod(
                    box64File,
                    0755
            );
        }
    }

    /*
     * ============================================================
     * ARM64EC / FEXCORE / WOWBOX64
     * ============================================================
     */

    private void extractEmulatorsDlls() {

        Context context =
                environment.getContext();

        File rootDir =
                environment.getImageFs()
                        .getRootDir();

        File system32dir =
                new File(
                        rootDir
                                + "/home/xuser/.wine/"
                                + "drive_c/windows/system32"
                );

        boolean containerDataChanged =
                false;

        String wowbox64Version =
                container.getBox64Version();

        String fexcoreVersion =
                container.getFEXCoreVersion();

        if (shortcut != null) {

            wowbox64Version =
                    shortcut.getExtra(
                            "box64Version",
                            shortcut.container
                                    .getBox64Version()
                    );

            fexcoreVersion =
                    shortcut.getExtra(
                            "fexcoreVersion",
                            shortcut.container
                                    .getFEXCoreVersion()
                    );
        }

        Log.d(
                TAG,
                "box64Version in use: "
                        + wowbox64Version
        );

        Log.d(
                TAG,
                "fexcoreVersion in use: "
                        + fexcoreVersion
        );

        /*
         * WOWBOX64
         */

        if (!wowbox64Version.equals(
                container.getExtra(
                        "box64Version"
                ))) {

            boolean applied =
                    applyRuntimeContent(
                            ContentProfile.ContentType
                                    .CONTENT_TYPE_WOWBOX64,
                            wowbox64Version,
                            context,
                            "wowbox64/wowbox64-"
                                    + wowbox64Version
                                    + ".tzst",
                            system32dir
                    );

            if (applied) {

                container.putExtra(
                        "box64Version",
                        wowbox64Version
                );

                containerDataChanged =
                        true;

            } else {

                Log.e(
                        TAG,
                        "Unable to apply WOWBox64 version "
                                + wowbox64Version
                );
            }
        }

        /*
         * FEXCORE
         */

        ContentProfile fexcoreProfile =
                resolveInstalledRuntimeProfile(
                        ContentProfile.ContentType
                                .CONTENT_TYPE_FEXCORE,
                        fexcoreVersion
                );

        boolean fexcoreFilesMissing =
                fexcoreProfile != null
                        && !contentsManager
                        .isContentApplied(
                                fexcoreProfile
                        );

        if (!fexcoreVersion.equals(
                container.getExtra(
                        "fexcoreVersion"
                ))
                || fexcoreFilesMissing) {

            if (fexcoreFilesMissing) {

                Log.w(
                        TAG,
                        "FEXCore files are missing or "
                                + "incomplete; reapplying "
                                + fexcoreVersion
                );
            }

            boolean applied =
                    applyRuntimeContent(
                            ContentProfile.ContentType
                                    .CONTENT_TYPE_FEXCORE,
                            fexcoreVersion,
                            context,
                            "fexcore/fexcore-"
                                    + fexcoreVersion
                                    + ".tzst",
                            system32dir
                    );

            if (applied) {

                container.putExtra(
                        "fexcoreVersion",
                        fexcoreVersion
                );

                containerDataChanged =
                        true;

            } else {

                Log.e(
                        TAG,
                        "Unable to apply FEXCore version "
                                + fexcoreVersion
                );
            }
        }

        if (containerDataChanged) {

            container.saveData();
        }
    }

    /*
     * ============================================================
     * CONSTRUCTOR
     * ============================================================
     */

    public GuestProgramLauncherComponent(
            ContentsManager contentsManager,
            ContentProfile wineProfile,
            Shortcut shortcut) {

        this.contentsManager =
                contentsManager;

        this.wineProfile =
                wineProfile;

        this.shortcut =
                shortcut;
    }

    /*
     * ============================================================
     * START
     * ============================================================
     */

    @Override
    public void start() {

        synchronized (lock) {

            if (wineInfo.isArm64EC()) {

                extractEmulatorsDlls();

            } else {

                extractBox64Files();
            }

            checkDependencies();

            pid =
                    execGuestProgram();
        }
    }

    /*
     * ============================================================
     * RAM CONFIGURATION
     * ============================================================
     *
     * Reads:
     *
     *     <container>/ram.conf
     *
     * Example:
     *
     *     RAM_GB=8
     *     RAM_MB=8192
     *
     * If the file does not exist:
     *
     *     8 GB
     *
     * is automatically used.
     */

    private int getConfiguredRamMB() {

        /*
         * No container.
         */

        if (container == null) {

            Log.w(
                    TAG,
                    "No container available; "
                            + "using default 8 GB"
            );

            return DEFAULT_RAM_MB;
        }

        /*
         * Container root.
         */

        File containerRoot =
                container.getRootDir();

        if (containerRoot == null) {

            Log.w(
                    TAG,
                    "Container root is null; "
                            + "using default 8 GB"
            );

            return DEFAULT_RAM_MB;
        }

        /*
         * RAM configuration.
         */

        File ramConfig =
                new File(
                        containerRoot,
                        "ram.conf"
                );

        /*
         * No configuration means 8 GB.
         */

        if (!ramConfig.exists()) {

            Log.w(
                    TAG,
                    "ram.conf does not exist; "
                            + "using default 8 GB"
            );

            return DEFAULT_RAM_MB;
        }

        int ramMB =
                DEFAULT_RAM_MB;

        try {

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new FileInputStream(
                                            ramConfig
                                    )
                            )
                    );

            String line;

            while ((line =
                    reader.readLine()) != null) {

                line =
                        line.trim();

                /*
                 * Preferred value.
                 */

                if (line.startsWith(
                        "RAM_MB="
                )) {

                    String value =
                            line.substring(
                                    "RAM_MB=".length()
                            ).trim();

                    ramMB =
                            Integer.parseInt(
                                    value
                            );

                    break;
                }

                /*
                 * Fallback if only RAM_GB exists.
                 */

                if (line.startsWith(
                        "RAM_GB="
                )) {

                    String value =
                            line.substring(
                                    "RAM_GB=".length()
                            ).trim();

                    int ramGB =
                            Integer.parseInt(
                                    value
                            );

                    ramMB =
                            ramGB * 1024;
                }
            }

            reader.close();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to read ram.conf",
                    e
            );

            ramMB =
                    DEFAULT_RAM_MB;
        }

        /*
         * Safety.
         */

        if (ramMB < MIN_RAM_MB) {

            ramMB =
                    MIN_RAM_MB;
        }

        if (ramMB > MAX_RAM_MB) {

            ramMB =
                    MAX_RAM_MB;
        }

        return ramMB;
    }

    /*
     * ============================================================
     * APPLY VIRTUAL RAM ENVIRONMENT
     * ============================================================
     *
     * This is the bridge:
     *
     * ContainerManager
     *        ↓
     * ram.conf
     *        ↓
     * GuestProgramLauncherComponent
     *        ↓
     * WINLATOR_FAKE_RAM_MB
     *        ↓
     * Wine
     */

    private void configureVirtualRam(
            EnvVars execEnvVars) {

        int ramMB =
                getConfiguredRamMB();

        int ramGB =
                ramMB / 1024;

        String ramValue =
                String.valueOf(ramMB);

        /*
         * ========================================================
         * MAIN VARIABLE
         * ========================================================
         *
         * This is the variable that the patched Wine
         * memory implementation must read.
         */

        execEnvVars.put(
                "WINLATOR_FAKE_RAM_MB",
                ramValue
        );

        /*
         * Additional Winlator variables.
         */

        execEnvVars.put(
                "WINLATOR_RAM_MB",
                ramValue
        );

        execEnvVars.put(
                "WINLATOR_RAM_GB",
                String.valueOf(ramGB)
        );

        execEnvVars.put(
                "WINLATOR_RAM_ENABLED",
                "1"
        );

        /*
         * Debug information.
         */

        Log.i(
                TAG,
                "================================"
        );

        Log.i(
                TAG,
                "WINLATOR VIRTUAL RAM"
        );

        Log.i(
                TAG,
                "RAM_GB = "
                        + ramGB
        );

        Log.i(
                TAG,
                "RAM_MB = "
                        + ramMB
        );

        Log.i(
                TAG,
                "WINLATOR_FAKE_RAM_MB = "
                        + ramValue
        );

        Log.i(
                TAG,
                "WINLATOR_RAM_ENABLED = 1"
        );

        Log.i(
                TAG,
                "================================"
        );
    }

    /*
     * ============================================================
     * DEPENDENCY CHECK
     * ============================================================
     */

    private String checkDependencies() {

        String curlPath =
                environment.getImageFs()
                        .getRootDir()
                        .getPath()
                        + "/usr/lib/libXau.so";

        String lddCommand =
                "ldd " + curlPath;

        StringBuilder output =
                new StringBuilder(
                        "Checking Curl dependencies...\n"
                );

        try {

            java.lang.Process process =
                    Runtime.getRuntime().exec(
                            lddCommand
                    );

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            BufferedReader errorReader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getErrorStream()
                            )
                    );

            String line;

            while ((line =
                    reader.readLine()) != null) {

                output.append(line)
                        .append("\n");
            }

            while ((line =
                    errorReader.readLine()) != null) {

                output.append(line)
                        .append("\n");
            }

            process.waitFor();

        } catch (Exception e) {

            output.append(
                    "Error running ldd: "
                            + e.getMessage()
            );
        }

        Log.d(
                "CurlDeps",
                output.toString()
        );

        return output.toString();
    }

    /*
     * ============================================================
     * STOP
     * ============================================================
     */

    @Override
    public void stop() {

        synchronized (lock) {

            if (pid != -1) {

                Process.killProcess(pid);

                pid = -1;
            }
        }
    }

    /*
     * ============================================================
     * LD_PRELOAD
     * ============================================================
     */

    private static String mergePreloadValue(
            String baseValue,
            String overrideValue) {

        if (overrideValue == null
                || overrideValue.isEmpty()) {

            return baseValue == null
                    ? ""
                    : baseValue;
        }

        if (baseValue == null
                || baseValue.isEmpty()) {

            return overrideValue;
        }

        if (overrideValue.equals(
                baseValue
        )) {

            return baseValue;
        }

        return baseValue
                + ":"
                + overrideValue;
    }

    private static String appendFirstExistingPreload(
            String ldPreload,
            File[] candidates) {

        for (File candidate :
                candidates) {

            if (candidate.exists()) {

                return mergePreloadValue(
                        ldPreload,
                        candidate.getAbsolutePath()
                );
            }
        }

        return ldPreload;
    }

    /*
     * ============================================================
     * GETTERS / SETTERS
     * ============================================================
     */

    public Callback<Integer>
    getTerminationCallback() {

        return terminationCallback;
    }

    public void setTerminationCallback(
            Callback<Integer>
                    terminationCallback) {

        this.terminationCallback =
                terminationCallback;
    }

    public String getGuestExecutable() {

        return guestExecutable;
    }

    public void setGuestExecutable(
            String guestExecutable) {

        this.guestExecutable =
                guestExecutable;
    }

    public String[] getBindingPaths() {

        return bindingPaths;
    }

    public void setBindingPaths(
            String[] bindingPaths) {

        this.bindingPaths =
                bindingPaths;
    }

    public EnvVars getEnvVars() {

        return envVars;
    }

    public void setEnvVars(
            EnvVars envVars) {

        this.envVars =
                envVars;
    }

    public String getBox64Preset() {

        return box64Preset;
    }

    public void setBox64Preset(
            String box64Preset) {

        this.box64Preset =
                box64Preset;
    }

    public void setFEXCorePreset(
            String fexcorePreset) {

        this.fexcorePreset =
                fexcorePreset;
    }

    /*
     * ============================================================
     * EXECUTE GUEST PROGRAM
     * ============================================================
     */

    private int execGuestProgram() {

        Context context =
                environment.getContext();

        ImageFs imageFs =
                environment.getImageFs();

        File rootDir =
                imageFs.getRootDir();

        SharedPreferences preferences =
                PreferenceManager
                        .getDefaultSharedPreferences(
                                context
                        );

        boolean enableBox64Logs =
                preferences.getBoolean(
                        "enable_box64_logs",
                        false
                );

        boolean openWithAndroidBrowser =
                preferences.getBoolean(
                        "open_with_android_browser",
                        false
                );

        boolean shareAndroidClipboard =
                preferences.getBoolean(
                        "share_android_clipboard",
                        false
                );

        /*
         * ========================================================
         * USER ENVIRONMENT SAFETY
         * ========================================================
         *
         * Prevents NullPointerException when envVars
         * has not yet been initialized.
         */

        if (this.envVars == null) {

            this.envVars =
                    new EnvVars();
        }

        if (openWithAndroidBrowser) {

            this.envVars.put(
                    "WINE_OPEN_WITH_ANDROID_BROWSER",
                    "1"
            );
        }

        if (shareAndroidClipboard) {

            this.envVars.put(
                    "WINE_FROM_ANDROID_CLIPBOARD",
                    "1"
            );

            this.envVars.put(
                    "WINE_TO_ANDROID_CLIPBOARD",
                    "1"
            );
        }

        /*
         * ========================================================
         * EXECUTION ENVIRONMENT
         * ========================================================
         */

        EnvVars execEnvVars =
                new EnvVars();

        /*
         * ========================================================
         * RAM
         * ========================================================
         *
         * Set early.
         */

        configureVirtualRam(
                execEnvVars
        );

        /*
         * ========================================================
         * BOX64
         * ========================================================
         */

        addBox64EnvVars(
                execEnvVars,
                enableBox64Logs
        );

        /*
         * ========================================================
         * FEXCORE
         * ========================================================
         */

        execEnvVars.putAll(
                FEXCorePresetManager.getEnvVars(
                        context,
                        fexcorePreset
                )
        );

        /*
         * ========================================================
         * GPU
         * ========================================================
         */

        String renderer =
                GPUInformation.getRenderer(
                        null,
                        null
                );

        if (renderer != null
                && renderer.contains(
                        "Mali"
                )) {

            execEnvVars.put(
                    "BOX64_MMAP32",
                    "0"
            );
        }

        String mmap32 =
                execEnvVars.get(
                        "BOX64_MMAP32"
                );

        if (mmap32 != null
                && mmap32.equals("1")
                && !wineInfo.isArm64EC()) {

            Log.d(
                    TAG,
                    "Disabling map memory placed"
            );

            execEnvVars.put(
                    "WRAPPER_DISABLE_PLACED",
                    "1"
            );
        }

        /*
         * ========================================================
         * LINUX / WINE ENVIRONMENT
         * ========================================================
         */

        execEnvVars.put(
                "HOME",
                imageFs.home_path
        );

        execEnvVars.put(
                "USER",
                ImageFs.USER
        );

        execEnvVars.put(
                "TMPDIR",
                rootDir.getPath()
                        + "/usr/tmp"
        );

        execEnvVars.put(
                "XDG_DATA_DIRS",
                rootDir.getPath()
                        + "/usr/share"
        );

        execEnvVars.put(
                "LD_LIBRARY_PATH",
                rootDir.getPath()
                        + "/usr/lib"
                        + ":/system/lib64"
        );

        execEnvVars.put(
                "XDG_CONFIG_DIRS",
                rootDir.getPath()
                        + "/usr/etc/xdg"
        );

        execEnvVars.put(
                "GST_PLUGIN_PATH",
                rootDir.getPath()
                        + "/usr/lib/gstreamer-1.0"
        );

        execEnvVars.put(
                "FONTCONFIG_PATH",
                rootDir.getPath()
                        + "/usr/etc/fonts"
        );

        execEnvVars.put(
                "VK_LAYER_PATH",
                rootDir.getPath()
                        + "/usr/share/vulkan/implicit_layer.d"
                        + ":"
                        + rootDir.getPath()
                        + "/usr/share/vulkan/explicit_layer.d"
        );

        execEnvVars.put(
                "WRAPPER_LAYER_PATH",
                rootDir.getPath()
                        + "/usr/lib"
        );

        execEnvVars.put(
                "WRAPPER_CACHE_PATH",
                rootDir.getPath()
                        + "/usr/var/cache"
        );

        execEnvVars.put(
                "WINE_NO_DUPLICATE_EXPLORER",
                "1"
        );

        execEnvVars.put(
                "PREFIX",
                rootDir.getPath()
                        + "/usr"
        );

        execEnvVars.put(
                "DISPLAY",
                ":0"
        );

        execEnvVars.put(
                "WINE_DISABLE_FULLSCREEN_HACK",
                "1"
        );

        execEnvVars.put(
                "GST_PLUGIN_FEATURE_RANK",
                "ximagesink:3000"
        );

        execEnvVars.put(
                "ALSA_CONFIG_PATH",
                rootDir.getPath()
                        + "/usr/share/alsa/alsa.conf"
                        + ":"
                        + rootDir.getPath()
                        + "/usr/etc/alsa/conf.d/"
                        + "android_aserver.conf"
        );

        execEnvVars.put(
                "ALSA_PLUGIN_DIR",
                rootDir.getPath()
                        + "/usr/lib/alsa-lib"
        );

        execEnvVars.put(
                "OPENSSL_CONF",
                rootDir.getPath()
                        + "/usr/etc/tls/openssl.cnf"
        );

        execEnvVars.put(
                "SSL_CERT_FILE",
                rootDir.getPath()
                        + "/usr/etc/tls/cert.pem"
        );

        execEnvVars.put(
                "SSL_CERT_DIR",
                rootDir.getPath()
                        + "/usr/etc/tls/certs"
        );

        execEnvVars.put(
                "WINE_X11FORCEGLX",
                "1"
        );

        execEnvVars.put(
                "WINE_GST_NO_GL",
                "1"
        );

        execEnvVars.put(
                "SteamGameId",
                "0"
        );

        execEnvVars.put(
                "PROTON_AUDIO_CONVERT",
                "0"
        );

        execEnvVars.put(
                "PROTON_VIDEO_CONVERT",
                "0"
        );

        execEnvVars.put(
                "PROTON_DEMUX",
                "0"
        );

        /*
         * ========================================================
         * WINE PATH
         * ========================================================
         */

        String winePath =
                imageFs.getWinePath()
                        + "/bin";

        Log.d(
                TAG,
                "WinePath is "
                        + winePath
        );

        execEnvVars.put(
                "PATH",
                winePath
                        + ":"
                        + rootDir.getPath()
                        + "/usr/bin"
        );

        /*
         * ========================================================
         * ANDROID SYSV SHM
         * ========================================================
         */

        execEnvVars.put(
                "ANDROID_SYSVSHM_SERVER",
                rootDir.getPath()
                        + UnixSocketConfig
                        .SYSVSHM_SERVER_PATH
        );

        /*
         * ========================================================
         * DNS
         * ========================================================
         */

        String primaryDNS =
                "8.8.4.4";

        ConnectivityManager
                connectivityManager =
                (ConnectivityManager)
                        context.getSystemService(
                                Service.CONNECTIVITY_SERVICE
                        );

        if (connectivityManager != null
                && connectivityManager
                        .getActiveNetwork() != null) {

            try {

                ArrayList<InetAddress>
                        dnsServers =
                        new ArrayList<>(
                                connectivityManager
                                        .getLinkProperties(
                                                connectivityManager
                                                        .getActiveNetwork()
                                        )
                                        .getDnsServers()
                        );

                if (!dnsServers.isEmpty()) {

                    primaryDNS =
                            dnsServers
                                    .get(0)
                                    .toString()
                                    .substring(1);
                }

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Unable to read DNS",
                        e
                );
            }
        }

        execEnvVars.put(
                "ANDROID_RESOLV_DNS",
                primaryDNS
        );

        execEnvVars.put(
                "WINE_NEW_NDIS",
                "1"
        );

        /*
         * ========================================================
         * LD_PRELOAD
         * ========================================================
         */

        String ld_preload =
                "";

        File sysvshm =
                new File(
                        imageFs.getLibDir(),
                        "libandroid-sysvshm.so"
                );

        if (sysvshm.exists()) {

            ld_preload =
                    sysvshm.getAbsolutePath();
        }

        /*
         * ========================================================
         * FAKE INPUT
         * ========================================================
         */

        File fakeinputDest =
                new File(
                        imageFs.getLibDir(),
                        "libfakeinput.so"
                );

        String nativeLibDir =
                environment
                        .getContext()
                        .getApplicationInfo()
                        .nativeLibraryDir;

        File fakeinputSrc =
                new File(
                        nativeLibDir,
                        "libfakeinput.so"
                );

        Log.d(
                TAG,
                "nativeLibDir: "
                        + nativeLibDir
        );

        if (fakeinputSrc.exists()) {

            try {

                FileUtils.copy(
                        fakeinputSrc,
                        fakeinputDest
                );

                Log.d(
                        TAG,
                        "Copied libfakeinput.so"
                );

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Failed to copy libfakeinput.so",
                        e
                );
            }

        } else {

            Log.e(
                    TAG,
                    "libfakeinput.so NOT FOUND: "
                            + fakeinputSrc
            );
        }

        if (fakeinputDest.exists()) {

            ld_preload =
                    mergePreloadValue(
                            ld_preload,
                            fakeinputDest
                                    .getAbsolutePath()
                    );
        }

        /*
         * ========================================================
         * JPEG
         * ========================================================
         */

        File[] jpegCandidates = {

                new File(
                        "/system/lib64/libjpeg.so"
                ),

                new File(
                        "/system_ext/lib64/libjpeg.so"
                )
        };

        ld_preload =
                appendFirstExistingPreload(
                        ld_preload,
                        jpegCandidates
                );

        /*
         * ========================================================
         * CRYPTO
         * ========================================================
         */

        File[] cryptoCandidates = {

                new File(
                        "/system/lib64/libcrypto.so"
                ),

                new File(
                        "/system_ext/lib64/libcrypto.so"
                ),

                new File(
                        imageFs.getLibDir(),
                        "libcrypto.so.3"
                )
        };

        ld_preload =
                appendFirstExistingPreload(
                        ld_preload,
                        cryptoCandidates
                );

        /*
         * ========================================================
         * FAKE EVDEV
         * ========================================================
         */

        File devInputDir =
                new File(
                        imageFs.getRootDir(),
                        "dev/input"
                );

        if (!devInputDir.exists()) {

            devInputDir.mkdirs();
        }

        File event0 =
                new File(
                        devInputDir,
                        "event0"
                );

        if (!event0.exists()) {

            try {

                event0.createNewFile();

            } catch (Exception ignored) {
            }
        }

        execEnvVars.put(
                "FAKE_EVDEV_DIR",
                devInputDir.getAbsolutePath()
        );

        execEnvVars.put(
                "FAKE_EVDEV_VIBRATION",
                "1"
        );

        execEnvVars.put(
                "LD_PRELOAD",
                ld_preload
        );

        /*
         * ========================================================
         * USER ENVIRONMENT
         * ========================================================
         */

        if (this.envVars != null) {

            if (this.envVars.has(
                    "MANGOHUD"
            )) {

                this.envVars.remove(
                        "MANGOHUD"
                );
            }

            if (this.envVars.has(
                    "MANGOHUD_CONFIG"
            )) {

                this.envVars.remove(
                        "MANGOHUD_CONFIG"
                );
            }

            execEnvVars.putAll(
                    this.envVars
            );
        }

        /*
         * ========================================================
         * FORCE RAM AFTER USER VARIABLES
         * ========================================================
         *
         * This is intentional.
         *
         * It prevents a custom container environment from
         * accidentally replacing the RAM value.
         */

        configureVirtualRam(
                execEnvVars
        );

        /*
         * ========================================================
         * DISPLAYX
         * ========================================================
         */

        boolean useDisplayX =
                shortcut != null
                        ? shortcut.getUseDisplayX()
                        : container != null
                        && container.getUseDisplayX();

        boolean trueDisplayX =
                shortcut != null
                        ? shortcut.getTrueDisplayX()
                        : container != null
                        && container.getTrueDisplayX();

        String surfaceFormat =
                shortcut != null
                        ? shortcut.getSurfaceFormat()
                        : container != null
                        ? container.getSurfaceFormat()
                        : "rgba8";

        execEnvVars.put(
                "WRAPPER_SURFACE_FORMAT",
                surfaceFormat
        );

        if (useDisplayX) {

            execEnvVars.put(
                    "DISPLAYX_SURFACE_FORMAT",
                    surfaceFormat
            );

        } else {

            execEnvVars.remove(
                    "DISPLAYX_SURFACE_FORMAT"
            );
        }

        final String displayXLayer =
                "VK_LAYER_DISPLAYX_display_x";

        String enabledLayers =
                execEnvVars.get(
                        "VK_INSTANCE_LAYERS"
                );

        if (useDisplayX
                && trueDisplayX) {

            execEnvVars.put(
                    "VK_INSTANCE_LAYERS",
                    displayXLayer
            );

        } else {

            StringBuilder filteredLayers =
                    new StringBuilder();

            if (enabledLayers != null
                    && !enabledLayers.isEmpty()) {

                for (String layer :
                        enabledLayers.split(":")) {

                    if (layer.isEmpty()
                            || layer.equals(
                                    displayXLayer
                            )) {

                        continue;
                    }

                    if (filteredLayers.length()
                            > 0) {

                        filteredLayers.append(
                                ':'
                        );
                    }

                    filteredLayers.append(
                            layer
                    );
                }
            }

            if (filteredLayers.length()
                    > 0) {

                execEnvVars.put(
                        "VK_INSTANCE_LAYERS",
                        filteredLayers.toString()
                );

            } else {

                execEnvVars.remove(
                        "VK_INSTANCE_LAYERS"
                );
            }
        }

        /*
         * ========================================================
         * FRAME GENERATION
         * ========================================================
         */

        FrameGenManager.applyLaunchEnv(
                execEnvVars
        );

        /*
         * ========================================================
         * EMULATOR
         * ========================================================
         */

        String emulator =
                container.getEmulator();

        if (shortcut != null) {

            emulator =
                    shortcut.getExtra(
                            "emulator",
                            container.getEmulator()
                    );
        }

        /*
         * ========================================================
         * WINE COMMAND
         * ========================================================
         */

        String command =
                "";

        String overriddenCommand =
                execEnvVars.get(
                        "GUEST_PROGRAM_LAUNCHER_COMMAND"
                );

        if (overriddenCommand != null
                && !overriddenCommand.isEmpty()) {

            String[] parts =
                    overriddenCommand.split(";");

            for (String part :
                    parts) {

                command +=
                        part + " ";
            }

            command =
                    command.trim();

        } else {

            if (wineInfo.isArm64EC()) {

                command =
                        winePath
                                + "/"
                                + guestExecutable;

                if (emulator != null
                        && emulator
                                .toLowerCase()
                                .equals(
                                        "fexcore"
                                )) {

                    execEnvVars.put(
                            "HODLL",
                            "libwow64fex.dll"
                    );

                } else {

                    execEnvVars.put(
                            "HODLL",
                            "wowbox64.dll"
                    );
                }

            } else {

                command =
                        imageFs.getBinDir()
                                + "/box64 "
                                + guestExecutable;
            }
        }

        /*
         * ========================================================
         * BOX64 PERMISSIONS
         * ========================================================
         */

        File box64File =
                new File(
                        rootDir,
                        "/usr/bin/box64"
                );

        if (box64File.exists()) {

            FileUtils.chmod(
                    box64File,
                    0755
            );
        }

        /*
         * ========================================================
         * FINAL DEBUG
         * ========================================================
         */

        int finalRamMB =
                getConfiguredRamMB();

        int finalRamGB =
                finalRamMB / 1024;

        Log.i(
                TAG,
                "================================"
        );

        Log.i(
                TAG,
                "Launching Windows program"
        );

        Log.i(
                TAG,
                "Command: "
                        + command
        );

        Log.i(
                TAG,
                "RAM configured: "
                        + finalRamGB
                        + " GB"
        );

        Log.i(
                TAG,
                "RAM configured: "
                        + finalRamMB
                        + " MB"
        );

        Log.i(
                TAG,
                "WINLATOR_FAKE_RAM_MB="
                        + execEnvVars.get(
                                "WINLATOR_FAKE_RAM_MB"
                        )
        );

        Log.i(
                TAG,
                "WINLATOR_RAM_MB="
                        + execEnvVars.get(
                                "WINLATOR_RAM_MB"
                        )
        );

        Log.i(
                TAG,
                "WINLATOR_RAM_GB="
                        + execEnvVars.get(
                                "WINLATOR_RAM_GB"
                        )
        );

        Log.i(
                TAG,
                "WINLATOR_RAM_ENABLED="
                        + execEnvVars.get(
                                "WINLATOR_RAM_ENABLED"
                        )
        );

        Log.i(
                TAG,
                "================================"
        );

        /*
         * ========================================================
         * START WINE / BOX64
         * ========================================================
         */

        return ProcessHelper.exec(
                command,
                execEnvVars.toStringArray(),
                rootDir,
                (status) -> {

                    synchronized (lock) {

                        pid = -1;
                    }

                    if (terminationCallback != null) {

                        terminationCallback.call(
                                status
                        );
                    }
                }
        );
    }

    /*
     * ============================================================
     * BOX64 ENVIRONMENT
     * ============================================================
     */

    private void addBox64EnvVars(
            EnvVars envVars,
            boolean enableLogs) {

        envVars.put(
                "BOX64_NOBANNER",
                ProcessHelper.PRINT_DEBUG
                        && enableLogs
                        ? "0"
                        : "1"
        );

        envVars.put(
                "BOX64_DYNAREC",
                "1"
        );

        if (enableLogs) {

            envVars.put(
                    "BOX64_LOG",
                    "1"
            );

            envVars.put(
                    "BOX64_DYNAREC_MISSING",
                    "1"
            );
        }

        envVars.putAll(
                Box64PresetManager.getEnvVars(
                        "box64",
                        environment.getContext(),
                        box64Preset
                )
        );

        envVars.put(
                "BOX64_X11GLX",
                "1"
        );

        envVars.put(
                "BOX64_NORCFILES",
                "1"
        );
    }

    /*
     * ============================================================
     * PROCESS CONTROL
     * ============================================================
     */

    public void suspendProcess() {

        synchronized (lock) {

            if (pid != -1) {

                ProcessHelper.suspendProcess(
                        pid
                );
            }
        }
    }

    public void resumeProcess() {

        synchronized (lock) {

            if (pid != -1) {

                ProcessHelper.resumeProcess(
                        pid
                );
            }
        }
    }
            }
