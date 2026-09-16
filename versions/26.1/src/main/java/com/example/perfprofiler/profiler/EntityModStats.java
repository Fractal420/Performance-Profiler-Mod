package com.example.perfprofiler.profiler;

import com.example.perfprofiler.util.ModResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class EntityModStats {
    private EntityModStats() {
    }

    public static Map<String, Integer> countEntitiesByMod(Minecraft client) {
        Map<String, Integer> byMod = new HashMap<>();
        Level level = client.level;
        if (level == null) return byMod;

        for (Entity e : getEntities(level)) {
            String mod = resolveEntityMod(e);
            byMod.merge(mod, 1, Integer::sum);
        }
        return byMod;
    }

    public static Map<String, Integer> countEntitiesByType(Minecraft client) {
        Map<String, Integer> byType = new HashMap<>();
        Level level = client.level;
        if (level == null) return byType;

        for (Entity e : getEntities(level)) {
            String key = EntityType.getKey(e.getType()).toString();
            byType.merge(key, 1, Integer::sum);
        }
        return byType;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Entity> getEntities(Level level) {
        try {
            Method m = level.getClass().getMethod("entitiesForRendering");
            return (Iterable<Entity>) m.invoke(level);
        } catch (Throwable t) {
            try {
                Object lookup = level.getClass().getMethod("getEntities").invoke(level);
                Object all = lookup.getClass().getMethod("getAll").invoke(lookup);
                return (Iterable<Entity>) all;
            } catch (Throwable t2) {
                return java.util.Collections.emptyList();
            }
        }
    }

    private static String resolveEntityMod(Entity e) {
        try {
            String className = e.getClass().getName();
            String mod = ModResolver.resolveFromClassName(className);
            if (!"unknown".equals(mod) && !"unknown-mod".equals(mod) && !"minecraft".equals(mod)) {
                return mod;
            }
            String key = EntityType.getKey(e.getType()).toString();
            if (key.contains(":")) {
                String ns = key.substring(0, key.indexOf(':'));
                if (!"minecraft".equals(ns)) return ns;
            }
            return "minecraft";
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
