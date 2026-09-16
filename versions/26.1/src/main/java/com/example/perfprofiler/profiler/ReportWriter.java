package com.example.perfprofiler.profiler;

import com.example.perfprofiler.util.ModResolver;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ReportWriter {
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private ReportWriter() {
    }

    public static Path writeReport(Minecraft client, SampleCollector sampler, String reason) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("perfprofiler").resolve("reports");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return null;
        }

        String ts = FMT.format(Instant.now());
        Path file = dir.resolve("profiler-report-" + ts + ".txt");

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("================================================================================\n");
        sb.append("Performance Profiler Report\n");
        sb.append("Generated: ").append(Instant.now()).append('\n');
        sb.append("Reason: ").append(reason).append('\n');
        sb.append("================================================================================\n\n");

        sb.append("### ENVIRONMENT\n");
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        sb.append("Java: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("JVM: ").append(rt.getVmName()).append(' ').append(rt.getVmVersion()).append('\n');
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" / ")
                .append(System.getProperty("os.arch")).append('\n');
        sb.append("Available processors: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("Minecraft: ").append(String.valueOf(net.minecraft.SharedConstants.getCurrentVersion())).append('\n');
        try {
            sb.append("Fabric Loader: ").append(FabricLoader.getInstance().getModContainer("fabricloader")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
        } catch (Throwable ignored) {
        }
        sb.append('\n');

        sb.append("### MEMORY & GC\n");
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        sb.append(String.format("Heap used: %s / %s (committed %s)\n",
                humanBytes(heap.getUsed()), humanBytes(heap.getMax()), humanBytes(heap.getCommitted())));
        sb.append(String.format("Non-heap used: %s / committed %s\n",
                humanBytes(nonHeap.getUsed()), humanBytes(nonHeap.getCommitted())));
        long totalGcTime = 0;
        long totalGcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            long c = gc.getCollectionCount();
            if (t >= 0) totalGcTime += t;
            if (c >= 0) totalGcCount += c;
            sb.append(String.format("GC %s: count=%d time=%dms\n", gc.getName(), c, t));
        }
        sb.append(String.format("Total GC: count=%d time=%dms\n\n", totalGcCount, totalGcTime));

        sb.append("### CLIENT OPTIONS (performance relevant)\n");
        try {
            Options opt = client.options;
            sb.append("Render distance: ").append(opt.getEffectiveRenderDistance()).append('\n');
            sb.append("Simulation distance: ").append(opt.simulationDistance().get()).append('\n');
            sb.append("Graphics: ").append(safeGet(opt, "graphicsMode")).append('\n');
            sb.append("Clouds: ").append(opt.cloudStatus().get()).append('\n');
            sb.append("Particles: ").append(opt.particles().get()).append('\n');
            sb.append("Entity shadows: ").append(opt.entityShadows().get()).append('\n');
            sb.append("Biome blend: ").append(opt.biomeBlendRadius().get()).append('\n');
            sb.append("Mipmap levels: ").append(opt.mipmapLevels().get()).append('\n');
            sb.append("VSync: ").append(opt.enableVsync().get()).append('\n');
            sb.append("Max FPS: ").append(opt.framerateLimit().get()).append('\n');
            sb.append("Fullscreen: ").append(opt.fullscreen().get()).append('\n');
            sb.append("GUI scale: ").append(opt.guiScale().get()).append('\n');
            sb.append("FOV: ").append(opt.fov().get()).append('\n');
        } catch (Throwable t) {
            sb.append("(could not read some options: ").append(t.getMessage()).append(")\n");
        }
        sb.append('\n');

        sb.append("### FRAME / SAMPLER SUMMARY\n");
        sb.append(String.format("Profiling samples: %d\n", sampler.getTotalSamples()));
        sb.append(String.format("Frames recorded: %d\n", sampler.getFrameCount()));
        sb.append(String.format("Avg frame time: %.3f ms\n", sampler.getAvgFrameMs()));
        sb.append(String.format("Min frame time: %.3f ms\n", sampler.getMinFrameMs()));
        sb.append(String.format("Max frame time: %.3f ms\n", sampler.getMaxFrameMs()));
        double avgFps = sampler.getAvgFrameMs() > 0 ? 1000.0 / sampler.getAvgFrameMs() : 0;
        sb.append(String.format("Approx avg FPS during session: %.1f\n\n", avgFps));

        sb.append("### HOTTEST MODS (by stack sample attribution)\n");
        sb.append("Higher hit count ≈ more time spent in that mod's code on the client thread.\n");
        sb.append("Note: statistical sampling – treat as relative ranking, not absolute %.\n\n");
        Map<String, Long> modHits = sampler.getModHitCounts();
        long totalHits = modHits.values().stream().mapToLong(Long::longValue).sum();
        List<Map.Entry<String, Long>> sortedMods = new ArrayList<>(modHits.entrySet());
        sortedMods.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());
        int rank = 1;
        for (Map.Entry<String, Long> e : sortedMods) {
            double pct = totalHits > 0 ? (e.getValue() * 100.0 / totalHits) : 0;
            sb.append(String.format("%2d. %-32s  hits=%6d  (~%.1f%%)\n",
                    rank++, e.getKey(), e.getValue(), pct));
        }
        sb.append('\n');

        sb.append("### HOTTEST METHODS (mod|Class.method)\n");
        Map<String, Long> methodHits = sampler.getMethodHitCounts();
        List<Map.Entry<String, Long>> sortedMethods = new ArrayList<>(methodHits.entrySet());
        sortedMethods.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());
        int limit = Math.min(40, sortedMethods.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> e = sortedMethods.get(i);
            double pct = totalHits > 0 ? (e.getValue() * 100.0 / totalHits) : 0;
            sb.append(String.format("%2d. %-70s  hits=%5d  (~%.1f%%)\n",
                    i + 1, e.getKey(), e.getValue(), pct));
        }
        sb.append('\n');

        sb.append("### RECENT STACK SAMPLES (for deeper AI analysis)\n");
        List<String> stacks = sampler.getRecentStacks();
        int sLimit = Math.min(25, stacks.size());
        for (int i = 0; i < sLimit; i++) {
            sb.append("Sample ").append(i + 1).append(": ").append(stacks.get(stacks.size() - sLimit + i)).append('\n');
        }
        sb.append('\n');

        sb.append("### ENTITY COUNTS BY MOD\n");
        Map<String, Integer> byMod = EntityModStats.countEntitiesByMod(client);
        List<Map.Entry<String, Integer>> sortedEntMod = new ArrayList<>(byMod.entrySet());
        sortedEntMod.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        int totalEnt = byMod.values().stream().mapToInt(Integer::intValue).sum();
        sb.append("Total entities (client view): ").append(totalEnt).append('\n');
        for (Map.Entry<String, Integer> e : sortedEntMod) {
            sb.append(String.format("  %-28s  %5d\n", e.getKey(), e.getValue()));
        }
        sb.append('\n');

        sb.append("### ENTITY COUNTS BY TYPE (top 30)\n");
        Map<String, Integer> byType = EntityModStats.countEntitiesByType(client);
        List<Map.Entry<String, Integer>> sortedType = new ArrayList<>(byType.entrySet());
        sortedType.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        int tLimit = Math.min(30, sortedType.size());
        for (int i = 0; i < tLimit; i++) {
            Map.Entry<String, Integer> e = sortedType.get(i);
            sb.append(String.format("  %-50s  %5d\n", e.getKey(), e.getValue()));
        }
        sb.append('\n');

        sb.append("### PARTICLES & CHUNKS\n");
        try {
            Object raw = client.particleEngine.countParticles();
            sb.append("Particles: ").append(raw).append('\n');
        } catch (Throwable t) {
            sb.append("Particles: (unavailable)\n");
        }
        try {
            int rendered = safeCountRenderedChunks(client);
            int loaded = client.level != null ? client.level.getChunkSource().getLoadedChunksCount() : -1;
            sb.append("Chunks rendered: ").append(rendered).append('\n');
            sb.append("Chunks loaded: ").append(loaded).append('\n');
        } catch (Throwable t) {
            sb.append("Chunks: (unavailable)\n");
        }
        sb.append('\n');

        sb.append("### ALL LOADED MODS (id → version | name)\n");
        Map<String, String> mods = ModResolver.getAllModsSummary();
        List<String> sortedIds = mods.keySet().stream().sorted().collect(Collectors.toList());
        for (String id : sortedIds) {
            sb.append(String.format("  %-32s  %s\n", id, mods.get(id)));
        }
        sb.append('\n');

        sb.append("### ANALYSIS HINTS\n");
        sb.append("- Look at HOTTEST MODS first. Mods with high sample % and high entity counts are prime suspects.\n");
        sb.append("- If 'minecraft' or 'java' dominate, the issue may be vanilla load, render distance, or GC pressure.\n");
        sb.append("- High particle counts often come from particle-heavy mods, fireworks, potions, or shaders.\n");
        sb.append("- Sudden max frame spikes with low average may indicate GC pauses or chunk loading stalls.\n");
        sb.append("- Compare this report with another taken after disabling the top 1-3 suspect mods.\n");
        sb.append("- GPU overheating is often caused by shaders (Iris/Oculus), high render distance, or unlimited FPS.\n");
        sb.append("- This report is designed so you can paste the whole file to an AI for interpretation.\n");
        sb.append("================================================================================\n");

        try {
            Files.writeString(file, sb.toString());
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    private static Object safeGet(Object opt, String name) {
        try {
            Object option = opt.getClass().getMethod(name).invoke(opt);
            return option.getClass().getMethod("get").invoke(option);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int safeCountRenderedChunks(Minecraft client) {
        try {
            Object result = client.levelRenderer.getClass().getMethod("countRenderedChunks").invoke(client.levelRenderer);
            if (result instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MiB", bytes / (1024.0 * 1024));
        return String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
