package com.winlator.cmod.core;

public abstract class DefaultVersion {

    // Box64
    public static final String BOX64 = "0.4.2";
    public static final String WOWBOX64 = "0.4.2";

    // FEX
    public static final String FEXCORE = "2601";

    // Vulkan wrapper
    public static final String WRAPPER = "System";
    public static final String WRAPPER_ADRENO = "turnip26.2.0";

    // DXVK
    // Mali/Helio G99 -> versão mais antiga
    // Outras GPUs -> versão mais nova
    public static final String DXVK =
            isMali()
                    ? "1.10.3"
                    : "2.3.1";

    // D8VK
    public static final String D8VK = "1.0";

    // D3D12 -> Vulkan
    public static final String VKD3D = "2.6";

    private static boolean isMali() {
        String renderer = GPUInformation.getRenderer(null, null);

        return renderer != null
                && renderer.toLowerCase().contains("mali");
    }
}
