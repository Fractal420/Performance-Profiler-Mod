package com.example.perfprofiler;

import com.example.perfprofiler.config.ProfilerConfig;
import com.example.perfprofiler.hud.ProfilerHud;
import com.example.perfprofiler.integration.ProfilerConfigScreen;
import com.example.perfprofiler.profiler.ReportWriter;
import com.example.perfprofiler.profiler.SampleCollector;
import com.example.perfprofiler.util.ModResolver;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class PerformanceProfilerClient implements ClientModInitializer {
    public static final String MOD_ID = "perfprofiler";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping toggleHudKey;
    private static KeyMapping toggleProfileKey;
    private static KeyMapping writeReportKey;
    private static KeyMapping openConfigKey;

    private static boolean hudVisible = true;
    private static final SampleCollector SAMPLER = new SampleCollector();
    private static long lastFrameNs = 0;

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "perfprofiler")
    );

    @Override
    public void onInitializeClient() {
        ProfilerConfig.load();
        ModResolver.rebuild();

        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.perfprofiler.toggle_hud",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                CATEGORY
        ));
        toggleProfileKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.perfprofiler.toggle_profile",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                CATEGORY
        ));
        writeReportKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.perfprofiler.write_report",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                CATEGORY
        ));
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.perfprofiler.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                CATEGORY
        ));

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
                (graphics, deltaTracker) -> {
                    long now = System.nanoTime();
                    if (lastFrameNs != 0) {
                        long delta = now - lastFrameNs;
                        SAMPLER.onFrame(delta);
                        ProfilerHud.onFrame(delta);
                    }
                    lastFrameNs = now;
                    if (hudVisible && ProfilerConfig.INSTANCE.enabled) {
                        ProfilerHud.render(graphics, Minecraft.getInstance(), SAMPLER);
                    }
                });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleHudKey.consumeClick()) {
                hudVisible = !hudVisible;
                LOGGER.info("Performance Profiler HUD: {}", hudVisible ? "ON" : "OFF");
            }
            while (toggleProfileKey.consumeClick()) {
                if (SAMPLER.isRunning()) {
                    SAMPLER.stop();
                    LOGGER.info("Profiling stopped. Writing report…");
                    Path file = ReportWriter.writeReport(client, SAMPLER, "manual-stop");
                    if (file != null) LOGGER.info("Report saved to: {}", file.toAbsolutePath());
                    else LOGGER.warn("Failed to write report");
                } else {
                    SAMPLER.start();
                    LOGGER.info("Profiling started. Press F9 to stop and write report, or F10 for a snapshot.");
                }
            }
            while (writeReportKey.consumeClick()) {
                LOGGER.info("Writing snapshot report…");
                Path file = ReportWriter.writeReport(client, SAMPLER,
                        SAMPLER.isRunning() ? "snapshot-while-profiling" : "snapshot");
                if (file != null) LOGGER.info("Report saved to: {}", file.toAbsolutePath());
                else LOGGER.warn("Failed to write report");
            }
            while (openConfigKey.consumeClick()) {
                client.setScreen(new ProfilerConfigScreen(client.screen));
            }
        });

        LOGGER.info("Performance Profiler initialized");
        LOGGER.info("  F7  = open config");
        LOGGER.info("  F8  = toggle HUD");
        LOGGER.info("  F9  = start/stop profiling");
        LOGGER.info("  F10 = write report snapshot");
    }

    public static SampleCollector getSampler() {
        return SAMPLER;
    }

    public static boolean isHudVisible() {
        return hudVisible;
    }
}
