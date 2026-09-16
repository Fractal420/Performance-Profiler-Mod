package com.example.perfprofiler.profiler;

import com.example.perfprofiler.util.MeteorProbe;
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

        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("Performance Profiler Report\n");
        sb.append("Generated: ").append(Instant.now()).append('\n');
        sb.append("Reason: ").append(reason).append('\n');
        sb.append("================================================================================\n\n");

        sb.append("### ENVIRONMENT\n");
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        sb.append("Java: ").append(System.getProperty("java.version")).append(" (")
                .append(System.getProperty("java.vendor")).append(")\n");
        sb.append("JVM: ").append(rt.getVmName()).append(' ').append(rt.getVmVersion()).append('\n');
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" / ")
                .append(System.getProperty("os.arch")).append('\n');
        sb.append("Available processors: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        try {
            sb.append("Minecraft: ").append(net.minecraft.SharedConstants.getCurrentVersion()).append('\n');
        } catch (Throwable t) {
            sb.append("Minecraft: (unavailable)\n");
        }
        try {
            sb.append("Fabric Loader: ").append(FabricLoader.getInstance().getModContainer("fabricloader")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
        } catch (Throwable t) {
            sb.append("Fabric Loader: ?\n");
        }
        sb.append('\n');

        sb.append("### MEMORY & GC\n");
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        sb.append(String.format("Heap used: %s / %s (committed %s)\n",
                humanBytes(heap.getUsed()),
                humanBytes(heap.getMax() > 0 ? heap.getMax() : heap.getCommitted()),
                humanBytes(heap.getCommitted())));
        sb.append(String.format("Non-heap used: %s / committed %s\n",
                humanBytes(nonHeap.getUsed()), humanBytes(nonHeap.getCommitted())));
        long totalGcCount = 0;
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            long tm = gc.getCollectionTime();
            if (c >= 0) totalGcCount += c;
            if (tm >= 0) totalGcTime += tm;
            sb.append(String.format("GC %s: count=%d time=%dms\n", gc.getName(), c, tm));
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
        sb.append(String.format("Approx avg FPS during session: %.1f\n", avgFps));
        sb.append(String.format("Spike threshold: >= %d ms\n\n", sampler.getSpikeThresholdMs()));

        sb.append("### HOTTEST MODS (attributed, infrastructure rolled up)\n");
        sb.append("Higher hit count ≈ more relative client-thread time.\n");
        sb.append("lwjgl/java frames are attributed to the calling mod when possible.\n");
        sb.append("Per-mod RAM cannot be measured accurately without a full heap dump.\n\n");
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

        sb.append("### HOTTEST MODULES / FEATURES (from stack class names)\n");
        sb.append("Examples: meteor:Tracers, xaero:minimap:..., litematica:OverlayRenderer\n");
        sb.append("This is the best signal for which Meteor module or addon feature is hot.\n\n");
        Map<String, Long> moduleHits = sampler.getModuleHitCounts();
        long totalModu = moduleHits.values().stream().mapToLong(Long::longValue).sum();
        List<Map.Entry<String, Long>> sortedModules = new ArrayList<>(moduleHits.entrySet());
        sortedModules.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());
        rank = 1;
        if (sortedModules.isEmpty()) {
            sb.append("(no module-level frames captured)\n");
        } else {
            int mLimit = Math.min(40, sortedModules.size());
            for (int i = 0; i < mLimit; i++) {
                Map.Entry<String, Long> e = sortedModules.get(i);
                double pct = totalModu > 0 ? (e.getValue() * 100.0 / totalModu) : 0;
                sb.append(String.format("%2d. %-48s  hits=%6d  (~%.1f%%)\n",
                        rank++, e.getKey(), e.getValue(), pct));
            }
        }
        sb.append('\n');

        sb.append("### WORKLOAD CATEGORIES\n");
        sb.append("Rough classification of what the client thread was doing.\n\n");
        Map<String, Long> cats = sampler.getCategoryHitCounts();
        long totalCat = cats.values().stream().mapToLong(Long::longValue).sum();
        List<Map.Entry<String, Long>> sortedCats = new ArrayList<>(cats.entrySet());
        sortedCats.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());
        for (Map.Entry<String, Long> e : sortedCats) {
            double pct = totalCat > 0 ? (e.getValue() * 100.0 / totalCat) : 0;
            sb.append(String.format("  %-16s  hits=%6d  (~%.1f%%)\n", e.getKey(), e.getValue(), pct));
        }
        sb.append('\n');

        sb.append("### FRAME TIME HISTOGRAM\n");
        Map<String, Long> buckets = sampler.getFrameBucketCounts();
        long totalFramesBucket = buckets.values().stream().mapToLong(Long::longValue).sum();
        String[] order = {
                "0-8ms (120+ FPS)",
                "8-12ms (83-120 FPS)",
                "12-16.7ms (60-83 FPS)",
                "16.7-25ms (40-60 FPS)",
                "25-40ms (25-40 FPS)",
                "40-80ms (stutter)",
                "80-200ms (hitch)",
                "200ms+ (severe)"
        };
        for (String key : order) {
            long v = buckets.getOrDefault(key, 0L);
            double pct = totalFramesBucket > 0 ? (v * 100.0 / totalFramesBucket) : 0;
            sb.append(String.format("  %-24s  %6d  (~%.1f%%)\n", key, v, pct));
        }
        sb.append('\n');

        sb.append("### METEOR MODULES CURRENTLY ACTIVE\n");
        List<String> enabled = MeteorProbe.listEnabledModules();
        if (enabled.isEmpty()) {
            sb.append("(Meteor not present, or could not reflect modules)\n");
        } else {
            sb.append("Count: ").append(enabled.size()).append('\n');
            for (String name : enabled) {
                sb.append("  - ").append(name).append('\n');
            }
        }
        sb.append('\n');

        sb.append("### HOTTEST MODS (raw top-of-stack, includes lwjgl)\n");
        Map<String, Long> rawHits = sampler.getModHitCountsRaw();
        long totalRaw = rawHits.values().stream().mapToLong(Long::longValue).sum();
        List<Map.Entry<String, Long>> sortedRaw = new ArrayList<>(rawHits.entrySet());
        sortedRaw.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());
        rank = 1;
        for (Map.Entry<String, Long> e : sortedRaw) {
            double pct = totalRaw > 0 ? (e.getValue() * 100.0 / totalRaw) : 0;
            sb.append(String.format("%2d. %-32s  hits=%6d  (~%.1f%%)\n",
                    rank++, e.getKey(), e.getValue(), pct));
            if (rank > 15) break;
        }
        sb.append('\n');

        sb.append("### FRAME SPIKES (>= threshold)\n");
        sb.append("Captured near the slow frame using the latest async stack sample.\n\n");
        List<SampleCollector.SpikeRecord> spikes = sampler.getSpikes();
        if (spikes.isEmpty()) {
            sb.append("(no spikes captured)\n");
        } else {
            int spikeLimit = Math.min(20, spikes.size());
            for (int i = spikes.size() - spikeLimit; i < spikes.size(); i++) {
                SampleCollector.SpikeRecord sp = spikes.get(i);
                sb.append(String.format("t=%dms  frame=%.1fms  mod=%s  module=%s  cat=%s\n  %s\n",
                        sp.sessionMs(), sp.frameMs(), sp.attributedMod(),
                        sp.module().isEmpty() ? "-" : sp.module(),
                        sp.category(),
                        sp.stackSummary().isEmpty() ? "(no stack)" : sp.stackSummary()));
            }
        }
        sb.append('\n');

        sb.append("### MEMORY TIMELINE (heap, not per-mod)\n");
        sb.append("JVM does not expose accurate per-mod RAM without a heap dump.\n");
        sb.append("These samples show total heap and GC activity during the session.\n\n");
        List<SampleCollector.MemorySample> memSamples = sampler.getMemorySamples();
        if (memSamples.isEmpty()) {
            sb.append("(no memory samples)\n");
        } else {
            for (SampleCollector.MemorySample m : memSamples) {
                double pct = m.heapMax() > 0 ? (m.heapUsed() * 100.0 / m.heapMax()) : 0;
                sb.append(String.format(
                        "t=%dms  used=%s  committed=%s  max=%s  (%.0f%%)  gcCountΔ=%d  gcTimeΔ=%dms\n",
                        m.sessionMs(),
                        humanBytes(m.heapUsed()),
                        humanBytes(m.heapCommitted()),
                        humanBytes(m.heapMax()),
                        pct,
                        m.gcCountDelta(),
                        m.gcTimeMsDelta()));
            }
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
        List<Map.Entry<String, Integer>> sortedEntType = new ArrayList<>(byType.entrySet());
        sortedEntType.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        int tLimit = Math.min(30, sortedEntType.size());
        for (int i = 0; i < tLimit; i++) {
            Map.Entry<String, Integer> e = sortedEntType.get(i);
            sb.append(String.format("  %-40s  %5d\n", e.getKey(), e.getValue()));
        }
        sb.append('\n');

        sb.append("### PARTICLES & CHUNKS\n");
        try {
            Object pe = client.particleEngine;
            Object count = pe.getClass().getMethod("countParticles").invoke(pe);
            sb.append("Particles: ").append(count).append('\n');
        } catch (Throwable t) {
            sb.append("Particles: ?\n");
        }
        int rendered = safeCountRenderedChunks(client);
        sb.append("Chunks rendered: ").append(rendered).append('\n');
        try {
            if (client.level != null) {
                sb.append("Chunks loaded: ").append(client.level.getChunkSource().getLoadedChunksCount()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("Chunks loaded: ?\n");
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
        sb.append("- HOTTEST MODULES names Meteor/Xaero/Litematica features seen on the stack.\n");
        sb.append("- METEOR MODULES ACTIVE lists what is enabled right now (reflection).\n");
        sb.append("- WORKLOAD CATEGORIES and FRAME HISTOGRAM show stutter vs steady load.\n");
        sb.append("- FRAME SPIKES include module + category near slow frames.\n");
        sb.append("- Per-mod RAM is not available without a heap dump.\n");
        sb.append("- GPU heat on mobile: lower max FPS, RD, minimap, Tracers/ESP.\n");
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
