package ru.evga314.dragscroll.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reflective access to optional mods whose classes are not on the compile
 * class path. Lookups walk the class hierarchy from the object's own class
 * up, exactly one level at a time, and are cached per class: these helpers
 * run on every touch event, and a fresh getDeclaredField/getDeclaredMethod
 * scan each time was a large part of the mod's per-frame cost.
 */
public final class Reflect {
    private Reflect() {
    }

    /** Declared fields of one class (not its superclasses), accessible. */
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> map = new HashMap<>();
            try {
                for (Field f : type.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        map.putIfAbsent(f.getName(), f);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            return map;
        }
    };

    /** Declared no-argument methods of one class, accessible. */
    private static final ClassValue<Map<String, Method>> NO_ARG_METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> map = new HashMap<>();
            try {
                for (Method m : type.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) {
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                        map.putIfAbsent(m.getName(), m);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            return map;
        }
    };

    /** Public no-argument methods of a class, inherited and interface ones included. */
    private static final ClassValue<Map<String, Method>> PUBLIC_NO_ARG_METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> map = new HashMap<>();
            try {
                for (Method m : type.getMethods()) {
                    if (m.getParameterCount() == 0) {
                        map.putIfAbsent(m.getName(), m);
                    }
                }
            } catch (Throwable ignored) {
            }
            return map;
        }
    };

    /** Declared methods of one class, in declaration order. */
    private static final ClassValue<List<Method>> DECLARED_METHODS = new ClassValue<>() {
        @Override
        protected List<Method> computeValue(Class<?> type) {
            try {
                return List.of(type.getDeclaredMethods());
            } catch (Throwable ignored) {
                return List.of();
            }
        }
    };

    /** True when {@code type} or one of its superclasses has exactly this name. */
    public static boolean extendsClass(Class<?> type, String className) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInstance(Object obj, String className) {
        return obj != null && extendsClass(obj.getClass(), className);
    }

    /** The field {@code name} declared by the first class in the hierarchy that has one. */
    public static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field f = FIELDS.get(c).get(name);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** The field {@code name} declared by exactly this class, or null. */
    public static Field declaredField(Class<?> type, String name) {
        return FIELDS.get(type).get(name);
    }

    /** The method declared by exactly this class, accessible, or null. */
    public static Method declaredMethodIn(Class<?> type, String name, Class<?>... params) {
        for (Method m : DECLARED_METHODS.get(type)) {
            if (m.getName().equals(name) && java.util.Arrays.equals(m.getParameterTypes(), params)) {
                try {
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The method declared by the first class in the hierarchy that has it, accessible, or null. */
    public static Method declaredMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Method m = declaredMethodIn(c, name, params);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** Value of the field {@code name}, or null. */
    public static Object read(Object obj, String name) {
        Field f = field(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * First int, double or float field found among {@code names}, checked
     * class by class (a subclass field wins over a superclass one); NaN when
     * none exists.
     */
    public static double readNumber(Object obj, String... names) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Map<String, Field> fields = FIELDS.get(c);
            for (String name : names) {
                Field f = fields.get(name);
                if (f == null) {
                    continue;
                }
                try {
                    Class<?> t = f.getType();
                    if (t == int.class) {
                        return f.getInt(obj);
                    }
                    if (t == double.class) {
                        return f.getDouble(obj);
                    }
                    if (t == float.class) {
                        return f.getFloat(obj);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return Double.NaN;
    }

    /** Writes {@code value} into every double, float or int field named in {@code names}. */
    public static void writeNumber(Object obj, double value, String... names) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Map<String, Field> fields = FIELDS.get(c);
            for (String name : names) {
                Field f = fields.get(name);
                if (f == null) {
                    continue;
                }
                try {
                    Class<?> t = f.getType();
                    if (t == double.class) {
                        f.setDouble(obj, value);
                    } else if (t == float.class) {
                        f.setFloat(obj, (float) value);
                    } else if (t == int.class) {
                        f.setInt(obj, (int) Math.round(value));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Result of the first no-argument method among {@code names} that returns
     * a number, checked class by class; NaN when none does.
     */
    public static double invokeNumber(Object obj, String... names) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Map<String, Method> methods = NO_ARG_METHODS.get(c);
            for (String name : names) {
                Method m = methods.get(name);
                if (m == null) {
                    continue;
                }
                try {
                    if (m.invoke(obj) instanceof Number n) {
                        return n.doubleValue();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return Double.NaN;
    }

    /** Public methods looked up by name and parameter types, per class. */
    private static final ClassValue<Map<String, Optional<Method>>> PUBLIC_METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Optional<Method>> computeValue(Class<?> type) {
            return new java.util.concurrent.ConcurrentHashMap<>();
        }
    };

    /** The public method {@code name(params)} as Class.getMethod finds it, cached; null when absent. */
    public static Method publicMethod(Class<?> type, String name, Class<?>... params) {
        String key = name + java.util.Arrays.toString(params);
        return PUBLIC_METHODS.get(type).computeIfAbsent(key, k -> {
            try {
                return Optional.of(type.getMethod(name, params));
            } catch (Throwable t) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Result of the public no-argument method {@code name} (as Class.getMethod finds it), or null. */
    public static Object invokePublic(Object obj, String name) {
        Method m = PUBLIC_NO_ARG_METHODS.get(obj.getClass()).get(name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Calls the first scroll setter the object has (scrollTo, setScrollAmount,
     * capYPosition or setScroll) taking a number, or a number and a boolean
     * animate flag (passed false). Returns true when one ran.
     */
    public static boolean invokeScrollSetter(Object obj, double target) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : DECLARED_METHODS.get(c)) {
                String name = m.getName();
                if (!(name.equals("scrollTo") || name.equals("setScrollAmount")
                        || name.equals("capYPosition") || name.equals("setScroll"))) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                try {
                    m.setAccessible(true);
                    if (p.length == 1) {
                        if (p[0] == int.class) {
                            m.invoke(obj, (int) Math.round(target));
                            return true;
                        }
                        if (p[0] == float.class) {
                            m.invoke(obj, (float) target);
                            return true;
                        }
                        if (p[0] == double.class || p[0] == Double.class) {
                            m.invoke(obj, target);
                            return true;
                        }
                    } else if (p.length == 2 && (p[0] == double.class || p[0] == Double.class)
                            && (p[1] == boolean.class || p[1] == Boolean.class)) {
                        m.invoke(obj, target, Boolean.FALSE);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }
}
