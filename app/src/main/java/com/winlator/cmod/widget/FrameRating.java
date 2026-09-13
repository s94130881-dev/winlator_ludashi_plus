package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {

    private Context context;

    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;

    private final TextView tvFPS;
    private final TextView tvRenderer;
    private final TextView tvGPU;
    private final TextView tvRAM;

    private HashMap graphicsDriverConfig;

    private static final String PREFS = "winlator_hud";
    private static final String KEY_VIS = "hud_vis";

    private final SharedPreferences prefs;
    private boolean userEnabled = false;

    private String lastKnownRenderer = null;

    public FrameRating(Context context, HashMap graphicsDriverConfig) {
        this(context, graphicsDriverConfig, null);
    }

    public FrameRating(
            Context context,
            HashMap graphicsDriverConfig,
            AttributeSet attrs) {

        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(
            Context context,
            HashMap graphicsDriverConfig,
            AttributeSet attrs,
            int defStyleAttr) {

        super(context, attrs, defStyleAttr);

        this.context = context;

        prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );

        View view = LayoutInflater.from(context)
                .inflate(R.layout.frame_rating, this, false);

        tvFPS = view.findViewById(R.id.TVFPS);

        tvRenderer = view.findViewById(R.id.TVRenderer);
        tvRenderer.setText("Vulkan");

        tvGPU = view.findViewById(R.id.TVGPU);

        try {
            Object version = graphicsDriverConfig.get("version");

            if (version != null) {
                tvGPU.setText(
                        GPUInformation.getRenderer(
                                version.toString(),
                                context
                        )
                );
            }
        } catch (Exception ignored) {
            tvGPU.setText("Unknown GPU");
        }

        tvRAM = view.findViewById(R.id.TVRAM);

        this.graphicsDriverConfig = graphicsDriverConfig;

        addView(view);
    }

    /*
     * ---------------------------------------------------------
     * MEMÓRIA
     * ---------------------------------------------------------
     *
     * MemTotal      = RAM física disponível para o sistema
     * MemAvailable  = RAM atualmente disponível
     * SwapTotal     = tamanho total do swap/ZRAM
     * SwapFree      = swap livre
     *
     * O HUD mostra:
     *
     * RAM física usada + swap usada
     *
     * /
     *
     * Exemplo:
     *
     * 3.2 / 11.5 GB Used
     *
     * O total é:
     *
     * RAM física + Swap
     */

    private long[] readMeminfo() {

        long memTotal = -1;
        long memAvailable = -1;
        long swapTotal = -1;
        long swapFree = -1;

        try (
                BufferedReader r =
                        new BufferedReader(
                                new FileReader("/proc/meminfo")
                        )
        ) {

            String line;

            while ((line = r.readLine()) != null) {

                if (line.startsWith("MemTotal:")) {

                    memTotal = parseMeminfoKb(line);

                } else if (line.startsWith("MemAvailable:")) {

                    memAvailable = parseMeminfoKb(line);

                } else if (line.startsWith("SwapTotal:")) {

                    swapTotal = parseMeminfoKb(line);

                } else if (line.startsWith("SwapFree:")) {

                    swapFree = parseMeminfoKb(line);
                }
            }

        } catch (Exception ignored) {
        }

        return new long[] {
                memTotal,
                memAvailable,
                swapTotal,
                swapFree
        };
    }

    private long parseMeminfoKb(String line) {

        try {

            String[] parts =
                    line.trim().split("\\s+");

            return Long.parseLong(parts[1]);

        } catch (Exception e) {

            return -1;
        }
    }

    /*
     * RAM física + Swap total
     */
    private long getCombinedTotalKb() {

        long[] mem = readMeminfo();

        long ram = mem[0];
        long swap = mem[2];

        if (ram <= 0)
            return -1;

        if (swap < 0)
            swap = 0;

        return ram + swap;
    }

    /*
     * RAM usada + Swap usada
     */
    private long getCombinedUsedKb() {

        long[] mem = readMeminfo();

        long ramTotal = mem[0];
        long ramAvailable = mem[1];

        long swapTotal = mem[2];
        long swapFree = mem[3];

        if (ramTotal <= 0 || ramAvailable < 0)
            return -1;

        if (swapTotal < 0)
            swapTotal = 0;

        if (swapFree < 0)
            swapFree = 0;

        long ramUsed =
                ramTotal - ramAvailable;

        long swapUsed =
                swapTotal - swapFree;

        if (ramUsed < 0)
            ramUsed = 0;

        if (swapUsed < 0)
            swapUsed = 0;

        return ramUsed + swapUsed;
    }

    /*
     * Total formatado
     */
    private String getTotalRAM() {

        long totalKb =
                getCombinedTotalKb();

        if (totalKb <= 0)
            return "N/A";

        return StringUtils.formatBytes(
                totalKb * 1024L,
                false
        );
    }

    /*
     * Memória usada formatada
     */
    private String getAvailableRAM() {

        long usedKb =
                getCombinedUsedKb();

        if (usedKb < 0)
            return "N/A";

        return StringUtils.formatBytes(
                usedKb * 1024L,
                false
        );
    }

    public void setRenderer(String renderer) {

        lastKnownRenderer = renderer;

        tvRenderer.setText(renderer);
    }

    public void setGpuName(String gpuName) {

        tvGPU.setText(gpuName);
    }

    public void reset() {

        tvRenderer.setText(
                lastKnownRenderer != null
                        ? lastKnownRenderer
                        : "Vulkan"
        );

        try {

            Object version =
                    graphicsDriverConfig.get("version");

            if (version != null) {

                tvGPU.setText(
                        GPUInformation.getRenderer(
                                version.toString(),
                                context
                        )
                );
            }

        } catch (Exception ignored) {
        }
    }

    public boolean hasSavedPref() {

        return prefs.contains(KEY_VIS);
    }

    public boolean isSavedVisible() {

        return prefs.getBoolean(
                KEY_VIS,
                false
        );
    }

    public void enableByUser() {

        userEnabled = true;

        prefs.edit()
                .putBoolean(KEY_VIS, true)
                .apply();

        post(() ->
                setVisibility(View.VISIBLE)
        );
    }

    public void disableByUser() {

        disableByUser(true);
    }

    public void disableByUser(boolean savePrefs) {

        userEnabled = false;

        if (savePrefs) {

            prefs.edit()
                    .putBoolean(KEY_VIS, false)
                    .apply();
        }

        setVisibility(View.GONE);
    }

    public boolean isUserEnabled() {

        return userEnabled;
    }

    public void update() {

        if (!userEnabled)
            return;

        if (lastTime == 0)
            lastTime = SystemClock.elapsedRealtime();

        long time =
                SystemClock.elapsedRealtime();

        if (time >= lastTime + 500) {

            lastFPS =
                    ((float) (frameCount * 1000)
                            / (time - lastTime));

            post(this);

            lastTime = time;

            frameCount = 0;
        }

        frameCount++;
    }

    @Override
    public void run() {

        if (!userEnabled)
            return;

        if (getVisibility() == GONE)
            setVisibility(View.VISIBLE);

        tvFPS.setText(
                String.format(
                        Locale.ENGLISH,
                        "%.1f",
                        lastFPS
                )
        );

        String used =
                getAvailableRAM();

        String total =
                getTotalRAM();

        tvRAM.setText(
                used + " / " + total + " GB Used"
        );
    }
}
