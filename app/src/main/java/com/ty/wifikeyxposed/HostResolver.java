package com.ty.wifikeyxposed;

import android.content.pm.ApplicationInfo;
import android.util.AtomicFile;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.wrap.DexField;
import org.luckypray.dexkit.wrap.DexMethod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/** 只在安装阶段解析；关闭 Bridge 后回调持有本代 ClassLoader 解析的成员。 */
final class HostResolver implements AutoCloseable {
    private final ClassLoader mLoader;
    private final String mApk;
    private final Consumer<String> mLog;
    private final AtomicFile mCacheFile;
    private final Properties mCache = new Properties();
    private final Map<String, List<String>> mResolved = new HashMap<>();
    private final Map<String, RuntimeException> mFailures = new HashMap<>();
    private DexKitBridge mBridge;
    private HostRules mRules;
    private boolean mDirty;

    HostResolver(ApplicationInfo host, ClassLoader loader, Consumer<String> log) throws Exception {
        if (host == null || !"com.snda.wifilocating".equals(host.packageName)) {
            throw new IllegalArgumentException("Unexpected host");
        }
        // shortcut: 当前只验证 base DEX；出现 split/动态代码时先停用自适应，覆盖真实样本后再扩展。
        if (host.splitSourceDirs != null && host.splitSourceDirs.length != 0) {
            throw new IllegalStateException("Split APK requires separate validation");
        }
        mLoader = loader;
        mApk = host.sourceDir;
        mLog = log;
        mCacheFile = new AtomicFile(new File(host.dataDir, "cache/wifikey-symbols.properties"));
        String identity = HostRules.SCHEMA + ":" + fingerprint(new File(mApk));
        try (FileInputStream input = mCacheFile.openRead()) {
            mCache.load(input);
        } catch (IOException | IllegalArgumentException ignored) {
            mCache.clear();
        }
        if (!validCache(mCache, identity)) mCache.clear();
        mCache.setProperty("identity", identity);
    }

    Method method(String key) throws ReflectiveOperationException {
        return HostRules.unique(methods(key), key);
    }

    List<Method> methods(String key) throws ReflectiveOperationException {
        List<String> values = symbols(key);
        try {
            return restoreMethods(values);
        } catch (ReflectiveOperationException | RuntimeException e) {
            invalidate(key);
            return restoreMethods(symbols(key));
        }
    }

    private List<Method> restoreMethods(List<String> descriptors) throws ReflectiveOperationException {
        List<Method> result = new ArrayList<>();
        for (String descriptor : descriptors) {
            Method method = new DexMethod(descriptor).getMethodInstance(mLoader);
            if (!new DexMethod(method).serialize().equals(descriptor)) throw new NoSuchMethodException("Descriptor mismatch");
            method.setAccessible(true);
            result.add(method);
        }
        return result;
    }

    Field field(String key) throws ReflectiveOperationException {
        List<String> values = symbols(key);
        try {
            return restoreField(values);
        } catch (ReflectiveOperationException | RuntimeException e) {
            invalidate(key);
            return restoreField(symbols(key));
        }
    }

    private Field restoreField(List<String> descriptors) throws ReflectiveOperationException {
        String descriptor = HostRules.unique(descriptors, "field");
        Field field = new DexField(descriptor).getFieldInstance(mLoader);
        if (!new DexField(field).serialize().equals(descriptor)) throw new NoSuchFieldException("Descriptor mismatch");
        field.setAccessible(true);
        return field;
    }

    Class<?> type(String key) throws ClassNotFoundException {
        List<String> values = symbols(key);
        try {
            return mLoader.loadClass(HostRules.unique(values, key));
        } catch (ClassNotFoundException | RuntimeException e) {
            invalidate(key);
            return mLoader.loadClass(HostRules.unique(symbols(key), key));
        }
    }

    private List<String> symbols(String key) {
        if (mFailures.containsKey(key)) throw mFailures.get(key);
        if (mResolved.containsKey(key)) return mResolved.get(key);
        String cached = mCache.getProperty(key);
        if (cached != null && !cached.isEmpty()) {
            List<String> values = Arrays.asList(cached.split("\n"));
            mResolved.put(key, values);
            mLog.accept("Resolved " + key + " (cache): " + values);
            return values;
        }
        try {
            if (mBridge == null) {
                System.loadLibrary("dexkit");
                mBridge = DexKitBridge.create(mApk);
                mBridge.setThreadNum(2);
                mRules = new HostRules(mBridge);
            }
            List<String> values = mRules.resolve(key);
            mResolved.put(key, values);
            mCache.setProperty(key, String.join("\n", values));
            mDirty = true;
            mLog.accept("Resolved " + key + " (scan): " + values);
            return values;
        } catch (RuntimeException e) {
            mFailures.put(key, e);
            throw e;
        }
    }

    private void invalidate(String key) {
        mResolved.remove(key);
        mCache.remove(key);
        mDirty = true;
        mLog.accept("Rebuilding invalid symbol: " + key);
    }

    static String fingerprint(File apk) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static boolean validCache(Properties values, String identity) {
        return identity.equals(values.getProperty("identity"))
                && checksum(values).equals(values.getProperty("checksum"));
    }

    static String checksum(Properties values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : new java.util.TreeSet<>(values.stringPropertyNames())) {
                if (key.equals("checksum")) continue;
                digest.update((key + "\u0000" + values.getProperty(key) + "\u0000")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (mBridge != null) {
            mBridge.close();
            mBridge = null;
            mRules = null;
        }
        if (!mDirty) return;
        FileOutputStream output = null;
        try {
            mCache.setProperty("checksum", checksum(mCache));
            output = mCacheFile.startWrite();
            mCache.store(output, "WiFiKey resolved members");
            mCacheFile.finishWrite(output);
        } catch (IOException e) {
            mCacheFile.failWrite(output);
            mLog.accept("Symbol cache not saved: " + e.getClass().getSimpleName());
        }
    }
}
