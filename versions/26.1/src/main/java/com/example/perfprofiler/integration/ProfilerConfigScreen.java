package com.example.perfprofiler.integration;

import com.example.perfprofiler.config.ProfilerConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ProfilerConfigScreen extends Screen {
    private final Screen parent;

    public ProfilerConfigScreen(Screen parent) {
        super(Component.literal("Performance Profiler Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 40;

        addToggle(cx, y, "HUD Enabled", ProfilerConfig.INSTANCE.enabled, v -> {
            ProfilerConfig.INSTANCE.enabled = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show FPS", ProfilerConfig.INSTANCE.showFps, v -> {
            ProfilerConfig.INSTANCE.showFps = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Frame Time", ProfilerConfig.INSTANCE.showFrameTime, v -> {
            ProfilerConfig.INSTANCE.showFrameTime = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Memory", ProfilerConfig.INSTANCE.showMemory, v -> {
            ProfilerConfig.INSTANCE.showMemory = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Entities", ProfilerConfig.INSTANCE.showEntities, v -> {
            ProfilerConfig.INSTANCE.showEntities = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Particles", ProfilerConfig.INSTANCE.showParticles, v -> {
            ProfilerConfig.INSTANCE.showParticles = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Chunks", ProfilerConfig.INSTANCE.showChunks, v -> {
            ProfilerConfig.INSTANCE.showChunks = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Profile Hint", ProfilerConfig.INSTANCE.showProfileHint, v -> {
            ProfilerConfig.INSTANCE.showProfileHint = v;
            ProfilerConfig.save();
        });
        y += 24;
        addToggle(cx, y, "Show Background", ProfilerConfig.INSTANCE.showBackground, v -> {
            ProfilerConfig.INSTANCE.showBackground = v;
            ProfilerConfig.save();
        });
        y += 30;

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx - 100, y, 200, 20)
                .build());
    }

    private void addToggle(int cx, int y, String label, boolean value, java.util.function.Consumer<Boolean> onChange) {
        this.addRenderableWidget(CycleButton.onOffBuilder(value)
                .create(cx - 100, y, 200, 20, Component.literal(label),
                        (btn, v) -> onChange.accept(v)));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
