package com.example.perfprofiler.profiler;

import com.example.perfprofiler.util.ModResolver;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class SampleCollector {
    private final ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();

    private final Map<String, LongAdder> modHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> methodHits = new ConcurrentHashMap<>();
    private final List<String> recentStacks = new ArrayList<>();
    private static final int MAX_RECENT_STACKS = 40;

    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong totalFrameNs = new AtomicLong();
    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong maxFrameNs = new AtomicLong();
    private final AtomicLong minFrameNs = new AtomicLong(Long.MAX_VALUE);

    private volatile boolean running = false;
    private volatile Thread clientThread;
    private Thread samplerThread;

    private static final long SAMPLE_SLEEP_MS = 5L;

    public void start() {
        stopSamplerThread();
        running = true;
        modHits.clear();
        methodHits.clear();
        recentStacks.clear();
        totalSamples.set(0);
        totalFrameNs.set(0);
        frameCount.set(0);
        maxFrameNs.set(0);
        minFrameNs.set(Long.MAX_VALUE);
        ModResolver.rebuild();
        samplerThread = new Thread(this::samplerLoop, "perfprofiler-sampler");
        samplerThread.setDaemon(true);
        samplerThread.setPriority(Thread.MAX_PRIORITY);
        samplerThread.start();
    }

    public void stop() {
        running = false;
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
    }

    private void samplerLoop() {
        while (running) {
            try {
                Thread target = clientThread;
                if (target != null && target.isAlive()) {
                    ThreadInfo info = threadMx.getThreadInfo(target.getId(), 64);
                    if (info != null && info.getStackTrace() != null && info.getStackTrace().length > 0) {
                        processStack(info.getStackTrace());
                    }
                }
                Thread.sleep(SAMPLE_SLEEP_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void processStack(StackTraceElement[] stack) {
        totalSamples.incrementAndGet();

        String attributedMod = "unknown";
        StringBuilder stackSummary = new StringBuilder();
        int depth = 0;
        boolean attributed = false;

        for (StackTraceElement el : stack) {
            String cn = el.getClassName();
            String mn = el.getMethodName();

            if (isNoiseFrame(cn, mn)) continue;
            if (depth++ > 32) break;

            String mod = resolveMod(cn, mn);
            String methodKey = mod + "|" + simpleName(cn) + "." + mn;
            methodHits.computeIfAbsent(methodKey, k -> new LongAdder()).increment();

            if (!attributed) {
                if (isPreferMod(mod)) {
                    attributedMod = mod;
                    attributed = true;
                } else if ("unknown".equals(attributedMod) || "java".equals(attributedMod)) {
                    attributedMod = mod;
                }
            }

            if (stackSummary.length() > 0) stackSummary.append(" <- ");
            stackSummary.append(simpleName(cn)).append('.').append(mn);
        }

        if (!attributed && ("unknown".equals(attributedMod) || attributedMod.isEmpty())) {
            attributedMod = "minecraft";
        }

        modHits.computeIfAbsent(attributedMod, k -> new LongAdder()).increment();

        synchronized (recentStacks) {
            if (recentStacks.size() >= MAX_RECENT_STACKS) {
                recentStacks.remove(0);
            }
            recentStacks.add(attributedMod + " :: " + stackSummary);
        }
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
        if (className.contains("perfprofiler-sampler")) return true;
        return false;
    }

    private static boolean isPreferMod(String mod) {
        if (mod == null) return false;
        if ("java".equals(mod) || "minecraft".equals(mod) || "fabric".equals(mod) || "lwjgl".equals(mod)) return false;
        if ("unknown".equals(mod) || "unknown-mod".equals(mod)) return false;
        if ("perfprofiler".equals(mod)) return false;
        return true;
    }

    private static String simpleName(String className) {
        int i = className.lastIndexOf('.');
        return i >= 0 ? className.substring(i + 1) : className;
    }

    public Map<String, Long> getModHitCounts() {
        Map<String, Long> out = new HashMap<>();
        modHits.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Long> getMethodHitCounts() {
        Map<String, Long> out = new HashMap<>();
        methodHits.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public List<String> getRecentStacks() {
        synchronized (recentStacks) {
            return new ArrayList<>(recentStacks);
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
}
