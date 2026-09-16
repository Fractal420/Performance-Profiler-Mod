package com.example.perfprofiler.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class MeteorProbe {
    private MeteorProbe() {
    }

    public static List<String> listEnabledModules() {
        List<String> out = new ArrayList<>();
        try {
            Class<?> modulesClass = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            Object modules = null;
            try {
                Method get = modulesClass.getMethod("get");
                modules = get.invoke(null);
            } catch (NoSuchMethodException e) {
                Field f = modulesClass.getDeclaredField("INSTANCE");
                f.setAccessible(true);
                modules = f.get(null);
            }
            if (modules == null) return out;

            Collection<?> all = null;
            for (String name : new String[]{"getAll", "getList", "getModules"}) {
                try {
                    Method m = modules.getClass().getMethod(name);
                    Object r = m.invoke(modules);
                    if (r instanceof Collection<?> c) {
                        all = c;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (all == null) {
                for (Field f : modules.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(modules);
                    if (v instanceof Map<?, ?> map) {
                        all = map.values();
                        break;
                    }
                    if (v instanceof Collection<?> c && !c.isEmpty()) {
                        all = c;
                        break;
                    }
                }
            }
            if (all == null) return out;

            for (Object mod : all) {
                if (mod == null) continue;
                boolean active = false;
                String title = mod.getClass().getSimpleName();
                try {
                    Method isActive = mod.getClass().getMethod("isActive");
                    Object a = isActive.invoke(mod);
                    if (a instanceof Boolean b) active = b;
                } catch (Throwable ignored) {
                }
                try {
                    Method nameM = mod.getClass().getMethod("getName");
                    Object n = nameM.invoke(mod);
                    if (n != null) title = n.toString();
                } catch (Throwable t) {
                    try {
                        Method nameM = mod.getClass().getMethod("name");
                        Object n = nameM.invoke(mod);
                        if (n != null) title = n.toString();
                    } catch (Throwable ignored) {
                    }
                }
                String category = "";
                try {
                    Method cat = mod.getClass().getMethod("getCategory");
                    Object c = cat.invoke(mod);
                    if (c != null) category = c.toString();
                } catch (Throwable ignored) {
                }
                if (active) {
                    if (!category.isEmpty()) {
                        out.add(title + " [" + category + "]");
                    } else {
                        out.add(title);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
