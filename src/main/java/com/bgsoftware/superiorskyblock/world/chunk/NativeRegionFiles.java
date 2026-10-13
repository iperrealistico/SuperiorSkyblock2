package com.bgsoftware.superiorskyblock.world.chunk;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Source-level bridge for Paper/Folia's RegionFile and Leaf's IRegionFile return ABI.
 * Calls run on the native ordered region-IO worker; no world-save fallback is used.
 */
public final class NativeRegionFiles {
    private static final ClassValue<CacheMethods> CACHES = new ClassValue<CacheMethods>() {
        @Override protected CacheMethods computeValue(Class<?> type) { return new CacheMethods(type); }
    };
    private static final ClassValue<FileMethods> FILES = new ClassValue<FileMethods>() {
        @Override protected FileMethods computeValue(Class<?> type) { return new FileMethods(type); }
    };

    private NativeRegionFiles() { }

    public static Object existing(Object cache, int chunkX, int chunkZ) throws IOException {
        return invoke(CACHES.get(cache.getClass()).existing, cache, chunkX, chunkZ);
    }

    public static Object loaded(Object cache, int chunkX, int chunkZ) throws IOException {
        return invoke(CACHES.get(cache.getClass()).loaded, cache, chunkX, chunkZ);
    }

    public static boolean hasChunk(Object regionFile, Object nativeChunkPosition) throws IOException {
        return (Boolean) invoke(FILES.get(regionFile.getClass()).hasChunk, regionFile, nativeChunkPosition);
    }

    /** A flush failure propagates to the write acknowledgement rather than becoming success. */
    public static void flush(Object regionFile) throws IOException {
        invoke(FILES.get(regionFile.getClass()).flush, regionFile);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws IOException {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException("Native region-file operation failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            throw new IOException("Native region-file ABI invocation failed: " + method.getName(), error);
        }
    }

    private static final class CacheMethods {
        final Method existing, loaded;
        CacheMethods(Class<?> type) {
            try {
                // Reflection resolves by name and parameters, independently of
                // the RegionFile versus IRegionFile JVM return descriptor.
                existing = type.getMethod("moonrise$getRegionFileIfExists", int.class, int.class);
                loaded = type.getMethod("moonrise$getRegionFileIfLoaded", int.class, int.class);
                for (Method method : new Method[] { existing, loaded })
                    if (Modifier.isStatic(method.getModifiers()) || method.getReturnType().isPrimitive())
                        throw new NoSuchMethodException("Expected an instance region-file cache getter");
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unsupported native region-file cache ABI: " + type.getName(), error);
            }
        }
    }

    private static final class FileMethods {
        final Method hasChunk, flush;
        FileMethods(Class<?> type) {
            try {
                Method found = null;
                for (Method method : type.getMethods()) {
                    if (!"hasChunk".equals(method.getName()) || method.getParameterTypes().length != 1 ||
                            method.getReturnType() != boolean.class || Modifier.isStatic(method.getModifiers())) continue;
                    if (found != null && !found.getParameterTypes()[0].equals(method.getParameterTypes()[0]))
                        throw new NoSuchMethodException("Ambiguous native region-file hasChunk overload");
                    found = method;
                }
                if (found == null) throw new NoSuchMethodException("hasChunk(ChunkPos)");
                hasChunk = found;
                flush = type.getMethod("flush");
                if (flush.getReturnType() != void.class || Modifier.isStatic(flush.getModifiers()))
                    throw new NoSuchMethodException("Expected an instance synchronous region-file flush");
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unsupported native region-file ABI: " + type.getName(), error);
            }
        }
    }
}
