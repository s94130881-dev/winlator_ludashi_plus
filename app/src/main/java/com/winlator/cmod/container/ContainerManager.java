package com.winlator.cmod.container;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

public class ContainerManager {

    private static final String TAG = "ContainerManager";

    /*
     * Memória padrão do container.
     *
     * 8 GB = 8192 MB
     *
     * IMPORTANTE:
     * Este valor representa a configuração de memória virtual
     * usada pelo nosso sistema de configuração.
     * Não transforma RAM física em RAM adicional.
     */
    public static final int DEFAULT_RAM_GB = 8;
    public static final int DEFAULT_RAM_MB = 8192;

    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;

    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false;

    public ContainerManager(Context context) {
        this.context = context;

        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");

        if (!homeDir.exists()) {
            homeDir.mkdirs();
        }

        loadContainers();

        isInitialized = true;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    public Context getContext() {
        return context;
    }

    /**
     * Carrega todos os containers existentes.
     */
    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        try {
            File[] files = homeDir.listFiles();

            if (files == null) {
                return;
            }

            for (File file : files) {

                if (!file.isDirectory()) {
                    continue;
                }

                String fileName = file.getName();

                if (!fileName.startsWith(ImageFs.USER + "-")) {
                    continue;
                }

                String idString =
                        fileName.substring((ImageFs.USER + "-").length());

                int id;

                try {
                    id = Integer.parseInt(idString);
                }
                catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid container directory: " + fileName);
                    continue;
                }

                Container container =
                        new Container(id, this);

                container.setRootDir(file);

                File configFile =
                        container.getConfigFile();

                if (!configFile.isFile() ||
                        configFile.length() == 0) {
                    continue;
                }

                JSONObject data =
                        new JSONObject(
                                FileUtils.readString(configFile)
                        );

                container.loadData(data);

                /*
                 * Containers antigos podem não possuir ram.conf.
                 * Nesse caso criamos a configuração padrão de 8 GB.
                 */
                ensureMemoryConfig(file);

                containers.add(container);

                maxContainerId =
                        Math.max(maxContainerId, id);
            }

        }
        catch (JSONException |
               NullPointerException |
               SecurityException e) {

            Log.e(
                    TAG,
                    "Error loading containers",
                    e
            );
        }
    }

    /**
     * Cria o arquivo de configuração de memória.
     *
     * Arquivo:
     *
     * ram.conf
     *
     * Conteúdo:
     *
     * RAM_GB=8
     * RAM_MB=8192
     */
    private void createMemoryConfig(File containerDir) {

        if (containerDir == null ||
                !containerDir.isDirectory()) {
            return;
        }

        File configFile =
                new File(containerDir, "ram.conf");

        if (configFile.exists()) {
            return;
        }

        String config =
                "# Winlator memory configuration\n" +
                "# Default virtual memory target\n" +
                "RAM_GB=8\n" +
                "RAM_MB=8192\n";

        try {

            FileUtils.writeString(
                    configFile,
                    config
            );

            Log.i(
                    TAG,
                    "Created 8 GB memory configuration: "
                            + configFile.getAbsolutePath()
            );

        }
        catch (Exception e) {

            Log.e(
                    TAG,
                    "Unable to create ram.conf",
                    e
            );
        }
    }

    /**
     * Garante que o container tenha ram.conf.
     */
    private void ensureMemoryConfig(File containerDir) {

        File configFile =
                new File(containerDir, "ram.conf");

        if (!configFile.exists()) {
            createMemoryConfig(containerDir);
        }
    }

    /**
     * Retorna a memória configurada para um container.
     */
    public int getContainerRamMB(Container container) {

        if (container == null ||
                container.getRootDir() == null) {
            return DEFAULT_RAM_MB;
        }

        File configFile =
                new File(
                        container.getRootDir(),
                        "ram.conf"
                );

        if (!configFile.isFile()) {
            createMemoryConfig(
                    container.getRootDir()
            );

            return DEFAULT_RAM_MB;
        }

        try {

            String content =
                    FileUtils.readString(configFile);

            for (String line :
                    content.split("\\r?\\n")) {

                line = line.trim();

                if (line.startsWith("RAM_MB=")) {

                    String value =
                            line.substring(
                                    "RAM_MB=".length()
                            ).trim();

                    int ram =
                            Integer.parseInt(value);

                    if (ram > 0) {
                        return ram;
                    }
                }
            }

        }
        catch (Exception e) {

            Log.w(
                    TAG,
                    "Invalid ram.conf, using default 8192 MB"
            );
        }

        return DEFAULT_RAM_MB;
    }

    /**
     * Retorna a memória em GB.
     */
    public int getContainerRamGB(Container container) {

        return getContainerRamMB(container) / 1024;
    }

    /**
     * Define a memória do container.
     */
    public void setContainerRamMB(
            Container container,
            int ramMB
    ) {

        if (container == null ||
                container.getRootDir() == null) {
            return;
        }

        if (ramMB <= 0) {
            ramMB = DEFAULT_RAM_MB;
        }

        File configFile =
                new File(
                        container.getRootDir(),
                        "ram.conf"
                );

        String config =
                "# Winlator memory configuration\n" +
                "RAM_GB=" +
                (ramMB / 1024) +
                "\n" +
                "RAM_MB=" +
                ramMB +
                "\n";

        FileUtils.writeString(
                configFile,
                config
        );
    }

    /**
     * Ativa um container.
     */
    public void activateContainer(Container container) {

        if (container == null) {
            return;
        }

        container.setRootDir(
                new File(
                        homeDir,
                        ImageFs.USER +
                                "-" +
                                container.id
                )
        );

        /*
         * Garante que a configuração de memória exista.
         */
        ensureMemoryConfig(
                container.getRootDir()
        );

        WineThemeManager.getUserWallpaperFile(context);

        File file =
                new File(
                        homeDir,
                        ImageFs.USER
                );

        if (file.exists() &&
                !FileUtils.isSymlink(file) &&
                file.isDirectory()) {

            Log.w(
                    TAG,
                    "Repairing stale real xuser directory before activation"
            );

            if (!FileUtils.delete(file)) {

                Log.e(
                        TAG,
                        "Unable to remove stale xuser directory: "
                                + file.getAbsolutePath()
                );
            }

        }
        else if (file.exists() &&
                !file.delete()) {

            Log.e(
                    TAG,
                    "Unable to remove stale xuser alias: "
                            + file.getAbsolutePath()
            );
        }

        FileUtils.symlink(
                "./" +
                        ImageFs.USER +
                        "-" +
                        container.id,
                file.getPath()
        );

        if (!FileUtils.isSymlink(file)) {

            Log.e(
                    TAG,
                    "Unable to activate container: "
                            + "xuser alias was not created for container "
                            + container.id
            );
        }
    }

    public void createContainerAsync(
            final JSONObject data,
            ContentsManager contentsManager,
            Callback<Container> callback
    ) {

        final Handler handler =
                new Handler(Looper.getMainLooper());

        Executors
                .newSingleThreadExecutor()
                .execute(() -> {

                    final Container container =
                            createContainer(
                                    data,
                                    contentsManager
                            );

                    handler.post(
                            () -> callback.call(container)
                    );
                });
    }

    public void duplicateContainerAsync(
            Container container,
            Runnable callback
    ) {

        final Handler handler =
                new Handler(Looper.getMainLooper());

        Executors
                .newSingleThreadExecutor()
                .execute(() -> {

                    duplicateContainer(container);

                    handler.post(callback);
                });
    }

    public void removeContainerAsync(
            Container container,
            Runnable callback
    ) {

        final Handler handler =
                new Handler(Looper.getMainLooper());

        Executors
                .newSingleThreadExecutor()
                .execute(() -> {

                    removeContainer(container);

                    handler.post(callback);
                });
    }

    private int findNextContainerId() {

        int id =
                Math.max(
                        1,
                        maxContainerId + 1
                );

        while (
                new File(
                        homeDir,
                        ImageFs.USER + "-" + id
                ).exists()
        ) {

            id++;
        }

        return id;
    }

    /**
     * Cria um novo container.
     */
    private Container createContainer(
            JSONObject data,
            ContentsManager contentsManager
    ) {

        try {

            int id =
                    findNextContainerId();

            data.put("id", id);

            /*
             * Define 8 GB como padrão no JSON.
             *
             * Isso permite que outros componentes do projeto
             * tenham acesso ao valor.
             */
            if (!data.has("ramSize")) {
                data.put(
                        "ramSize",
                        DEFAULT_RAM_MB
                );
            }

            File containerDir =
                    new File(
                            homeDir,
                            ImageFs.USER +
                                    "-" +
                                    id
                    );

            if (!containerDir.mkdirs()) {

                Log.e(
                        TAG,
                        "Unable to create container directory: "
                                + containerDir
                );

                return null;
            }

            /*
             * Cria ram.conf imediatamente.
             */
            createMemoryConfig(containerDir);

            Container container =
                    new Container(
                            id,
                            this
                    );

            container.setRootDir(
                    containerDir
            );

            container.loadData(data);

            /*
             * Garante que o valor padrão continue disponível.
             */
            setContainerRamMB(
                    container,
                    DEFAULT_RAM_MB
            );

            container.setWineVersion(
                    data.getString("wineVersion")
            );

            /*
             * Extrai o ambiente Wine.
             */
            if (!extractContainerPatternFile(
                    container,
                    container.getWineVersion(),
                    contentsManager,
                    containerDir,
                    null
            )) {

                FileUtils.delete(
                        containerDir
                );

                return null;
            }

            /*
             * Salva a configuração principal.
             */
            container.saveData();

            /*
             * Garante novamente a configuração de memória
             * depois da criação do prefixo Wine.
             */
            ensureMemoryConfig(containerDir);

            maxContainerId =
                    Math.max(
                            maxContainerId,
                            id
                    );

            containers.add(container);

            Log.i(
                    TAG,
                    "Container " +
                            id +
                            " created with default RAM: 8192 MB"
            );

            return container;

        }
        catch (JSONException e) {

            Log.e(
                    TAG,
                    "Error creating container",
                    e
            );
        }

        return null;
    }

    /**
     * Duplica um container.
     */
    private void duplicateContainer(
            Container srcContainer
    ) {

        if (srcContainer == null) {
            return;
        }

        int id =
                findNextContainerId();

        File dstDir =
                new File(
                        homeDir,
                        ImageFs.USER +
                                "-" +
                                id
                );

        if (!dstDir.mkdirs()) {
            return;
        }

        if (!FileUtils.copy(
                srcContainer.getRootDir(),
                dstDir,
                file -> FileUtils.chmod(file, 0771)
        )) {

            FileUtils.delete(dstDir);
            return;
        }

        Container dstContainer =
                new Container(
                        id,
                        this
                );

        dstContainer.setRootDir(dstDir);

        dstContainer.setName(
                srcContainer.getName() +
                        " (" +
                        context.getString(R.string._copy) +
                        ")"
        );

        dstContainer.setScreenSize(
                srcContainer.getScreenSize()
        );

        dstContainer.setEnvVars(
                srcContainer.getEnvVars()
        );

        dstContainer.setCPUList(
                srcContainer.getCPUList()
        );

        dstContainer.setCPUListWoW64(
                srcContainer.getCPUListWoW64()
        );

        dstContainer.setGraphicsDriver(
                srcContainer.getGraphicsDriver()
        );

        dstContainer.setDXWrapper(
                srcContainer.getDXWrapper()
        );

        dstContainer.setDXWrapperConfig(
                srcContainer.getDXWrapperConfig()
        );

        dstContainer.setAudioDriver(
                srcContainer.getAudioDriver()
        );

        dstContainer.setWinComponents(
                srcContainer.getWinComponents()
        );

        dstContainer.setDrives(
                srcContainer.getDrives()
        );

        dstContainer.setShowFPS(
                srcContainer.isShowFPS()
        );

        dstContainer.setStartupSelection(
                srcContainer.getStartupSelection()
        );

        dstContainer.setBox64Preset(
                srcContainer.getBox64Preset()
        );

        dstContainer.setDesktopTheme(
                srcContainer.getDesktopTheme()
        );

        dstContainer.setWineVersion(
                srcContainer.getWineVersion()
        );

        /*
         * Garante que a cópia também tenha ram.conf.
         */
        ensureMemoryConfig(dstDir);

        dstContainer.saveData();

        maxContainerId =
                Math.max(
                        maxContainerId,
                        id
                );

        containers.add(dstContainer);
    }

    private void removeContainer(
            Container container
    ) {

        if (container == null) {
            return;
        }

        if (FileUtils.delete(
                container.getRootDir()
        )) {

            containers.remove(container);
        }
    }

    public ArrayList<Shortcut> loadShortcuts() {

        ArrayList<Shortcut> shortcuts =
                new ArrayList<>();

        for (Container container :
                containers) {

            File desktopDir =
                    container.getDesktopDir();

            ArrayList<File> files =
                    new ArrayList<>();

            if (desktopDir.exists()) {

                File[] desktopFiles =
                        desktopDir.listFiles();

                if (desktopFiles != null) {
                    files.addAll(
                            Arrays.asList(
                                    desktopFiles
                            )
                    );
                }
            }

            for (File file : files) {

                String fileName =
                        file.getName();

                if (fileName.endsWith(".lnk")) {

                    String filePath =
                            file.getPath();

                    File desktopFile =
                            new File(
                                    filePath.substring(
                                            0,
                                            filePath.lastIndexOf(".")
                                    ) +
                                            ".desktop"
                            );

                    if (!desktopFile.exists()) {

                        MSLink.createDesktopFile(
                                file,
                                context
                        );

                        shortcuts.add(
                                new Shortcut(
                                        container,
                                        desktopFile
                                )
                        );
                    }

                }
                else if (
                        fileName.endsWith(".desktop")
                ) {

                    shortcuts.add(
                            new Shortcut(
                                    container,
                                    file
                            )
                    );
                }
            }
        }

        shortcuts.sort(
                Comparator.comparing(
                        a -> a.name
                )
        );

        return shortcuts;
    }

    public int getNextContainerId() {
        return findNextContainerId();
    }

    public Container getContainerById(int id) {

        for (Container container :
                containers) {

            if (container.id == id) {
                return container;
            }
        }

        return null;
    }

    private void extractCommonDlls(
            WineInfo wineInfo,
            String srcName,
            String dstName,
            File containerDir,
            OnExtractFileListener onExtractFileListener
    ) throws JSONException {

        File srcDir =
                new File(
                        wineInfo.path +
                                "/lib/wine/" +
                                srcName
                );

        File staleIcuDll =
                new File(
                        containerDir,
                        ".wine/drive_c/windows/" +
                                dstName +
                                "/icu.dll"
                );

        if (staleIcuDll.exists() &&
                !FileUtils.delete(staleIcuDll)) {

            throw new JSONException(
                    "Could not remove incompatible " +
                            dstName +
                            "/icu.dll"
            );
        }

        File[] srcfiles =
                srcDir.listFiles(
                        file -> file.isFile()
                );

        if (srcfiles == null) {

            throw new JSONException(
                    "Missing Wine files"
            );
        }

        for (File file : srcfiles) {

            String dllName =
                    file.getName();

            if (dllName.equals("iexplore.exe") &&
                    wineInfo.isArm64EC() &&
                    srcName.equals("aarch64-windows")) {

                file =
                        new File(
                                wineInfo.path +
                                        "/lib/wine/" +
                                        "i386-windows/iexplore.exe"
                        );
            }

            if (dllName.equals("tabtip.exe") ||
                    dllName.equals("icu.dll")) {

                continue;
            }

            File dstFile =
                    new File(
                            containerDir,
                            ".wine/drive_c/windows/" +
                                    dstName +
                                    "/" +
                                    dllName
                    );

            if (dstFile.exists()) {
                continue;
            }

            if (onExtractFileListener != null) {

                dstFile =
                        onExtractFileListener.onExtractFile(
                                dstFile,
                                0
                        );

                if (dstFile == null) {
                    continue;
                }
            }

            FileUtils.copy(
                    file,
                    dstFile
            );
        }
    }

    public boolean extractContainerPatternFile(
            Container container,
            String wineVersion,
            ContentsManager contentsManager,
            File containerDir,
            OnExtractFileListener onExtractFileListener
    ) {

        WineInfo wineInfo =
                WineInfo.fromIdentifier(
                        context,
                        contentsManager,
                        wineVersion
                );

        if (wineInfo.path == null ||
                wineInfo.path.isEmpty()) {

            return false;
        }

        String containerPattern =
                wineVersion +
                        "_container_pattern.tzst";

        boolean result =
                TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        context,
                        containerPattern,
                        containerDir,
                        onExtractFileListener
                );

        if (!result) {

            File containerPatternFile =
                    new File(
                            wineInfo.path +
                                    "/prefixPack.txz"
                    );

            result =
                    TarCompressorUtils.extract(
                            TarCompressorUtils.Type.XZ,
                            containerPatternFile,
                            containerDir
                    );
        }

        if (result) {

            try {

                if (wineInfo.isArm64EC()) {

                    extractCommonDlls(
                            wineInfo,
                            "aarch64-windows",
                            "system32",
                            containerDir,
                            onExtractFileListener
                    );

                }
                else {

                    extractCommonDlls(
                            wineInfo,
                            "x86_64-windows",
                            "system32",
                            containerDir,
                            onExtractFileListener
                    );
                }

                extractCommonDlls(
                        wineInfo,
                        "i386-windows",
                        "syswow64",
                        containerDir,
                        onExtractFileListener
                );

            }
            catch (JSONException e) {

                return false;
            }
        }

        return result;
    }

    public Container getContainerForShortcut(
            Shortcut shortcut
    ) {

        for (Container container :
                containers) {

            if (container.id ==
                    shortcut.getContainerId()) {

                return container;
            }
        }

        return null;
    }

    private void runOnUiThread(
            Runnable action
    ) {

        new Handler(
                Looper.getMainLooper()
        ).post(action);
    }
}
