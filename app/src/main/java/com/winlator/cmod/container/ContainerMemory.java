package com.winlator.cmod.container;

import com.winlator.cmod.core.FileUtils;

import java.io.File;

public final class ContainerMemory {

    private ContainerMemory() {
    }

    // 8 GB = 8192 MB
    public static final int DEFAULT_RAM_GB = 8;
    public static final int DEFAULT_RAM_MB = 8192;

    private static final String CONFIG_FILE = "ram.conf";

    /**
     * Cria a configuração padrão de memória dentro do container.
     */
    public static void createDefaultConfig(File containerDir) {
        if (containerDir == null || !containerDir.isDirectory()) {
            return;
        }

        File config = new File(containerDir, CONFIG_FILE);

        if (config.exists()) {
            return;
        }

        String content =
                "# Winlator container memory configuration\n" +
                "# Default virtual memory target\n" +
                "RAM_GB=8\n" +
                "RAM_MB=8192\n";

        FileUtils.writeString(config, content);
    }

    /**
     * Lê o valor RAM_MB do ram.conf.
     */
    public static int getRamMB(File containerDir) {
        if (containerDir == null) {
            return DEFAULT_RAM_MB;
        }

        File config = new File(containerDir, CONFIG_FILE);

        if (!config.isFile()) {
            return DEFAULT_RAM_MB;
        }

        try {
            String content = FileUtils.readString(config);

            for (String line : content.split("\\r?\\n")) {
                line = line.trim();

                if (line.startsWith("RAM_MB=")) {
                    int value = Integer.parseInt(
                            line.substring("RAM_MB=".length()).trim()
                    );

                    if (value > 0) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return DEFAULT_RAM_MB;
    }

    public static int getRamGB(File containerDir) {
        return getRamMB(containerDir) / 1024;
    }
}
