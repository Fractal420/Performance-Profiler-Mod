package com.example.perfprofiler.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProfilerConfig {
    public static final ProfilerConfig INSTANCE = new ProfilerConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("perfprofiler.json");

    public boolean enabled = true;
    public int hudX = 4;
    public int hudY = 4;
    public float scale = 1.0f;
    public boolean showFps = true;
    public boolean showFrameTime = true;
    public boolean showMemory = true;
    public boolean showEntities = true;
    public boolean showParticles = true;
    public boolean showChunks = true;
    public boolean showProfileHint = true;
    public boolean showBackground = true;
    public int backgroundColor = 0x80000000;
    public int textColor = 0xFFFFFFFF;
    public int warningColor = 0xFFFFAA00;
    public int criticalColor = 0xFFFF5555;

    public int fpsWarning = 45;
    public int fpsCritical = 25;
    public int memoryWarningPercent = 75;
    public int memoryCriticalPercent = 90;
    public int entityWarning = 500;
    public int entityCritical = 1500;
    public int particleWarning = 1000;
    public int particleCritical = 5000;

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                ProfilerConfig loaded = GSON.fromJson(json, ProfilerConfig.class);
                if (loaded != null) {
                    INSTANCE.enabled = loaded.enabled;
                    INSTANCE.hudX = loaded.hudX;
                    INSTANCE.hudY = loaded.hudY;
                    INSTANCE.scale = loaded.scale;
                    INSTANCE.showFps = loaded.showFps;
                    INSTANCE.showFrameTime = loaded.showFrameTime;
                    INSTANCE.showMemory = loaded.showMemory;
                    INSTANCE.showEntities = loaded.showEntities;
                    INSTANCE.showParticles = loaded.showParticles;
                    INSTANCE.showChunks = loaded.showChunks;
                    INSTANCE.showProfileHint = loaded.showProfileHint;
                    INSTANCE.showBackground = loaded.showBackground;
                    INSTANCE.backgroundColor = loaded.backgroundColor;
                    INSTANCE.textColor = loaded.textColor;
                    INSTANCE.warningColor = loaded.warningColor;
                    INSTANCE.criticalColor = loaded.criticalColor;
                    INSTANCE.fpsWarning = loaded.fpsWarning;
                    INSTANCE.fpsCritical = loaded.fpsCritical;
                    INSTANCE.memoryWarningPercent = loaded.memoryWarningPercent;
                    INSTANCE.memoryCriticalPercent = loaded.memoryCriticalPercent;
                    INSTANCE.entityWarning = loaded.entityWarning;
                    INSTANCE.entityCritical = loaded.entityCritical;
                    INSTANCE.particleWarning = loaded.particleWarning;
                    INSTANCE.particleCritical = loaded.particleCritical;
                }
            } catch (Exception ignored) {
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException ignored) {
        }
    }
}
