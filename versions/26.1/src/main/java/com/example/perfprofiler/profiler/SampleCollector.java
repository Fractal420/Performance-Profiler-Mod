package com.example.perfprofiler.profiler;

import com.example.perfprofiler.util.ModResolver;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private long lastSampleNs = 0;
    private static final long SAMPLE_INTERVAL_NS = 5_000_000L;

    public void start() {
        running = true;
        modHits.clear();
        methodHits.clear();
        recentStacks.clear();
        totalSamples.set(0);
        totalFrameNs.set(0);
        frameCount.set(0);
        maxFrameNs.set(0);
        minFrameNs.set(Long.MAX_VALUE);
        lastSampleNs = 0;
        ModResolver.rebuild();
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public void onFrame(long frameDurationNs) {
        if (!running) return;

        totalFrameNs.addAndGet(frameDurationNs);
        frameCount.incrementAndGet();
        maxFrameNs.accumulateAndGet(frameDurationNs, Math::max);
        minFrameNs.accumulateAndGet(frameDurationNs, Math::min);

        long now = System.nanoTime();
        if (now - lastSampleNs < SAMPLE_INTERVAL_NS) return;
        lastSampleNs = now;

        sampleCurrentThread();
    }

    private void sampleCurrentThread() {
        try {
            ThreadInfo info = threadMx.getThreadInfo(Thread.currentThread().getId(), 48);
            if (info == null) return;
            StackTraceElement[] stack = info.getStackTrace();
            if (stack == null || stack.length == 0) return;

            totalSamples.incrementAndGet();

            String attributedMod = "unknown";
            boolean foundMod = false;

            StringBuilder stackSummary = new StringBuilder();
            int depth = 0;
            for (StackTraceElement el : stack) {
                if (depth++ > 24) break;
                String cn = el.getClassName();
                String mod = ModResolver.resolveFromClassName(cn);
                String methodKey = mod + "|" + simpleName(cn) + "." + el.getMethodName();

                methodHits.computeIfAbsent(methodKey, k -> new LongAdder()).increment();

                if (!foundMod && !"java".equals(mod) && !"minecraft".equals(mod) && !"fabric".equals(mod) && !"lwjgl".equals(mod)) {
                    attributedMod = mod;
                    foundMod = true;
                } else if (!foundMod && ("minecraft".equals(mod) || "fabric".equals(mod))) {
                    if ("unknown".equals(attributedMod) || "java".equals(attributedMod)) {
                        attributedMod = mod;
                    }
                }

                if (stackSummary.length() > 0) stackSummary.append(" <- ");
                stackSummary.append(simpleName(cn)).append('.').append(el.getMethodName());
            }

            modHits.computeIfAbsent(attributedMod, k -> new LongAdder()).increment();

            synchronized (recentStacks) {
                if (recentStacks.size() >= MAX_RECENT_STACKS) {
                    recentStacks.remove(0);
                }
                recentStacks.add(attributedMod + " :: " + stackSummary);
            }
        } catch (Throwable ignored) {
        }
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
