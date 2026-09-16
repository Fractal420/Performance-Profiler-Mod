package com.example.perfprofiler.profiler;

import com.example.perfprofiler.util.ModResolver;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class SampleCollector {
    private final ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
    private final MemoryMXBean memoryMx = ManagementFactory.getMemoryMXBean();

    private final Map<String, LongAdder> modHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> modHitsRaw = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> methodHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> moduleHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> categoryHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> frameBuckets = new ConcurrentHashMap<>();
    private final List<String> recentStacks = new ArrayList<>();
    private final List<SpikeRecord> spikes = new ArrayList<>();
    private final List<MemorySample> memorySamples = new ArrayList<>();
    private static final int MAX_RECENT_STACKS = 40;
    private static final int MAX_SPIKES = 40;
    private static final int MAX_MEMORY_SAMPLES = 80;

    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong totalFrameNs = new AtomicLong();
    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong maxFrameNs = new AtomicLong();
    private final AtomicLong minFrameNs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicReference<String> latestStackSummary = new AtomicReference<>("");
    private final AtomicReference<String> latestAttributedMod = new AtomicReference<>("unknown");
    private final AtomicReference<String> latestModule = new AtomicReference<>("");
    private final AtomicReference<String> latestCategory = new AtomicReference<>("other");

    private volatile boolean running = false;
    private volatile Thread clientThread;
    private Thread samplerThread;
    private long sessionStartNs;
    private long lastMemorySampleNs;
    private long startGcCount;
    private long startGcTimeMs;

    private static final long SAMPLE_SLEEP_MS = 5L;
    private static final long MEMORY_SAMPLE_INTERVAL_NS = 500_000_000L;
    private static final long SPIKE_THRESHOLD_NS = 40_000_000L;

    public void start() {
        stopSamplerThread();
        running = true;
        modHits.clear();
        modHitsRaw.clear();
        methodHits.clear();
        moduleHits.clear();
        categoryHits.clear();
        frameBuckets.clear();
        recentStacks.clear();
        spikes.clear();
        memorySamples.clear();
        totalSamples.set(0);
        totalFrameNs.set(0);
        frameCount.set(0);
        maxFrameNs.set(0);
        minFrameNs.set(Long.MAX_VALUE);
        latestStackSummary.set("");
        latestAttributedMod.set("unknown");
        latestModule.set("");
        latestCategory.set("other");
        sessionStartNs = System.nanoTime();
        lastMemorySampleNs = 0;
        long[] gc = readGc();
        startGcCount = gc[0];
        startGcTimeMs = gc[1];
        ModResolver.rebuild();
        recordMemorySample();
        samplerThread = new Thread(this::samplerLoop, "perfprofiler-sampler");
        samplerThread.setDaemon(true);
        samplerThread.setPriority(Thread.MAX_PRIORITY);
        samplerThread.start();
    }

    public void stop() {
        running = false;
        recordMemorySample();
        stopSamplerThread();
    }

    private void stopSamplerThread() {
        Thread t = samplerThread;
        samplerThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void onFrame(long frameDurationNs) {
        if (clientThread == null) {
            clientThread = Thread.currentThread();
        }
        if (!running) return;

        totalFrameNs.addAndGet(frameDurationNs);
        frameCount.incrementAndGet();
        maxFrameNs.accumulateAndGet(frameDurationNs, Math::max);
        minFrameNs.accumulateAndGet(frameDurationNs, Math::min);

        double ms = frameDurationNs / 1_000_000.0;
        frameBuckets.computeIfAbsent(bucketLabel(ms), k -> new LongAdder()).increment();

        if (frameDurationNs >= SPIKE_THRESHOLD_NS) {
            synchronized (spikes) {
                if (spikes.size() >= MAX_SPIKES) {
                    spikes.remove(0);
                }
                spikes.add(new SpikeRecord(
                        (System.nanoTime() - sessionStartNs) / 1_000_000L,
                        ms,
                        latestAttributedMod.get(),
                        latestModule.get(),
                        latestCategory.get(),
                        latestStackSummary.get()
                ));
            }
        }
    }

    private static String bucketLabel(double ms) {
        if (ms < 8) return "0-8ms (120+ FPS)";
        if (ms < 12) return "8-12ms (83-120 FPS)";
        if (ms < 16.7) return "12-16.7ms (60-83 FPS)";
        if (ms < 25) return "16.7-25ms (40-60 FPS)";
        if (ms < 40) return "25-40ms (25-40 FPS)";
        if (ms < 80) return "40-80ms (stutter)";
        if (ms < 200) return "80-200ms (hitch)";
        return "200ms+ (severe)";
    }

    private void samplerLoop() {
        while (running) {
            try {
                Thread target = clientThread;
                if (target != null && target.isAlive()) {
                    ThreadInfo info = threadMx.getThreadInfo(target.getId(), 72);
                    if (info != null && info.getStackTrace() != null && info.getStackTrace().length > 0) {
                        processStack(info.getStackTrace());
                    }
                }
                long now = System.nanoTime();
                if (now - lastMemorySampleNs >= MEMORY_SAMPLE_INTERVAL_NS) {
                    lastMemorySampleNs = now;
                    recordMemorySample();
                }
                Thread.sleep(SAMPLE_SLEEP_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void recordMemorySample() {
        try {
            MemoryUsage heap = memoryMx.getHeapMemoryUsage();
            long[] gc = readGc();
            synchronized (memorySamples) {
                if (memorySamples.size() >= MAX_MEMORY_SAMPLES) {
                    memorySamples.remove(0);
                }
                memorySamples.add(new MemorySample(
                        (System.nanoTime() - sessionStartNs) / 1_000_000L,
                        heap.getUsed(),
                        heap.getCommitted(),
                        heap.getMax() > 0 ? heap.getMax() : heap.getCommitted(),
                        gc[0] - startGcCount,
                        gc[1] - startGcTimeMs
                ));
            }
        } catch (Throwable ignored) {
        }
    }

    private static long[] readGc() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) time += t;
        }
        return new long[]{count, time};
    }

    private void processStack(StackTraceElement[] stack) {
        totalSamples.incrementAndGet();

        List<FrameInfo> frames = new ArrayList<>();
        StringBuilder stackSummary = new StringBuilder();
        int depth = 0;
        String bestModule = null;
        String category = "other";

        for (StackTraceElement el : stack) {
            String cn = el.getClassName();
            String mn = el.getMethodName();
            if (isNoiseFrame(cn, mn)) continue;
            if (depth++ > 40) break;

            String mod = resolveMod(cn, mn);
            frames.add(new FrameInfo(mod, cn, mn));
            methodHits.computeIfAbsent(mod + "|" + simpleName(cn) + "." + mn, k -> new LongAdder()).increment();

            String module = detectModule(cn, mn);
            if (module != null && bestModule == null) {
                bestModule = module;
            }

            String cat = detectCategory(cn, mn);
            if (!"other".equals(cat) && "other".equals(category)) {
                category = cat;
            }

            if (stackSummary.length() > 0) stackSummary.append(" <- ");
            stackSummary.append(simpleName(cn)).append('.').append(mn);
        }

        String rawMod = frames.isEmpty() ? "unknown" : frames.get(0).mod;
        String attributedMod = attributeMod(frames);
        if (bestModule == null) {
            bestModule = "";
        }

        modHitsRaw.computeIfAbsent(rawMod, k -> new LongAdder()).increment();
        modHits.computeIfAbsent(attributedMod, k -> new LongAdder()).increment();
        categoryHits.computeIfAbsent(category, k -> new LongAdder()).increment();
        if (!bestModule.isEmpty()) {
            moduleHits.computeIfAbsent(bestModule, k -> new LongAdder()).increment();
        }

        String summary = stackSummary.toString();
        latestStackSummary.set(summary);
        latestAttributedMod.set(attributedMod);
        latestModule.set(bestModule);
        latestCategory.set(category);

        synchronized (recentStacks) {
            if (recentStacks.size() >= MAX_RECENT_STACKS) {
                recentStacks.remove(0);
            }
            String label = attributedMod;
            if (!bestModule.isEmpty()) {
                label = label + " [" + bestModule + "]";
            }
            recentStacks.add(label + " /" + category + "/ :: " + summary);
        }
    }

    private static String detectModule(String className, String methodName) {
        String cn = className;
        String lower = cn.toLowerCase(Locale.ROOT);

        if (cn.contains("meteordevelopment.meteorclient.systems.modules")
                || cn.contains("meteordevelopment.meteorclient.systems.hud")
                || cn.contains("meteordevelopment.meteorclient.renderer")
                || cn.contains("meteordevelopment.meteorclient.utils.render")) {
            return "meteor:" + simpleName(cn);
        }
        if (lower.contains("meteor") && (lower.contains("module") || lower.contains("addon"))) {
            return "meteor:" + simpleName(cn);
        }

        if (lower.contains("baritone")) {
            return "baritone:" + simpleName(cn);
        }
        if (lower.contains("blackout")) {
            return "blackout:" + simpleName(cn);
        }
        if (lower.contains("meteorplus") || lower.contains("meteor.plus")) {
            return "meteorplus:" + simpleName(cn);
        }
        if (lower.contains("higtools")) {
            return "higtools:" + simpleName(cn);
        }
        if (lower.contains("trouser") || lower.contains("streak")) {
            return "streak:" + simpleName(cn);
        }
        if (lower.contains("litematica") || lower.contains("malilib")) {
            if (lower.contains("overlay") || lower.contains("render") || lower.contains("schematic")) {
                return "litematica:" + simpleName(cn);
            }
        }
        if (lower.contains("xaero")) {
            if (lower.contains("minimap") || lower.contains("radar")) return "xaero:minimap:" + simpleName(cn);
            if (lower.contains("worldmap") || lower.contains("mapprocessor") || lower.contains("mapregion")) {
                return "xaero:worldmap:" + simpleName(cn);
            }
            return "xaero:" + simpleName(cn);
        }
        if (lower.contains("iris") || lower.contains("irisshaders")) {
            return "iris:" + simpleName(cn);
        }
        if (lower.contains("sodium") && (lower.contains("render") || lower.contains("chunk") || lower.contains("section"))) {
            return "sodium:" + simpleName(cn);
        }

        String mixinMod = modFromMixinName(methodName);
        if (mixinMod != null && methodName != null) {
            int idx = methodName.lastIndexOf('$');
            if (idx > 0 && idx + 1 < methodName.length()) {
                String tail = methodName.substring(idx + 1);
                if (tail.length() > 2 && !tail.startsWith("mixinextras")) {
                    return mixinMod + ":" + tail;
                }
            }
        }
        return null;
    }

    private static String detectCategory(String className, String methodName) {
        String c = className.toLowerCase(Locale.ROOT);
        String m = methodName == null ? "" : methodName.toLowerCase(Locale.ROOT);
        if (c.contains("particle") || m.contains("particle")) return "particles";
        if (c.contains("minimap") || c.contains("worldmap") || c.contains("mapprocessor") || c.contains("mapregion")) {
            return "maps";
        }
        if (c.contains("gui") || c.contains("screen") || c.contains("hud") || c.contains("inGameHud".toLowerCase())
                || m.contains("flushguistate") || m.contains("onrender") && c.contains("class_329")) {
            return "gui_hud";
        }
        if (c.contains("overlay") || c.contains("schematic") || c.contains("litematica") || c.contains("malilib")) {
            return "world_overlay";
        }
        if (c.contains("tracer") || c.contains("esp") || c.contains("renderer3d") || c.contains("outline")
                || c.contains("waypoint") || c.contains("storageesp") || c.contains("holeesp")) {
            return "esp_tracers";
        }
        if (c.contains("chunk") || c.contains("terrain") || c.contains("section") || c.contains("sodium")
                || c.contains("levelrenderer") || c.contains("class_761")) {
            return "terrain";
        }
        if (c.contains("entity") || c.contains("livingentity") || c.contains("class_897") || c.contains("class_898")) {
            return "entities";
        }
        if (m.contains("tick") || c.contains("tick") || m.contains("ontick") || m.contains("ontick")) {
            return "tick";
        }
        if (c.contains("gl") || c.contains("lwjgl") || c.contains("glfw") || c.contains("opengl")
                || m.contains("buffer") || m.contains("draw")) {
            return "gpu_submit";
        }
        if (c.contains("path") || c.contains("baritone") || c.contains("goal")) {
            return "pathfinding";
        }
        if (c.contains("network") || c.contains("packet") || c.contains("connection")) {
            return "network";
        }
        return "other";
    }

    private static String attributeMod(List<FrameInfo> frames) {
        String fallback = "minecraft";
        for (FrameInfo f : frames) {
            if (isPreferMod(f.mod)) return f.mod;
            if (!isInfrastructure(f.mod)) {
                if ("unknown".equals(fallback) || "java".equals(fallback) || isInfrastructure(fallback)) {
                    fallback = f.mod;
                }
            }
        }
        for (FrameInfo f : frames) {
            if (!"java".equals(f.mod) && !isInfrastructure(f.mod)) return f.mod;
        }
        return frames.isEmpty() ? "unknown" : frames.get(0).mod;
    }

    private static boolean isInfrastructure(String mod) {
        return "lwjgl".equals(mod) || "java".equals(mod) || "jdk".equals(mod);
    }

    private static String resolveMod(String className, String methodName) {
        String fromMixin = modFromMixinName(methodName);
        if (fromMixin != null) return fromMixin;
        fromMixin = modFromMixinName(className);
        if (fromMixin != null) return fromMixin;
        return ModResolver.resolveFromClassName(className);
    }

    private static String modFromMixinName(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("$iris$") || lower.contains(".iris.") || lower.contains("net.irisshaders")) return "iris";
        if (lower.contains("$sodium$") || lower.contains(".sodium.")) return "sodium";
        if (lower.contains("$zoomify$") || lower.contains("zoomify")) return "zoomify";
        if (lower.contains("$lithium$") || lower.contains(".lithium.")) return "lithium";
        if (lower.contains("meteor")) return "meteor-client";
        if (lower.contains("baritone")) return "baritone-meteor";
        if (lower.contains("xaero") && lower.contains("minimap")) return "xaerominimap";
        if (lower.contains("xaero") && lower.contains("worldmap")) return "xaeroworldmap";
        if (lower.contains("xaero")) return "xaerolib";
        if (lower.contains("litematica")) return "litematica";
        if (lower.contains("malilib")) return "malilib";
        if (lower.contains("immediatelyfast")) return "immediatelyfast";
        if (lower.contains("entityculling")) return "entityculling";
        if (lower.contains("wthit") || lower.contains("jade")) return "wthit";
        if (lower.contains("fabric-rendering") || lower.contains("hudelementregistry")) return "fabric";
        return null;
    }

    private static boolean isNoiseFrame(String className, String methodName) {
        if (className.startsWith("com.example.perfprofiler")) return true;
        if (className.startsWith("java.lang.Thread")) return true;
        if (className.startsWith("java.lang.management")) return true;
        if (className.startsWith("sun.management")) return true;
        if (className.startsWith("jdk.internal")) return true;
        if (className.startsWith("java.lang.invoke.LambdaForm")) return true;
        if (className.startsWith("java.lang.invoke.Invokers")) return true;
        if ("getStackTrace".equals(methodName)) return true;
        if ("getThreadInfo".equals(methodName) || "getThreadInfo1".equals(methodName)) return true;
        return false;
    }

    private static boolean isPreferMod(String mod) {
        if (mod == null) return false;
        if (isInfrastructure(mod)) return false;
        if ("minecraft".equals(mod) || "fabric".equals(mod)) return false;
        if ("unknown".equals(mod) || "unknown-mod".equals(mod)) return false;
        if ("perfprofiler".equals(mod)) return false;
        return true;
    }

    private static String simpleName(String className) {
        int i = className.lastIndexOf('.');
        String n = i >= 0 ? className.substring(i + 1) : className;
        int d = n.indexOf('$');
        if (d > 0) n = n.substring(0, d);
        return n;
    }

    public Map<String, Long> getModHitCounts() {
        Map<String, Long> out = new HashMap<>();
        modHits.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Long> getModHitCountsRaw() {
        Map<String, Long> out = new HashMap<>();
        modHitsRaw.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Long> getMethodHitCounts() {
        Map<String, Long> out = new HashMap<>();
        methodHits.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Long> getModuleHitCounts() {
        Map<String, Long> out = new HashMap<>();
        moduleHits.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Long> getCategoryHitCounts() {
        Map<String, Long> out = new HashMap<>();
        categoryHits.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Long> getFrameBucketCounts() {
        Map<String, Long> out = new HashMap<>();
        frameBuckets.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public List<String> getRecentStacks() {
        synchronized (recentStacks) {
            return new ArrayList<>(recentStacks);
        }
    }

    public List<SpikeRecord> getSpikes() {
        synchronized (spikes) {
            return new ArrayList<>(spikes);
        }
    }

    public List<MemorySample> getMemorySamples() {
        synchronized (memorySamples) {
            return new ArrayList<>(memorySamples);
        }
    }

    public long getTotalSamples() {
        return totalSamples.get();
    }

    public long getFrameCount() {
        return frameCount.get();
    }

    public double getAvgFrameMs() {
        long c = frameCount.get();
        if (c == 0) return 0;
        return (totalFrameNs.get() / (double) c) / 1_000_000.0;
    }

    public double getMaxFrameMs() {
        return maxFrameNs.get() / 1_000_000.0;
    }

    public double getMinFrameMs() {
        long v = minFrameNs.get();
        return v == Long.MAX_VALUE ? 0 : v / 1_000_000.0;
    }

    public long getSpikeThresholdMs() {
        return SPIKE_THRESHOLD_NS / 1_000_000L;
    }

    public record SpikeRecord(long sessionMs, double frameMs, String attributedMod, String module, String category, String stackSummary) {
    }

    public record MemorySample(long sessionMs, long heapUsed, long heapCommitted, long heapMax, long gcCountDelta, long gcTimeMsDelta) {
    }

    private record FrameInfo(String mod, String className, String methodName) {
    }
}
