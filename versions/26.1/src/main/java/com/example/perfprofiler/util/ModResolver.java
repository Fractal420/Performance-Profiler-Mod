package com.example.perfprofiler.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ModResolver {
    private static final Map<String, String> PACKAGE_TO_MOD = new ConcurrentHashMap<>();
    private static final Map<String, String> CLASS_TO_MOD = new ConcurrentHashMap<>();
    private static boolean built = false;

    private ModResolver() {
    }

    public static synchronized void rebuild() {
        PACKAGE_TO_MOD.clear();
        CLASS_TO_MOD.clear();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ModMetadata meta = container.getMetadata();
            String modId = meta.getId();
            PACKAGE_TO_MOD.putIfAbsent(modId, modId);
            PACKAGE_TO_MOD.putIfAbsent(modId.replace('-', '_'), modId);
            PACKAGE_TO_MOD.putIfAbsent(modId.replace('_', '-'), modId);
        }
        PACKAGE_TO_MOD.put("net.minecraft", "minecraft");
        PACKAGE_TO_MOD.put("com.mojang", "minecraft");
        PACKAGE_TO_MOD.put("net.fabricmc", "fabric");
        PACKAGE_TO_MOD.put("org.lwjgl", "lwjgl");
        PACKAGE_TO_MOD.put("java.", "java");
        PACKAGE_TO_MOD.put("jdk.", "java");
        PACKAGE_TO_MOD.put("sun.", "java");
        PACKAGE_TO_MOD.put("com.example.perfprofiler", "perfprofiler");
        built = true;
    }

    public static String resolveFromClassName(String className) {
        if (className == null || className.isEmpty()) return "unknown";
        String cached = CLASS_TO_MOD.get(className);
        if (cached != null) return cached;

        if (!built) rebuild();

        String best = "unknown";
        int bestLen = -1;
        String pkg = className;
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) pkg = className.substring(0, lastDot);

        String current = pkg;
        while (!current.isEmpty()) {
            String candidate = PACKAGE_TO_MOD.get(current);
            if (candidate != null && current.length() > bestLen) {
                best = candidate;
                bestLen = current.length();
            }
            for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
                String id = c.getMetadata().getId();
                if (current.contains(id) || current.contains(id.replace('-', '.')) || current.contains(id.replace('-', '_'))) {
                    if (id.length() > bestLen) {
                        best = id;
                        bestLen = id.length();
                    }
                }
            }
            int idx = current.lastIndexOf('.');
            if (idx <= 0) break;
            current = current.substring(0, idx);
        }

        if ("unknown".equals(best)) {
            if (className.startsWith("net.minecraft") || className.startsWith("com.mojang")) best = "minecraft";
            else if (className.startsWith("net.fabricmc")) best = "fabric";
            else if (className.startsWith("org.lwjgl")) best = "lwjgl";
            else if (className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.")) best = "java";
            else best = guessKnown(className);
        }

        CLASS_TO_MOD.put(className, best);
        return best;
    }

    private static String guessKnown(String className) {
        String lower = className.toLowerCase();
        if (lower.contains("sodium")) return "sodium";
        if (lower.contains("iris")) return "iris";
        if (lower.contains("lithium")) return "lithium";
        if (lower.contains("phosphor")) return "phosphor";
        if (lower.contains("entityculling")) return "entityculling";
        if (lower.contains("ferritecore")) return "ferritecore";
        if (lower.contains("modernfix")) return "modernfix";
        if (lower.contains("optifine")) return "optifine";
        if (lower.contains("rubidium")) return "rubidium";
        return "unknown-mod";
    }

    public static Map<String, String> getAllModsSummary() {
        Map<String, String> map = new HashMap<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            ModMetadata m = c.getMetadata();
            map.put(m.getId(), m.getVersion().getFriendlyString() + " | " + m.getName());
        }
        return map;
    }

    public static Optional<ModContainer> getMod(String id) {
        return FabricLoader.getInstance().getModContainer(id);
    }
}
