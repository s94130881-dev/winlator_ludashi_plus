package com.winlator.cmod.container;

import java.io.File;

import com.winlator.cmod.core.FileUtils;

public class ContainerMemory {

    // Memória padrão: 8 GB
    public static final int DEFAULT_RAM_GB = 8;
    public static final int DEFAULT_RAM_MB = 8192;

    private final File containerDir;

    public ContainerMemory(File containerDir) {
        this.containerDir = containerDir;
    }

    public File getConfigFile() {
        return new File(containerDir, "ram.conf");
    }

    public void createDefaultConfig() {
        File config = getConfigFile();

        if (!config.exists()) {
            String data =
                    "RAM_GB=8\n" +
                    "RAM_MB=8192\n";

            FileUtils.writeString(config, data);
        }
    }

    public int getRamMB() {
        return DEFAULT_RAM_MB;
    }

    public int getRamGB() {
        return DEFAULT_RAM_GB;
    }
}
