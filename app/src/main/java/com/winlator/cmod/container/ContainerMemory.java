package com.winlator.cmod.container;

import com.winlator.cmod.core.FileUtils;

import java.io.File;

public final class ContainerMemory {

private ContainerMemory() {
    // Classe utilitária
}

/*
 * ============================================================
 * CONFIGURAÇÃO PADRÃO
 * ============================================================
 *
 * 8 GB = 8192 MB
 *
 * IMPORTANTE:
 * Este valor representa a memória reportada/configurada para
 * o ambiente Wine/Windows.
 *
 * Não transforma RAM física do Android em 8 GB reais.
 */

public static final int DEFAULT_RAM_GB = 8;
public static final int DEFAULT_RAM_MB = 8192;

/*
 * Limites permitidos.
 */
public static final int MIN_RAM_GB = 1;
public static final int MAX_RAM_GB = 64;

public static final int MIN_RAM_MB =
        MIN_RAM_GB * 1024;

public static final int MAX_RAM_MB =
        MAX_RAM_GB * 1024;

/*
 * Arquivo de configuração dentro do container.
 */
private static final String CONFIG_FILE = "ram.conf";


/*
 * ============================================================
 * CONFIGURAÇÃO PADRÃO
 * ============================================================
 */

/**
 * Cria ram.conf com 8 GB caso o arquivo ainda não exista.
 */
public static void createDefaultConfig(File containerDir) {

    if (containerDir == null ||
            !containerDir.isDirectory()) {
        return;
    }

    File config =
            new File(
                    containerDir,
                    CONFIG_FILE
            );

    /*
     * Não sobrescrever uma configuração existente.
     */
    if (config.exists()) {
        return;
    }

    writeConfig(
            config,
            DEFAULT_RAM_MB
    );
}


/*
 * ============================================================
 * LEITURA
 * ============================================================
 */

/**
 * Retorna a RAM configurada em MB.
 *
 * Prioridade:
 *
 * 1. RAM_MB
 * 2. RAM_GB
 * 3. 8192 MB
 */
public static int getRamMB(File containerDir) {

    if (containerDir == null) {
        return DEFAULT_RAM_MB;
    }

    File config =
            new File(
                    containerDir,
                    CONFIG_FILE
            );

    if (!config.isFile()) {

        createDefaultConfig(
                containerDir
        );

        return DEFAULT_RAM_MB;
    }

    try {

        String content =
                FileUtils.readString(config);

        if (content == null ||
                content.trim().isEmpty()) {

            return DEFAULT_RAM_MB;
        }

        /*
         * Primeiro tenta RAM_MB.
         */
        Integer ramMB =
                readValue(
                        content,
                        "RAM_MB"
                );

        if (ramMB != null) {

            return clampRAM(
                    ramMB
            );
        }

        /*
         * Depois tenta RAM_GB.
         */
        Integer ramGB =
                readValue(
                        content,
                        "RAM_GB"
                );

        if (ramGB != null) {

            long mb =
                    (long) ramGB * 1024L;

            if (mb >= MIN_RAM_MB &&
                    mb <= MAX_RAM_MB) {

                return (int) mb;
            }
        }

    }
    catch (Exception ignored) {
        // Usa o padrão abaixo.
    }

    return DEFAULT_RAM_MB;
}


/**
 * Retorna a RAM configurada em GB.
 */
public static int getRamGB(File containerDir) {

    return getRamMB(
            containerDir
    ) / 1024;
}


/*
 * ============================================================
 * ESCRITA
 * ============================================================
 */

/**
 * Define a RAM do container em MB.
 */
public static void setRamMB(
        File containerDir,
        int ramMB
) {

    if (containerDir == null) {
        return;
    }

    if (!containerDir.isDirectory()) {
        return;
    }

    ramMB =
            clampRAM(
                    ramMB
            );

    File config =
            new File(
                    containerDir,
                    CONFIG_FILE
            );

    writeConfig(
            config,
            ramMB
    );
}


/**
 * Define a RAM do container em GB.
 */
public static void setRamGB(
        File containerDir,
        int ramGB
) {

    if (ramGB < MIN_RAM_GB) {
        ramGB = MIN_RAM_GB;
    }

    if (ramGB > MAX_RAM_GB) {
        ramGB = MAX_RAM_GB;
    }

    setRamMB(
            containerDir,
            ramGB * 1024
    );
}


/*
 * ============================================================
 * UTILITÁRIOS
 * ============================================================
 */

/**
 * Mantém a RAM dentro dos limites permitidos.
 */
public static int clampRAM(int ramMB) {

    if (ramMB < MIN_RAM_MB) {
        return MIN_RAM_MB;
    }

    if (ramMB > MAX_RAM_MB) {
        return MAX_RAM_MB;
    }

    return ramMB;
}


/**
 * Lê uma propriedade numérica do ram.conf.
 */
private static Integer readValue(
        String content,
        String key
) {

    if (content == null ||
            key == null) {
        return null;
    }

    String[] lines =
            content.split(
                    "\\r?\\n"
            );

    for (String line : lines) {

        if (line == null) {
            continue;
        }

        line =
                line.trim();

        /*
         * Ignora comentários.
         */
        if (line.isEmpty() ||
                line.startsWith("#")) {
            continue;
        }

        String prefix =
                key + "=";

        if (!line.startsWith(prefix)) {
            continue;
        }

        String value =
                line.substring(
                        prefix.length()
                ).trim();

        try {

            return Integer.parseInt(
                    value
            );

        }
        catch (NumberFormatException ignored) {

            return null;
        }
    }

    return null;
}


/**
 * Escreve um ram.conf completo.
 */
private static void writeConfig(
        File config,
        int ramMB
) {

    if (config == null) {
        return;
    }

    ramMB =
            clampRAM(
                    ramMB
            );

    int ramGB =
            ramMB / 1024;

    String content =
            "# Winlator container memory configuration\n" +
            "# Virtual memory target reported to Wine\n" +
            "# This does not increase physical Android RAM\n" +
            "RAM_GB=" +
            ramGB +
            "\n" +
            "RAM_MB=" +
            ramMB +
            "\n";

    try {

        FileUtils.writeString(
                config,
                content
        );

    }
    catch (Exception ignored) {
        // Não derruba o container caso a gravação falhe.
    }
}


/**
 * Retorna o caminho do arquivo ram.conf.
 */
public static File getConfigFile(
        File containerDir
) {

    if (containerDir == null) {
        return null;
    }

    return new File(
            containerDir,
            CONFIG_FILE
    );
}


/**
 * Verifica se o container possui uma configuração válida.
 */
public static boolean hasValidConfig(
        File containerDir
) {

    if (containerDir == null ||
            !containerDir.isDirectory()) {
        return false;
    }

    File config =
            getConfigFile(
                    containerDir
            );

    if (config == null ||
            !config.isFile()) {
        return false;
    }

    int ram =
            getRamMB(
                    containerDir
            );

    return ram >= MIN_RAM_MB &&
            ram <= MAX_RAM_MB;
}


/**
 * Garante que o container possua uma configuração válida.
 *
 * Se não existir, cria 8 GB.
 */
public static void ensureConfig(
        File containerDir
) {

    if (containerDir == null ||
            !containerDir.isDirectory()) {
        return;
    }

    File config =
            getConfigFile(
                    containerDir
            );

    if (config == null ||
            !config.isFile()) {

        createDefaultConfig(
                containerDir
        );
    }
}

}
