package com.example.perfprofiler.hud;

import com.example.perfprofiler.config.ProfilerConfig;
import com.example.perfprofiler.profiler.SampleCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ProfilerHud {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    private static final int FPS_SAMPLES = 90;
    private static final long[] frameTimesNs = new long[FPS_SAMPLES];
    private static int frameIndex = 0;
    private static boolean samplesFilled = false;

    private ProfilerHud() {
    }

    public static void onFrame(long frameDurationNs) {
        frameTimesNs[frameIndex] = frameDurationNs;
        frameIndex = (frameIndex + 1) % FPS_SAMPLES;
        if (frameIndex == 0) samplesFilled = true;
    }

    private static double getAverageFps() {
        int count = samplesFilled ? FPS_SAMPLES : Math.max(frameIndex, 1);
        long total = 0;
        for (int i = 0; i < count; i++) total += frameTimesNs[i];
        double avgNs = (double) total / count;
        return avgNs > 0 ? 1_000_000_000.0 / avgNs : 0;
    }

    private static double getAverageFrameTimeMs() {
        int count = samplesFilled ? FPS_SAMPLES : Math.max(frameIndex, 1);
        long total = 0;
        for (int i = 0; i < count; i++) total += frameTimesNs[i];
        return (total / (double) count) / 1_000_000.0;
    }

    public static void render(GuiGraphicsExtractor graphics, Minecraft client, SampleCollector sampler) {
        if (isHideGui(client)) return;

        ProfilerConfig cfg = ProfilerConfig.INSTANCE;
        Font font = client.font;
        List<Line> lines = new ArrayList<>();

        int fps = client.getFps();
        double avgFps = getAverageFps();
        double frameMs = getAverageFrameTimeMs();

        if (cfg.showFps) {
            int color = colorForThreshold(fps, cfg.fpsCritical, cfg.fpsWarning, true);
            lines.add(new Line(String.format("FPS: %d (avg %.1f)", fps, avgFps), color));
        }
        if (cfg.showFrameTime) {
            int color = colorForThreshold(frameMs, 40.0, 22.0, false);
            lines.add(new Line(String.format("Frame: %.2f ms", frameMs), color));
        }

        if (cfg.showMemory) {
            MemoryUsage heap = MEMORY.getHeapMemoryUsage();
            long used = heap.getUsed();
            long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
            double pct = max > 0 ? (used * 100.0 / max) : 0;
            int color = colorForThreshold(pct, cfg.memoryCriticalPercent, cfg.memoryWarningPercent, false);
            lines.add(new Line(String.format("Mem: %s / %s (%.0f%%)",
                    formatBytes(used), formatBytes(max), pct), color));
        }

        Level level = client.level;
        if (level != null) {
            if (cfg.showEntities) {
                int entities = countEntities(level);
                int color = colorForThreshold(entities, cfg.entityCritical, cfg.entityWarning, false);
                lines.add(new Line("Entities: " + entities, color));
            }

            if (cfg.showParticles) {
                int particles = parseParticleCount(invokeCountParticles(client));
                int color = colorForThreshold(particles, cfg.particleCritical, cfg.particleWarning, false);
                lines.add(new Line("Particles: " + particles, color));
            }

            if (cfg.showChunks) {
                try {
                    int chunks = countRenderedChunks(client);
                    int loaded = level.getChunkSource().getLoadedChunksCount();
                    lines.add(new Line(String.format("Chunks: %d / %d", chunks, loaded), withAlpha(cfg.textColor)));
                } catch (Throwable t) {
                    lines.add(new Line("Chunks: ?", withAlpha(cfg.textColor)));
                }
            }
        }

        if (sampler.isRunning()) {
            lines.add(new Line(String.format("PROFILING… samples=%d  (F9=stop)", sampler.getTotalSamples()), 0xFF55FF55));
        } else if (cfg.showProfileHint) {
            lines.add(new Line("F9=profile  F10=report", 0xFFAAAAAA));
        }

        if (lines.isEmpty()) return;

        float scale = cfg.scale;
        int lineHeight = Mth.ceil(font.lineHeight * scale) + 1;
        int maxWidth = 0;
        for (Line l : lines) {
            maxWidth = Math.max(maxWidth, font.width(l.text));
        }
        int boxW = Mth.ceil(maxWidth * scale) + 8;
        int boxH = lines.size() * lineHeight + 4;

        try {
            graphics.pose().pushMatrix();
            graphics.pose().translate(cfg.hudX, cfg.hudY);
            graphics.pose().scale(scale, scale);

            if (cfg.showBackground) {
                graphics.fill(0, 0, (int) (boxW / scale), (int) (boxH / scale), cfg.backgroundColor);
            }

            int y = 2;
            for (Line l : lines) {
                graphics.text(font, Component.literal(l.text), 4, y, l.color, true);
                y += font.lineHeight + 1;
            }

            graphics.pose().popMatrix();
        } catch (Throwable t) {
            try {
                graphics.text(font, Component.literal("Profiler HUD error"), 4, 4, 0xFFFF5555, true);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isHideGui(Minecraft client) {
        try {
            Object options = client.options;
            try {
                Object hide = options.getClass().getField("hideGui").get(options);
                if (hide instanceof Boolean b) return b;
                Method get = hide.getClass().getMethod("get");
                Object v = get.invoke(hide);
                if (v instanceof Boolean b) return b;
            } catch (Throwable ignored) {
            }
            try {
                Method m = options.getClass().getMethod("hideGui");
                Object v = m.invoke(options);
                if (v instanceof Boolean b) return b;
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int countEntities(Level level) {
        try {
            Iterable<?> it = (Iterable<?>) invoke(level, "entitiesForRendering");
            int count = 0;
            for (Object ignored : it) count++;
            return count;
        } catch (Throwable t) {
            try {
                Object entityLookup = invoke(level, "getEntities");
                Object all = invoke(entityLookup, "getAll");
                if (all instanceof Iterable<?> it) {
                    int count = 0;
                    for (Object ignored : it) count++;
                    return count;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static int countRenderedChunks(Minecraft client) {
        try {
            Object renderer = client.levelRenderer;
            Object result = invoke(renderer, "countRenderedChunks");
            if (result instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static Object invokeCountParticles(Minecraft client) {
        try {
            Object engine = client.particleEngine;
            return invoke(engine, "countParticles");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Integer) types[i] = int.class;
            else if (a instanceof Long) types[i] = long.class;
            else if (a instanceof Float) types[i] = float.class;
            else if (a instanceof Double) types[i] = double.class;
            else if (a instanceof Boolean) types[i] = boolean.class;
            else types[i] = a.getClass();
        }
        Method m = findMethod(target.getClass(), name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] types) throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == types.length) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static int parseParticleCount(Object countResult) {
        if (countResult == null) return 0;
        if (countResult instanceof Number n) return n.intValue();
        String s = countResult.toString().trim();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1));
            String digits = s.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int withAlpha(int color) {
        if ((color & 0xFF000000) == 0) {
            return color | 0xFF000000;
        }
        return color;
    }

    private static int colorForThreshold(double value, double critical, double warning, boolean higherIsBetter) {
        ProfilerConfig cfg = ProfilerConfig.INSTANCE;
        int c;
        if (higherIsBetter) {
            if (value <= critical) c = cfg.criticalColor;
            else if (value <= warning) c = cfg.warningColor;
            else c = cfg.textColor;
        } else {
            if (value >= critical) c = cfg.criticalColor;
            else if (value >= warning) c = cfg.warningColor;
            else c = cfg.textColor;
        }
        return withAlpha(c);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private record Line(String text, int color) {
    }
}
