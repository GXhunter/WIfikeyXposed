package com.ty.wifikeyxposed;

/*
 * 用户需求核心摘要 (CONTEXT RESTORE):
 * 1. 目标应用：WiFi万能钥匙 5.2.13 (com.snda.wifilocating)
 * 2. 核心功能：本地 SVIP 永久解锁、全模块去广告 (开屏/列表/视频)、MD3E 设置界面。
 * 3. 创新功能：底栏极致精简，且实现状态双向同步。
 * 4. 逻辑增强：实时监控应用底栏开启情况。若应用隐藏了某项，则模块设置自动标记为“精简开启”。
 * 5. 交互增强：实现稳定版免 Root 重启机制、高风险操作倒计时二次确认。
 * 6. 验证流程：每次更改后构建 APK，通过 ADB 安装，重启目标应用，查看 LSPosed 日志。
 * 7. 行为规范：思考必须是中文，交流必须是中文。
 * 8. API 规范：100% 符合 libxposed API 101 规范。
 */

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.ViewGroup;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class MainHook extends XposedModule {
    private static final String TAG = "WiFiKeyXposed";
    private static final String TARGET_PACKAGE = "com.snda.wifilocating";
    private static final String ME_FRAGMENT_CLASS = "com.wifitutu.ui.me.MeFragment";
    private static final String TEENAGER_MANAGER_CLASS = "com.wifitutu.link.foundation.sdk.d1";
    private static final String AD_STRATEGY_CLASS = "com.wifi.business.potocol.sdk.base.strategy.AdStrategy";
    private static final String REMOTE_CONFIG_INTERFACE = "com.link.ida.sdk.protocol.api.interfaces.IWfRemoteConfig";
    private static final String URI_CHECKER_CLASS = "com.wifitutu.ui.dialog.c";
    private static final String ACTION_RESTART = "com.ty.wifikeyxposed.ACTION_RESTART";
    private static final String ACTION_CLEAR_CLOUD = "com.ty.wifikeyxposed.ACTION_CLEAR_CLOUD";
    private static final String HOST_COMMAND_PERMISSION =
            "com.ty.wifikeyxposed.permission.SEND_HOST_COMMAND";

    /** 5.2.29 native bridge 生成类 (lib-native_release) — 全部为 native 方法, 汇聚了 app 所有 JNI 调用 */
    private static final String[] NATIVE_BRIDGE_CLASSES = {
        "C01cdb392bfd527d707b524edd53587f7",
        "C33770a53a0574b75dc315f193807bc22",
        "C39e27d40794a5115491979f02e84d758",
        "C401e8c36c253e3ffbcd603ae42d97d54",
        "C4cb43ce283b9ea38e0e83f91c723b73c",
        "C50a40ad32ea6b17ecc5a33fbf2fa5ff4",
        "C54c0d751e76a3797e06e9586b59fb298",
        "C614c73d440dcb3459b926b6c7b292529",
        "C7b63f721abfa003f272845668ac68dd5",
        "C7d0fe81c3cfafe692c9d6516ce6abfde",
        "C7dee84534cd55357b19a729eeec11c11",
        "C89416343e0371b16de8b18a5edef19c6",
        "C8aaf9350909bb9a0731c2bf84b6217dc",
        "C8d679743ba0ac35d9a94a30469ba9357",
        "C905caf5a8e5cd345b6e731d69d75f40e",
        "C95ce4b455bef784a668fb2a9b3a87a75",
        "C9959c85d22adcb32a0169d52a29ab1ef",
        "Ca27f912416b02e58733bb81b93f98525",
        "Cbc2052cbae0ffa2f2bab269bfc80a9bc",
        "Cc5df4aa1ae41cbde7731559adbf84a4f",
        "Cca6718b1bca29a3c5b32123190c33160",
        "Ccfc1ec8b5d1d858b73d5233600c3f596",
        "Cef07326a43da517076ba2932a8719640",
        "Cf56a349496d91586fe2379695e6911c7",
        "Cf80b9e64695edbfba0f05e2b07b0bd90",
        "Cfa6cb3197659a9a1102ae6a7c62bca73",
        "Cfd0bc440daf3432f0737c50edb24fb63",
        "Cfd1a480bd5cf9a179e44b8d253fa2521"
    };
    private static final String NATIVE_BRIDGE_PACKAGE = "com.wifitutu.link.foundation.native_.bridge.generated.";
    /** ajnixx 包 — jni_bind/jni_tick/jni_invoke 等 native 方法 (崩溃堆栈直接来源) */
    private static final String[] AJNIXX_BRIDGE_CLASSES = {
        "com.nnt.ajnixx.Activity$Companion",
        "com.nnt.ajnixx.MainThread$Companion",
        "com.nnt.ajnixx.Callback"
    };

    private Handler mainHandler;
    private boolean isPerformingForcedHide = false;
    /** 宿主 classLoader 与 native 库目录 — 热重载时通过 savedInstanceState 传递给新一代代码 (均为框架/boot 类加载器对象, 可安全跨代) */
    private ClassLoader hostClassLoader;
    private String hostNativeLibDir;

    public MainHook() {
        super();
    }

    private boolean isFeatureEnabled(String key, boolean def) {
        try {
            SharedPreferences sp = getRemotePreferences("settings");
            return sp.getBoolean(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private void updateFeatureState(String key, boolean value) {
        try {
            SharedPreferences sp = getRemotePreferences("settings");
            if (sp.getBoolean(key, !value) != value) {
                sp.edit().putBoolean(key, value).apply();
                log(4, TAG, "Auto-synced tab state: " + key + " -> " + value);
            }
        } catch (Exception e) {
            log(6, TAG, "Failed to update remote prefs: " + e.getMessage());
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        super.onPackageReady(param);
        if (!param.getPackageName().equals(TARGET_PACKAGE)) {
            return;
        }

        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }

        log(4, TAG, "Hooking into: " + param.getPackageName());

        hostClassLoader = param.getClassLoader();
        android.content.pm.ApplicationInfo info = param.getApplicationInfo();
        hostNativeLibDir = (info != null) ? info.nativeLibraryDir : null;
        installAllHooks(hostClassLoader, hostNativeLibDir);
    }

    /** 安装全部 hook — 首次加载与热重载后共用 */
    private void installAllHooks(ClassLoader classLoader, String nativeLibDir) {
        try {
            log(4, TAG, "=== 开始初始化 hook ===");
            // ★ 最先安装: Android 15+/16KB Page 设备上 5.2.29 启动即崩 (UnsatisfiedLinkError) 的兼容修复
            hookNativeBridgeCompat(classLoader, nativeLibDir);
            log(4, TAG, "hookNativeBridgeCompat 完成");
            hookVipStatus(classLoader);
            log(4, TAG, "hookVipStatus 完成");
            hookBottomNavigation(classLoader);
            log(4, TAG, "hookBottomNavigation 完成");
            hookOptionalHomeModules(classLoader);
            hookOptionalHomeTabs(classLoader);
            hookFloatingBall(classLoader);
            log(4, TAG, "hookFloatingBall 完成");
            hookFullscreenGuide(classLoader);
            log(4, TAG, "hookFullscreenGuide 完成");
            hookAppUpdate(classLoader);
            log(4, TAG, "hookAppUpdate 完成");
            hookHomeWidgets(classLoader);
            hookWifiMapRow(classLoader);
            log(4, TAG, "hookHomeWidgets 完成");
            hookHomeRootWatcher(classLoader);
            log(4, TAG, "hookHomeRootWatcher 完成");
            hookRnHomeView(classLoader);
            log(4, TAG, "hookRnHomeView 完成");
            // hookHomeDialogComponents 暂时禁用 — S1 hook 导致 NPE 崩溃
            // TODO: 需要分析 S1 返回值类型后再启用
            // hookHomeDialogComponents(classLoader);
            // log(4, TAG, "hookHomeDialogComponents 完成");
            hookPostConnectPromotions(classLoader);
            hookAds(classLoader);
            log(4, TAG, "hookAds 完成");
            hookCommonFlags(classLoader);
            log(4, TAG, "hookCommonFlags 完成");
            hookCloudControl(classLoader);
            log(4, TAG, "hookCloudControl 完成");
            hookTeenagerMode(classLoader);
            log(4, TAG, "hookTeenagerMode 完成");
            hookAntiTamper(classLoader);
            log(4, TAG, "hookAntiTamper 完成");
            hookWifiSilentDelete(classLoader);
            log(4, TAG, "hookWifiSilentDelete 完成");
            hookQuickSettingsBypass(classLoader);
            log(4, TAG, "hookQuickSettingsBypass 完成");
            hookHomeSpeedUpBadge(classLoader);
            hookMineRecentApps(classLoader);
            hookMeFragment(classLoader);
            log(4, TAG, "hookMeFragment 完成");
            log(4, TAG, "=== hook 初始化完成 ===");
            scheduleStartupHomeHides();
        } catch (Throwable e) {
            log(6, TAG, "Initialization error: " + e.getMessage());
        }
    }

    /**
     * API 102 热重载 — 旧代码即将退役 (运行在旧代码上)
     * 返回 true 前必须停掉模块拥有的线程/回调并释放对模块对象的引用
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        try {
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
            removeHomeWatch();
            isPerformingForcedHide = false;
            // ArrayList/String/PathClassLoader 均为 boot/framework 类加载器对象, 可安全跨代传递
            java.util.ArrayList<Object> state = new java.util.ArrayList<>();
            state.add(hostClassLoader);
            state.add(hostNativeLibDir);
            state.add(new java.util.WeakHashMap<>(mCollapsedHomeTools));
            mCollapsedHomeTools.clear();
            state.add(new java.util.WeakHashMap<>(mHiddenHomePromotions));
            mHiddenHomePromotions.clear();
            mHomeResizeTask = null;
            param.setSavedInstanceState(state);
            log(4, TAG, "Hot reloading: state saved, old generation retiring");
            return true;
        } catch (Throwable t) {
            log(6, TAG, "Hot reloading rejected: " + t.getMessage());
            return false;
        }
    }

    /**
     * API 102 热重载完成 — 新代码已加载 (运行在新代码上)
     * 包生命周期回调不会自动重放, 必须在此显式重装 hooks
     */
    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        super.onHotReloaded(param); // 默认实现: 卸载上一代全部 hook handles
        try {
            mainHandler = new Handler(Looper.getMainLooper());
            Object saved = param.getSavedInstanceState();
            if (saved instanceof java.util.List<?> && ((java.util.List<?>) saved).size() >= 2) {
                java.util.List<?> state = (java.util.List<?>) saved;
                hostClassLoader = (ClassLoader) state.get(0);
                hostNativeLibDir = (String) state.get(1);
                if (state.size() > 3 && state.get(3) instanceof java.util.Map) {
                    java.util.Map<?, ?> hidden = (java.util.Map<?, ?>) state.get(3);
                    mainHandler.post(() -> {
                        for (java.util.Map.Entry<?, ?> entry : hidden.entrySet()) {
                            if (entry.getKey() instanceof View && entry.getValue() instanceof int[]) {
                                mHiddenHomePromotions.put((View) entry.getKey(), (int[]) entry.getValue());
                            }
                        }
                    });
                }
                if (state.size() > 2 && state.get(2) instanceof java.util.Map) {
                    java.util.Map<?, ?> collapsed = (java.util.Map<?, ?>) state.get(2);
                    // 热重载回调在 Binder 线程，视图恢复必须回到主线程。
                    mainHandler.post(() -> {
                        for (java.util.Map.Entry<?, ?> entry : collapsed.entrySet()) {
                            if (entry.getKey() instanceof View && entry.getValue() instanceof Integer) {
                                View view = (View) entry.getKey();
                                if (!view.isAttachedToWindow()) continue;
                                view.setVisibility((Integer) entry.getValue());
                                refreshHomeToolsSize(view, view.getHeight());
                            }
                        }
                    });
                }
                log(4, TAG, "Hot reloaded: reinstalling hooks for " + TARGET_PACKAGE);
                installAllHooks(hostClassLoader, hostNativeLibDir);
            } else {
                log(6, TAG, "Hot reloaded but no saved state; hooks not reinstalled");
            }
        } catch (Throwable t) {
            log(6, TAG, "onHotReloaded error: " + t.getMessage());
        }
    }

    /**
     * 实现底栏精简功能及状态实时监控
     */
    private void hookBottomNavigation(ClassLoader classLoader) {
        // 核心 Hook A: 模仿青少年模式拦截 Tab 初始化 (Source of Truth)
        try {
            Class<?> extensionsKtCls = classLoader.loadClass("com.wifitutu.widget.maintab.ExtentionsKt");
            Method tabFilter = null;
            for (Method m : extensionsKtCls.getDeclaredMethods()) {
                if (!m.getName().equals("a") || !Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount() != 1 || !m.getReturnType().isEnum()) {
                    continue;
                }
                if (tabFilter != null) throw new IllegalStateException("Ambiguous tab filter");
                tabFilter = m;
            }
            if (tabFilter == null) throw new NoSuchMethodException("Tab filter");

            Method getId = tabFilter.getParameterTypes()[0].getMethod("getId");
            if (getId.getReturnType() != String.class) throw new NoSuchMethodException("String getId()");
            // 5.2.52 移动了枚举类，从实际方法签名取类型。
            Object teenagerEnum = Enum.valueOf(tabFilter.getReturnType().asSubclass(Enum.class), "TEENAGER");
            hook(tabFilter).intercept(chain -> {
                Object result = chain.proceed();
                if (result != null) return result; // 原生已拦截

                try {
                    String id = (String) getId.invoke(chain.getArgs().get(0));
                    String prefKey = mapTabIdToPrefKey(id);
                    if (prefKey != null && isFeatureEnabled(prefKey, false)) {
                        log(4, TAG, "Simplified bottom bar (Filter): " + id);
                        return teenagerEnum;
                    }
                } catch (Exception ignored) {}
                return null;
            });
            log(4, TAG, "Hooked tab filter: " + tabFilter);
        } catch (Exception e) {
            log(6, TAG, "Failed to hook ExtentionsKt.a: " + e.getMessage());
        }

        // 核心 Hook B: 拦截视频/广场/发现等硬编码开关
        try {
            Class<?> movieExtCls = classLoader.loadClass("com.wifitutu.extents.c");
            hook(movieExtCls.getDeclaredMethod("d")).intercept(chain -> {
                if (isFeatureEnabled("hide_tab_video", false)) return false;
                return chain.proceed();
            });
            hook(movieExtCls.getDeclaredMethod("b")).intercept(chain -> {
                if (isFeatureEnabled("hide_tab_nearby", false)) return false;
                return chain.proceed();
            });
        } catch (Exception ignored) {}

        // 核心 Hook C: 拦截 WebTab 开关
        try {
            Class<?> webTabCls = classLoader.loadClass("com.wifitutu.ui.web.d");
            hook(webTabCls.getDeclaredMethod("f")).intercept(chain -> {
                if (isFeatureEnabled("hide_tab_web", false)) return false;
                return chain.proceed();
            });
        } catch (Exception ignored) {}

        // 核心 Hook D: 强制压制 View 可见性 (Catch-all)
        try {
            Class<?> viewCls = classLoader.loadClass("android.view.View");
            Method setVisibilityMethod = viewCls.getMethod("setVisibility", int.class);

            hook(setVisibilityMethod).intercept(chain -> {
                if (isPerformingForcedHide) return chain.proceed();

                View view = (View) chain.getThisObject();
                int id = view.getId();
                if (id == View.NO_ID) return chain.proceed();

                String resName = null;
                try { resName = view.getResources().getResourceEntryName(id); } catch (Exception ignored) {}
                if (resName == null || !resName.startsWith("navigation_")) return chain.proceed();

                int requestedVisibility = (int) chain.getArgs().get(0);
                String prefKey = mapResNameToPrefKey(resName);
                if (prefKey == null) return chain.proceed();

                // 逻辑 A: 如果模块设置了隐藏，且应用尝试显示 -> 拦截并强制设为 GONE
                if (requestedVisibility == View.VISIBLE && isFeatureEnabled(prefKey, false)) {
                    log(4, TAG, "Blocking Visibility on tab: " + resName);
                    chain.getArgs().set(0, View.GONE); // 修改参数为 GONE
                    return chain.proceed();
                }

                // 逻辑 B: 实时监控。如果应用自己设为 GONE -> 自动同步模块开关为 ON (Hidden)
                if (requestedVisibility == View.GONE) {
                    updateFeatureState(prefKey, true);
                }

                return chain.proceed();
            });

            // 生命周期刷新 (二次压制)
            Class<?> mainActivityCls = classLoader.loadClass("com.wifitutu.ui.main.MainActivity");
            Method onResumeMethod = mainActivityCls.getDeclaredMethod("onResume");
            hook(onResumeMethod).intercept(chain -> {
                Object result = chain.proceed();
                mainHandler.postDelayed(() -> refreshBottomTabs(chain.getThisObject()), 1500);
                return result;
            });

        } catch (Exception e) {
            log(6, TAG, "Failed to hook BottomNavigation catch-all: " + e.getMessage());
        }
    }

    private String mapTabIdToPrefKey(String id) {
        if (id == null) return null;
        switch (id) {
            case "connect": return "hide_tab_home";
            case "nearby": return "hide_tab_nearby";
            case "movie": return "hide_tab_video";
            case "welfare": return "hide_tab_welfare";
            case "im": return "hide_tab_im";
            case "guard": return "hide_tab_guard";
            case "mine": return "hide_tab_me";
            // v5.2.18 新增底栏 Tab
            case "deepseek": return "hide_tab_deepseek";
            case "shopmall": return "hide_tab_shopmall";
            case "bus": return "hide_tab_bus";
            case "film": return "hide_tab_film";
            case "ai": return "hide_tab_ai";
            case "kouxin": return "hide_tab_kouxin";
            case "game": return "hide_tab_game";
            default: return null;
        }
    }

    private String mapResNameToPrefKey(String resName) {
        switch (resName) {
            case "navigation_home": return "hide_tab_home";
            case "navigation_nearby": return "hide_tab_nearby";
            case "navigation_video": return "hide_tab_video";
            case "navigation_welfare": return "hide_tab_welfare";
            case "navigation_im": return "hide_tab_im";
            case "navigation_web": return "hide_tab_web";
            case "navigation_guard": return "hide_tab_guard";
            case "navigation_me": return "hide_tab_me";
            case "navigation_kouxin": return "hide_tab_kouxin";
            default: return null;
        }
    }

    private void refreshBottomTabs(Object mainActivity) {
        try {
            isPerformingForcedHide = true;
            android.app.Activity activity = (android.app.Activity) mainActivity;
            View root = activity.getWindow().getDecorView();
            String[] tabNames = {
                "navigation_home", "navigation_nearby", "navigation_video", 
                "navigation_welfare", "navigation_im", "navigation_web", 
                "navigation_guard", "navigation_me", "navigation_kouxin"
            };
            
            for (String name : tabNames) {
                int id = activity.getResources().getIdentifier(name, "id", TARGET_PACKAGE);
                if (id != 0) {
                    View v = root.findViewById(id);
                    if (v != null) {
                        String prefKey = mapResNameToPrefKey(name);
                        if (isFeatureEnabled(prefKey, false)) {
                            log(4, TAG, "Forcing GONE on tab: " + name);
                            v.setVisibility(View.GONE);
                        } else if (v.getVisibility() == View.GONE) {
                            updateFeatureState(prefKey, true);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            isPerformingForcedHide = false;
        }
    }

    private void hookHomeWidgets(ClassLoader classLoader) {
        if (!isAnyToolHideEnabled() && !isFeatureEnabled("remove_ads", false) && !isFeatureEnabled("hide_speed_up", false)) return;
        try {
            Class<?> homeDialogCls = classLoader.loadClass("com.wifitutu.ui.home.HomeDialog");
            Method n0Method = null;
            for (Method m : homeDialogCls.getDeclaredMethods()) {
                if (m.getName().equals("n0") && m.getParameterCount() == 7) {
                    n0Method = m;
                    break;
                }
            }
            if (n0Method != null) {
                hook(n0Method).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object dialog = chain.getThisObject();
                        Field bindingField = null;
                        for (Field f : dialog.getClass().getDeclaredFields()) {
                            if (f.getType().getName().contains("DialogHomeBinding")) {
                                bindingField = f;
                                break;
                            }
                        }
                        if (bindingField == null) bindingField = dialog.getClass().getDeclaredField("g");
                        bindingField.setAccessible(true);
                        Object binding = bindingField.get(dialog);
                        if (binding != null) {
                            Field rnField = binding.getClass().getField("rnLayout");
                            final android.view.View rnLayout = (android.view.View) rnField.get(binding);
                            // 用 getRoot 获取根 View，统一处理所有隐藏
                            try {
                                Method getRootMethod = binding.getClass().getMethod("getRoot");
                                final android.view.View rootView = (android.view.View) getRootMethod.invoke(binding);
                                if (rootView != null && rootView instanceof android.view.ViewGroup) {
                                    final android.view.ViewGroup rootVg = (android.view.ViewGroup) rootView;
                                    // 多次延迟隐藏
                                    rootVg.postDelayed(() -> hideSpecialViews(rootVg), 500);
                                    rootVg.postDelayed(() -> hideSpecialViews(rootVg), 2000);
                                    rootVg.postDelayed(() -> hideSpecialViews(rootVg), 5000);
                                    // OnGlobalLayoutListener 持续监控
                                    rootVg.getViewTreeObserver().addOnGlobalLayoutListener(
                                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                                            @Override
                                            public void onGlobalLayout() {
                                                hideSpecialViews(rootVg);
                                            }
                                        }
                                    );
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        log(6, TAG, "Error in tool hide: " + e.getMessage());
                    }
                    return result;
                });
                log(4, TAG, "Hooked HomeDialog.n0() for tool hide (v9)");
            }
        } catch (Exception e) {
            log(6, TAG, "Failed to hook HomeDialog.n0(): " + e.getMessage());
        }
    }

    private void hideToolsInView(android.view.View root) {
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup vg = (android.view.ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            android.view.View child = vg.getChildAt(i);
            // 检查 contentDescription
            if (child.getContentDescription() != null) {
                String desc = child.getContentDescription().toString().trim();
                String pk = mapToolDescToPrefKey(desc);
                if (pk != null && isFeatureEnabled(pk, false) && child.getVisibility() != android.view.View.GONE) {
                    child.setVisibility(android.view.View.GONE);
                    log(4, TAG, "Hid tool (desc): " + desc);
                }
            }
            // 检查自身是 TextView
            if (child instanceof android.widget.TextView) {
                String text = ((android.widget.TextView) child).getText().toString().trim();
                String pk = mapToolDescToPrefKey(text);
                if (pk != null && isFeatureEnabled(pk, false) && child.getVisibility() != android.view.View.GONE) {
                    child.setVisibility(android.view.View.GONE);
                    log(4, TAG, "Hid tool (text): " + text);
                }
            }
            // 检查子 View 的 TextView
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup cvg = (android.view.ViewGroup) child;
                boolean found = false;
                for (int j = 0; j < cvg.getChildCount(); j++) {
                    android.view.View sub = cvg.getChildAt(j);
                    if (sub instanceof android.widget.TextView) {
                        String text = ((android.widget.TextView) sub).getText().toString().trim();
                        String pk = mapToolDescToPrefKey(text);
                        if (pk != null && isFeatureEnabled(pk, false) && child.getVisibility() != android.view.View.GONE) {
                            child.setVisibility(android.view.View.GONE);
                            log(4, TAG, "Hid tool (child text): " + text);
                            found = true;
                            break;
                        }
                    }
                }
                // 递归遍历更深层
                if (!found) {
                    hideToolsInView(cvg);
                }
            }
        }
    }

    private void hideViewById(android.view.ViewGroup root, String resName) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            try {
                String entryName = child.getResources().getResourceEntryName(child.getId());
                if (resName.equals(entryName) && child.getVisibility() != android.view.View.GONE) {
                    child.setVisibility(android.view.View.GONE);
                }
            } catch (Exception ignored) {}
            if (child instanceof android.view.ViewGroup) {
                hideViewById((android.view.ViewGroup) child, resName);
            }
        }
    }


    /**
     * 首页金刚区(工具网格 rn_layout, 由 React Native 渲染)里的运营/推广位名称。
     * 这些格子与真实工具同处一个网格, 数据由服务端下发(getConnTools), 内容为购物/短视频/App 下载等广告位,
     * 既不属于 connect_tools_config 的工具项, 也没有资源 id, 只能在视图层按名称识别 —— 受 remove_ads 控制。
     */
    private static final String[] HOME_PROMO_NAMES = {
        "福利商城", "看看", "附近的人", "随身WiFi", "智能日历", "一句记账",
        "AI趣味照", "记加班", "记工簿", "今日运势", "消灭星星", "水果消消乐",
        "算 24 点", "算24点", "马戏团连线", "每日赛事", "每日数独", "打企鹅"
    };

    private boolean isHomePromo(String name) {
        if (name == null) return false;
        for (String promo : HOME_PROMO_NAMES) {
            if (promo.equals(name)) return true;
        }
        return false;
    }

    // 隐藏广告横幅、立即加速按钮、金刚区广告位等特殊 View
    private void hideSpecialViews(android.view.ViewGroup root) {
        // 广告横幅 + 首页金刚区广告位 — 受 remove_ads 控制
        if (isFeatureEnabled("remove_ads", false)) {
            try {
                hideViewById(root, "ad_banner");
            } catch (Throwable ignored) {}
            try {
                hideHomePromoItems(root);
            } catch (Throwable t) {
                log(6, TAG, "hideHomePromoItems error: " + t);
            }
        }
        // 立即加速 — 受 hide_speed_up 控制
        if (isFeatureEnabled("hide_speed_up", false)) {
            try {
                hideViewById(root, "speed_up_layout");
            } catch (Throwable ignored) {}
        }
        // 工具栏 — 受各工具开关控制
        if (isAnyToolHideEnabled()) {
            try {
                hideToolsInView(root);
            } catch (Throwable ignored) {}
        }
        collapseEmptyHomeTools(root);
        updateHomePromotions(root);
        // 右下角浮动广告气泡 (典典帮你支招 / 签到领现金) — 视图层兜底
        if (isAdBubbleBlocked()) {
            try {
                hideBubbleOverlay(root);
            } catch (Throwable t) {
                log(6, TAG, "hideBubbleOverlay error: " + t);
            }
        }
        // 底栏「游戏」标签 — 有开关时从 HomeTabLayout 里摘掉, 让底栏按剩余标签重新均分
        if (isFeatureEnabled("hide_tab_game", true)) {
            try {
                removeGameTab(root);
            } catch (Throwable t) {
                log(6, TAG, "removeGameTab error: " + t);
            }
        }
    }

    /**
     * 移除底栏「游戏」标签。
     * 标签列表由 HomeTabLayout.setTabs(List<ml0.i>) 灌入, 且通常在模块拿到控制权之前就已构建,
     * 因此这里走 HomeTabLayout 自带的 removeTabs(List<String>) —— 按名称找到 tab 再按 id 移除,
     * 比直接隐藏 View 更好: 剩下的标签会自动重新等分布局。
     */
    private void removeGameTab(android.view.View root) {
        try {
            android.view.View tabLayout = findViewByClassName(root, "com.wifitutu.widget.maintab.HomeTabLayout");
            if (tabLayout == null) return;

            Object tabs = tabLayout.getClass().getMethod("getTabs").invoke(tabLayout);
            if (!(tabs instanceof java.util.List)) return;

            String gameId = null;
            for (Object tab : (java.util.List<?>) tabs) {
                if (tab == null) continue;
                String name;
                try {
                    name = (String) tab.getClass().getMethod("getName").invoke(tab);
                } catch (Throwable ignored) {
                    continue;
                }
                if ("游戏".equals(name)) {
                    try {
                        gameId = (String) tab.getClass().getMethod("getId").invoke(tab);
                    } catch (Throwable ignored) {}
                    break;
                }
            }
            if (gameId == null) return;

            java.util.List<String> ids = new java.util.ArrayList<>();
            ids.add(gameId);
            tabLayout.getClass().getMethod("removeTabs", java.util.List.class).invoke(tabLayout, ids);
            log(4, TAG, "Removed bottom tab: " + gameId);
        } catch (Throwable t) {
            log(6, TAG, "removeGameTab failed: " + t);
        }
    }

    /** 按类名深度查找 View */
    private android.view.View findViewByClassName(android.view.View root, String className) {
        java.util.List<android.view.View> stack = new java.util.ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            android.view.View v = stack.remove(stack.size() - 1);
            if (className.equals(v.getClass().getName())) return v;
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    stack.add(g.getChildAt(i));
                }
            }
        }
        return null;
    }

    /** 隐藏金刚区里的运营/广告格子 (每格 = 图标 ViewGroup + 文字 TextView) */
    private void hideHomePromoItems(android.view.ViewGroup root) {
        java.util.List<android.view.View> stack = new java.util.ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            android.view.View v = stack.remove(stack.size() - 1);
            if (!(v instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            String label = gridItemLabel(group);
            if (label != null && isHomePromo(label)) {
                if (group.getVisibility() != android.view.View.GONE) {
                    group.setVisibility(android.view.View.GONE);
                    log(4, TAG, "Hid home promo: " + label);
                }
                continue; // 命中即整格隐藏, 无需再深入
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                stack.add(group.getChildAt(i));
            }
        }
    }

    private final java.util.Map<View, Integer> mCollapsedHomeTools = new java.util.WeakHashMap<>();
    private Boolean mSupportsHomeToolsResize;
    private final java.util.Map<View, int[]> mHiddenHomePromotions = new java.util.WeakHashMap<>();
    private Runnable mHomeResizeTask;

    private boolean supportsHomeLayout(Context context) {
        if (mSupportsHomeToolsResize == null) {
            try {
                mSupportsHomeToolsResize = context.getPackageManager()
                        .getPackageInfo(TARGET_PACKAGE, 0).getLongVersionCode() == 261104;
                if (!mSupportsHomeToolsResize) log(5, TAG, "Home layout adaptation requires host 261104");
            } catch (Exception e) {
                return false;
            }
        }
        return mSupportsHomeToolsResize;
    }

    private boolean setHomePromotionHidden(View view, boolean hidden, boolean collapseHeight) {
        if (view == null || view.getLayoutParams() == null) return false;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int[] original = mHiddenHomePromotions.get(view);
        if (!hidden) {
            if (original == null) return false;
            mHiddenHomePromotions.remove(view);
            if (view.getVisibility() == View.GONE) view.setVisibility(original[0]);
            if (collapseHeight && params.height == 0) {
                params.height = original[1];
                view.setLayoutParams(params);
            }
            return true;
        }
        if (view.getVisibility() == View.GONE && (!collapseHeight || params.height == 0)) return false;
        if (original == null) {
            mHiddenHomePromotions.put(view, new int[]{view.getVisibility(), params.height});
        } else if (view.getVisibility() != View.GONE) {
            original[0] = view.getVisibility();
        }
        view.setVisibility(View.GONE);
        if (collapseHeight && params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }
        return true;
    }

    private void updateHomePromotions(ViewGroup root) {
        if (!supportsHomeLayout(root.getContext())) return;
        int homeId = root.getResources().getIdentifier("layout_parent", "id", TARGET_PACKAGE);
        View home = homeId == 0 ? null : root.findViewById(homeId);
        if (home == null) return;
        int listId = home.getResources().getIdentifier("wifi_list", "id", TARGET_PACKAGE);
        View list = listId == 0 ? null : home.findViewById(listId);
        if (!(list instanceof ViewGroup)
                || !list.getClass().getName().equals("com.wifitutu_common.ui.WifiList")) return;
        boolean hidden = isFeatureEnabled("remove_ads", false);
        boolean resized = false;
        for (String name : new String[]{"state_layout", "crh_state_layout"}) {
            int id = home.getResources().getIdentifier(name, "id", TARGET_PACKAGE);
            if (id != 0) resized |= setHomePromotionHidden(home.findViewById(id), hidden, false);
        }
        ViewGroup rows = (ViewGroup) list;
        int mapId = home.getResources().getIdentifier("map_layout", "id", TARGET_PACKAGE);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (mapId != 0 && row.getId() == mapId) setHomePromotionHidden(row, hidden, true);
        }
        if (resized) {
            View tools = findViewByClassName(home, "com.wifitutu.ui.view.RnHomesView");
            if (tools != null) refreshHomeToolsSize(tools, tools.getVisibility() == View.GONE ? 0 : tools.getHeight());
        }
    }

    private void hookWifiMapRow(ClassLoader classLoader) {
        try {
            Class<?> adapter = classLoader.loadClass("com.wifitutu_common.ui.WifiList$WifiAdapter");
            Class<?> holder = classLoader.loadClass("com.wifitutu.tutu_monitor.monitor.BindingViewHolder");
            Method bind = adapter.getDeclaredMethod("o", holder, int.class);
            Method getBinding = holder.getMethod("g");
            Class<?> mapBinding = classLoader.loadClass("com.wifitutu_common.databinding.ItemWifiMapBinding");
            Field itemView = holder.getField("itemView");
            if (bind.getReturnType() != void.class || itemView.getType() != View.class
                    || !getBinding.getReturnType().isAssignableFrom(mapBinding)) {
                throw new NoSuchMethodException("Unexpected WiFi row binding signature");
            }
            hook(bind).intercept(chain -> {
                View row = (View) itemView.get(chain.getArgs().get(0));
                if (!supportsHomeLayout(row.getContext())) return chain.proceed();
                // 先归还上次绑定的布局属性，避免复用把零高度带到新的列表项。
                setHomePromotionHidden(row, false, true);
                Object result = chain.proceed();
                int mapId = row.getResources().getIdentifier("map_layout", "id", TARGET_PACKAGE);
                if (mapId != 0 && row.getId() == mapId
                        && mapBinding.isInstance(getBinding.invoke(chain.getArgs().get(0)))) {
                    setHomePromotionHidden(row, isFeatureEnabled("remove_ads", false), true);
                }
                return result;
            });
            log(4, TAG, "Hooked WiFi map row binding");
        } catch (Exception e) {
            log(5, TAG, "Failed to hook WiFi map row: " + e.getMessage());
        }
    }

    private void collapseEmptyHomeTools(ViewGroup root) {
        View tools = findViewByClassName(root, "com.wifitutu.ui.view.RnHomesView");
        if (!(tools instanceof ViewGroup)) return;
        if (!supportsHomeLayout(tools.getContext())) return;
        ViewGroup group = (ViewGroup) tools;
        if (group.getChildCount() == 0) return;
        boolean enabled = isFeatureEnabled("remove_ads", false) || isAnyToolHideEnabled();
        if (!enabled || hasVisibleToolContent(group)) {
            Integer visibility = mCollapsedHomeTools.remove(tools);
            if (visibility != null) {
                tools.setVisibility(visibility);
                refreshHomeToolsSize(tools, tools.getHeight());
            }
        } else if (tools.getVisibility() == View.VISIBLE) {
            // RN 隐藏子格子不会重算 Yoga 高度，空网格需连同外层占位一起收起。
            mCollapsedHomeTools.put(tools, tools.getVisibility());
            tools.setVisibility(View.GONE);
            refreshHomeToolsSize(tools, 0);
            log(4, TAG, "Collapsed empty home tools, height=" + tools.getHeight());
        }
    }

    private void refreshHomeToolsSize(View tools, int height) {
        if (mHomeResizeTask != null) mainHandler.removeCallbacks(mHomeResizeTask);
        mHomeResizeTask = () -> {
            mHomeResizeTask = null;
            if (!tools.isAttachedToWindow()) return;
            try {
                // 5.2.52 用此回调更新 defaultMarginTop 并重测 scrollLayout；GONE 不会触发 onSizeChanged。
                Field callbackField = tools.getClass().getDeclaredField("onSizeChanged");
                callbackField.setAccessible(true);
                Object callback = callbackField.get(tools);
                if (callback == null) return;
                callback.getClass().getMethod("invoke", Object.class, Object.class, Object.class, Object.class)
                        .invoke(callback, tools.getWidth(), height, tools.getWidth(), tools.getHeight());
            } catch (Exception e) {
                log(5, TAG, "Failed to refresh home tools size: " + e.getMessage());
            }
        };
        mainHandler.post(mHomeResizeTask);
    }

    private boolean hasVisibleToolContent(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            if (child instanceof ViewGroup) {
                if (hasVisibleToolContent((ViewGroup) child)) return true;
            } else if (!(child instanceof TextView)
                    || ((TextView) child).getText().length() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 识别金刚区单个格子: 恰有一个非空文字子 View + 至少一处图标; 非格子返回 null */
    private String gridItemLabel(android.view.ViewGroup group) {
        String text = null;
        int textCount = 0;
        boolean hasIcon = false;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence cs = ((TextView) child).getText();
                String t = cs == null ? "" : cs.toString().trim();
                if (!t.isEmpty()) {
                    text = t;
                    textCount++;
                }
            } else if (child instanceof android.widget.ImageView) {
                hasIcon = true;
            } else if (child instanceof android.view.ViewGroup && containsImageView((android.view.ViewGroup) child)) {
                hasIcon = true;
            }
        }
        if (!hasIcon || textCount != 1) return null;
        return text;
    }

    private boolean containsImageView(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child instanceof android.widget.ImageView) return true;
            if (child instanceof android.view.ViewGroup && containsImageView((android.view.ViewGroup) child)) return true;
        }
        return false;
    }

    /** 首页根 View 的布局监听 — 与 HomeDialog 绑定路径解耦, 保证金刚区每次重绘后都会重新应用隐藏 */
    private View mHomeWatchRoot;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener mHomeWatchListener;
    private final View.OnAttachStateChangeListener mHomeAttachListener = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {}

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (view == mHomeWatchRoot) removeHomeWatch();
        }
    };

    /**
     * HomeMainActivity 才是首页宿主 (com.wifitutu.ui.main.MainActivity 不是它的父类),
     * 而 HomeDialog.n0() 这条绑定路径在新版并非每次都会执行。
     * 直接把隐藏逻辑挂在首页 Activity 的根 View 上, 避免依赖 n0。
     */
    private void hookHomeRootWatcher(ClassLoader classLoader) {
        try {
            Class<?> mainCls = classLoader.loadClass("com.wifitutu.ui.main.HomeMainActivity");
            int hooked = 0;
            for (Method m : mainCls.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.equals("onResume") && !name.equals("onWindowFocusChanged")) continue;
                try {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        scheduleHomeHides(chain.getThisObject());
                        return result;
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            log(4, TAG, "Hooked HomeMainActivity lifecycle, count: " + hooked);
        } catch (Throwable e) {
            log(6, TAG, "Failed to hook HomeMainActivity: " + e.getMessage());
        }
    }

    private void scheduleHomeHides(final Object activity) {
        try {
            if (!(activity instanceof android.app.Activity)) return;
            final android.app.Activity act = (android.app.Activity) activity;
            mainHandler.postDelayed(() -> installHomeWatch(act), 600);
            mainHandler.postDelayed(() -> installHomeWatch(act), 2500);
            mainHandler.postDelayed(() -> installHomeWatch(act), 6000);
        } catch (Throwable t) {
            log(6, TAG, "scheduleHomeHides error: " + t);
        }
    }

    private void installHomeWatch(final android.app.Activity activity) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            final View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            installHomeWatch((ViewGroup) decor);
        } catch (Throwable t) {
            log(6, TAG, "installHomeWatch error: " + t);
        }
    }

    private void installHomeWatch(final ViewGroup root) {
        if (!root.isAttachedToWindow()) return;
        hideSpecialViews(root);
        if (root == mHomeWatchRoot) return;
        removeHomeWatch();
        mHomeWatchRoot = root;
        mHomeWatchListener = () -> {
            try {
                hideSpecialViews(root);
            } catch (Throwable ignored) {}
        };
        root.addOnAttachStateChangeListener(mHomeAttachListener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(mHomeWatchListener);
        log(4, TAG, "Home layout watcher installed");
    }

    /**
     * 金刚区容器 RnHomesView 构造时挂载隐藏任务。
     * 这条路径由布局 inflate 触发, 不依赖 HomeDialog 绑定/Activity 生命周期, 时序上最可靠。
     */
    private void hookRnHomeView(final ClassLoader classLoader) {
        try {
            Class<?> rnCls = classLoader.loadClass("com.wifitutu.ui.view.RnHomesView");
            int hooked = 0;
            for (Constructor<?> c : rnCls.getDeclaredConstructors()) {
                try {
                    hook(c).intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof android.view.View) {
                            scheduleHideFromView((android.view.View) self);
                        }
                        return result;
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            log(4, TAG, "Hooked RnHomesView constructors: " + hooked);
        } catch (Throwable t) {
            log(6, TAG, "hookRnHomeView failed: " + t);
        }
    }

    /** 从金刚区容器向上找到首页根 View, 多次延迟 + 布局监听地应用隐藏 */
    private void scheduleHideFromView(final android.view.View anchor) {
        try {
            Runnable pass = () -> {
                try {
                    if (!anchor.isAttachedToWindow()) return;
                    android.view.View root = anchor;
                    while (root.getParent() instanceof android.view.View) {
                        root = (android.view.View) root.getParent();
                    }
                    if (!(root instanceof android.view.ViewGroup)) return;
                    installHomeWatch((ViewGroup) root);
                } catch (Throwable t) {
                    log(6, TAG, "home hide pass error: " + t);
                }
            };
            // 统一由模块 Handler 持有，热重载时才能完整取消旧代任务。
            mainHandler.postDelayed(pass, 300);
            mainHandler.postDelayed(pass, 1200);
            mainHandler.postDelayed(pass, 3000);
            mainHandler.postDelayed(pass, 6000);
            mainHandler.postDelayed(pass, 10000);
        } catch (Throwable t) {
            log(6, TAG, "scheduleHideFromView failed: " + t);
        }
    }

    /**
     * 模块的 onPackageReady 在宿主 Application 创建后才回调, 首页布局此时往往已经 inflate 完成,
     * 因此构造函数/Hook 回调可能错过首个实例。这里主动从 ActivityThread 里找到当前首页 Activity,
     * 对已存在的 View 树立即应用隐藏。
     */
    private android.app.Activity findHomeActivity() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("currentActivityThread").invoke(null);
            Field f = atCls.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(at);
            if (map == null) return null;
            java.lang.reflect.Method valuesMethod = map.getClass().getMethod("values");
            java.util.Collection<?> records = (java.util.Collection<?>) valuesMethod.invoke(map);
            android.app.Activity fallback = null;
            for (Object rec : records) {
                if (rec == null) continue;
                Field af = rec.getClass().getDeclaredField("activity");
                af.setAccessible(true);
                Object a = af.get(rec);
                if (!(a instanceof android.app.Activity)) continue;
                android.app.Activity act = (android.app.Activity) a;
                if (act.isFinishing() || act.isDestroyed()) continue;
                if (act.getClass().getName().equals("com.wifitutu.ui.main.HomeMainActivity")) return act;
                if (fallback == null) fallback = act;
            }
            return fallback;
        } catch (Throwable t) {
            log(6, TAG, "findHomeActivity failed: " + t);
            return null;
        }
    }

    /** 安装 hook 后主动补一次首页隐藏 (覆盖 hook 前已创建的 View 树) */
    private void scheduleStartupHomeHides() {
        for (int delay : new int[]{1000, 3000, 6000, 12000}) {
            mainHandler.postDelayed(() -> {
                try {
                    android.app.Activity act = findHomeActivity();
                    if (act != null) installHomeWatch(act);
                } catch (Throwable ignored) {}
            }, delay);
        }
    }

    private void removeHomeWatch() {
        try {
            if (mHomeWatchRoot != null && mHomeWatchListener != null) {
                android.view.ViewTreeObserver observer = mHomeWatchRoot.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(mHomeWatchListener);
            }
        } catch (Throwable ignored) {
        } finally {
            if (mHomeWatchRoot != null) {
                mHomeWatchRoot.removeOnAttachStateChangeListener(mHomeAttachListener);
            }
            mHomeWatchRoot = null;
            mHomeWatchListener = null;
        }
    }

    private boolean isAnyToolHideEnabled() {
        String[] keys = {"hide_tool_clean", "hide_tool_speedup", "hide_tool_cooling",
            "hide_tool_speedtest", "hide_tool_network", "hide_tool_security",
            "hide_tool_kuaikan", "hide_tool_novel", "hide_tool_game", "hide_tool_more",
            "hide_tool_pieces", "hide_tool_douyin_coupon", "hide_tool_friend_msg"};
        for (String k : keys) {
            if (isFeatureEnabled(k, false)) return true;
        }
        return false;
    }

    private String findToolName(android.view.View view) {
        if (view.getContentDescription() != null) {
            String desc = view.getContentDescription().toString().trim();
            if (!desc.isEmpty()) return desc;
        }
        if (view instanceof android.widget.TextView) {
            String text = ((android.widget.TextView) view).getText().toString().trim();
            if (!text.isEmpty() && mapToolDescToPrefKey(text) != null) return text;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.View child = vg.getChildAt(i);
                if (child instanceof android.widget.TextView) {
                    String text = ((android.widget.TextView) child).getText().toString().trim();
                    if (!text.isEmpty() && mapToolDescToPrefKey(text) != null) return text;
                }
            }
        }
        return null;
    }

    private String mapToolDescToPrefKey(String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.equals("垃圾清理")) return "hide_tool_clean";
        if (name.equals("手机加速")) return "hide_tool_speedup";
        if (name.equals("手机降温")) return "hide_tool_cooling";
        if (name.equals("网络测速")) return "hide_tool_speedtest";
        if (name.equals("网络加速")) return "hide_tool_network";
        if (name.equals("安全检测")) return "hide_tool_security";
        if (name.contains("快看")) return "hide_tool_kuaikan";
        if (name.equals("免费小说")) return "hide_tool_novel";
        if (name.equals("游戏中心")) return "hide_tool_game";
        if (name.equals("更多")) return "hide_tool_more";
        if (name.equals("文件碎片")) return "hide_tool_pieces";
        if (name.contains("抖音优惠")) return "hide_tool_douyin_coupon";
        if (name.equals("好友消息")) return "hide_tool_friend_msg";
        return null;
    }

    private void hookHomeDialogComponents(ClassLoader classLoader) {
        try {
            Class<?> homeDialogCls = classLoader.loadClass("com.wifitutu.ui.home.HomeDialog");

            // S1 — IM 消息提醒 (多个重载)
            for (Method m : homeDialogCls.getDeclaredMethods()) {
                if (m.getName().equals("S1") && m.getParameterCount() > 0) {
                    final Method s1m = m;
                    Class<?> retType = m.getReturnType();
                    hook(s1m).intercept(chain -> {
                        if (isFeatureEnabled("hide_tool_im", false)) {
                            return retType == int.class ? 0 : (retType == boolean.class ? Boolean.FALSE : null);
                        }
                        Object r = chain.proceed();
                        return r != null ? r : (retType == int.class ? 0 : (retType == boolean.class ? Boolean.FALSE : null));
                    });
                    log(4, TAG, "Hooked HomeDialog.S1()");
                    break;
                }
            }

            // a1 — VIP 顶部入口
            try {
                hook(homeDialogCls.getDeclaredMethod("a1")).intercept(chain -> {
                    if (isFeatureEnabled("hide_tool_vip", false)) return null;
                    return chain.proceed();
                });
                log(4, TAG, "Hooked HomeDialog.a1() (VIP)");
            } catch (Exception ignored) {}

            // x1 — 用户个人信息布局
            try {
                hook(homeDialogCls.getDeclaredMethod("x1")).intercept(chain -> {
                    if (isFeatureEnabled("hide_tool_user", false)) return null;
                    return chain.proceed();
                });
                log(4, TAG, "Hooked HomeDialog.x1() (User)");
            } catch (Exception ignored) {}

            // I1 — 赋能面板
            try {
                hook(homeDialogCls.getDeclaredMethod("I1", homeDialogCls)).intercept(chain -> {
                    if (isFeatureEnabled("hide_tool_empower", false)) return null;
                    return chain.proceed();
                });
                log(4, TAG, "Hooked HomeDialog.I1() (Empower)");
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log(6, TAG, "Failed to hook HomeDialog components: " + e.getMessage());
        }
    }

    private String mapWidgetIdToPrefKey(String id) {
        if (id == null) return null;
        switch (id) {
            case "12": return "hide_tool_clean";     // 垃圾清理
            case "13": return "hide_tool_speedup";   // 手机加速
            case "14": return "hide_tool_cooling";   // 手机降温
            case "16": return "hide_tool_speedtest"; // 网络测速
            case "17": return "hide_tool_network";   // 网络加速
            case "18": return "hide_tool_security";  // 安全检测
            case "20": return "hide_tool_kuaikan";   // 快看
            case "56": return "hide_tool_novel";     // 免费小说
            case "60": return "hide_tool_game";      // 游戏中心
            case "100": return "hide_tool_more";     // 更多
            default: return null;
        }
    }

    private String mapOldWidgetIdToPrefKey(String id) {
        if (id == null) return null;
        switch (id) {
            case "501": return "hide_tool_clean";     // 垃圾清理
            case "502": return "hide_tool_speedup";   // 手机加速
            case "503": return "hide_tool_cooling";   // 手机降温
            case "505": return "hide_tool_speedtest"; // 网络测速
            case "504": return "hide_tool_pieces";    // 文件碎片
            default: return null;
        }
    }

    private void hookCloudControl(ClassLoader classLoader) {
        try {
            try {
                Class<?> remoteConfigCls = classLoader.loadClass(REMOTE_CONFIG_INTERFACE);
                for (Method m : remoteConfigCls.getDeclaredMethods()) {
                    if (m.getName().equals("getConfig")) {
                        hook(m).intercept(chain -> {
                            if (isFeatureEnabled("remove_cloud_control", false)) return null;
                            return chain.proceed();
                        });
                    }
                }
            } catch (Exception ignored) {}

            try {
                Class<?> adStrategyCls = classLoader.loadClass(AD_STRATEGY_CLASS);
                Method getTaiChiMethod = adStrategyCls.getDeclaredMethod("getTaiChiValue");
                hook(getTaiChiMethod).intercept(chain -> {
                    if (isFeatureEnabled("remove_cloud_control", false)) return "";
                    return chain.proceed();
                });
            } catch (Exception ignored) {}

            try {
                Class<?> uriCheckerCls = classLoader.loadClass(URI_CHECKER_CLASS);
                String[] checkMethods = {"h", "i", "k", "l", "m"};
                for (String name : checkMethods) {
                    try {
                        for (Method m : uriCheckerCls.getDeclaredMethods()) {
                            if (m.getName().equals(name) && m.getReturnType() == boolean.class) {
                                hook(m).intercept(chain -> {
                                    if (isFeatureEnabled("remove_cloud_control", false)) return true;
                                    return chain.proceed();
                                });
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            log(6, TAG, "Failed to hook CloudControl: " + e.getMessage());
        }
    }

    private void hookRestartLogic(ClassLoader classLoader) {
        try {
            Class<?> appClass = classLoader.loadClass("android.app.Application");
            Method onCreateMethod = appClass.getDeclaredMethod("onCreate");
            
            hook(onCreateMethod).intercept(chain -> {
                Object result = chain.proceed();
                final Context context = (Context) chain.getThisObject();
                
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (ACTION_RESTART.equals(action)) {
                            doSuicide();
                        } else if (ACTION_CLEAR_CLOUD.equals(action)) {
                            log(4, TAG, "Clearing cloud config files (no auto-restart)...");
                            clearCloudFiles(context);
                        }
                    }
                };
                
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_RESTART);
                filter.addAction(ACTION_CLEAR_CLOUD);
                // 当前 Context 属于宿主；只接收模块签名权限持有者发送的跨应用广播。
                androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter,
                        HOST_COMMAND_PERMISSION, null,
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
                return result;
            });
        } catch (Exception e) {
            log(6, TAG, "Failed to hook restart logic: " + e.getMessage());
        }
    }

    private void doSuicide() {
        log(4, TAG, "Suicide initiated. Killing " + Process.myPid());
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    private void clearCloudFiles(Context context) {
        try {
            File filesDir = context.getFilesDir();
            String[] configPaths = {"probe", "config", "strategy", "mmkv"};
            for (String path : configPaths) {
                deleteDir(new File(filesDir, path));
            }
            
            File spDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            if (spDir.exists()) {
                File[] files = spDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        if (name.contains("config") || name.contains("strategy") || name.contains("cloud")) {
                            boolean deleted = f.delete();
                            if (!deleted) log(4, TAG, "Could not delete: " + name);
                        }
                    }
                }
            }
            log(4, TAG, "Cloud config cleanup finished.");
        } catch (Exception e) {
            log(6, TAG, "Cleanup error: " + e.getMessage());
        }
    }

    private void deleteDir(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) deleteDir(f);
            }
        }
        boolean deleted = file.delete();
        if (!deleted) log(4, TAG, "Could not delete file/dir: " + file.getName());
    }

    private void hookTeenagerMode(ClassLoader classLoader) {
        try {
            Class<?> teenagerClass = classLoader.loadClass(TEENAGER_MANAGER_CLASS);

            // isRunning() → true，让 app 认为青少年模式已开启
            for (Method m : teenagerClass.getDeclaredMethods()) {
                if (m.getName().equals("isRunning") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("lite_teenager", false)) return true;
                        return chain.proceed();
                    });
                    log(4, TAG, "Hooked teenager.isRunning()");
                    break;
                }
            }

            // isLimited() → false，避免限时锁定
            for (Method m : teenagerClass.getDeclaredMethods()) {
                if (m.getName().equals("isLimited") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("lite_teenager", false)) return false;
                        return chain.proceed();
                    });
                    log(4, TAG, "Hooked teenager.isLimited()");
                    break;
                }
            }

            // open(pwd) → true，跳过密码验证
            for (Method m : teenagerClass.getDeclaredMethods()) {
                if (m.getName().equals("open") && m.getParameterCount() == 1 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("lite_teenager", false)) return true;
                        return chain.proceed();
                    });
                    log(4, TAG, "Hooked teenager.open()");
                    break;
                }
            }

            // Ge(pwd) → true，跳过密码验证（关闭青少年模式）
            for (Method m : teenagerClass.getDeclaredMethods()) {
                if (m.getName().equals("Ge") && m.getParameterCount() == 1 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("lite_teenager", false)) return true;
                        return chain.proceed();
                    });
                    log(4, TAG, "Hooked teenager.Ge()");
                    break;
                }
            }

        } catch (Exception e) {
            log(6, TAG, "Failed to hook TeenagerMode: " + e.getMessage());
        }
    }

    /**
     * 绕过 5.2.19 防篡改检测
     * 核心: AppManager.is_cheat_() (native) → sdk.i.Ye() → g0.Ye()
     * 拦截 Ye() 让它永远返回 false，app 就认为未被篡改
     */
    private void hookAntiTamper(ClassLoader classLoader) {
        // Hook sdk.i.Ye() — 这是 isCheat 的 Java 层入口
        try {
            Class<?> sdkICls = classLoader.loadClass("com.wifitutu.link.foundation.sdk.i");
            for (Method m : sdkICls.getDeclaredMethods()) {
                if (m.getName().equals("Ye") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        log(4, TAG, "Bypassed isCheat (Ye) → false");
                        return false;
                    });
                    log(4, TAG, "Hooked sdk.i.Ye() (anti-tamper bypass)");
                    break;
                }
            }
        } catch (Exception e) {
            log(6, TAG, "Failed to hook sdk.i.Ye(): " + e.getMessage());
        }

        // Hook g0.Ye() — 接口层兜底
        try {
            Class<?> g0Cls = classLoader.loadClass("com.wifitutu.link.foundation.core.g0");
            for (Method m : g0Cls.getDeclaredMethods()) {
                if (m.getName().equals("Ye") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        log(4, TAG, "Bypassed g0.Ye() → false");
                        return false;
                    });
                    log(4, TAG, "Hooked g0.Ye() (anti-tamper bypass)");
                    break;
                }
            }
        } catch (Exception e) {
            log(6, TAG, "Failed to hook g0.Ye(): " + e.getMessage());
        }

        // Hook AppManager.is_cheat_() — native 层绕过
        try {
            Class<?> appMgrCls = classLoader.loadClass("com.wifitutu.link.foundation.native_.AppManager");
            for (Method m : appMgrCls.getDeclaredMethods()) {
                if (m.getName().equals("is_cheat_") && m.getParameterCount() == 0) {
                    hook(m).intercept(chain -> {
                        log(4, TAG, "Bypassed is_cheat_() → false");
                        return false;
                    });
                    log(4, TAG, "Hooked AppManager.is_cheat_() (native anti-tamper)");
                    break;
                }
            }
        } catch (Exception e) {
            log(6, TAG, "Failed to hook is_cheat_: " + e.getMessage());
        }
    }

    /**
     * ★ Android 15+/16KB Page Size 启动崩溃兼容修复 (5.2.29)
     *
     * 崩溃链路 (崩溃报告.txt):
     *   TuTuApp.onCreate → l0.s() → c1.loadLibrary("link-foundation") 失败被 catch(Exception) 吞掉返回 false
     *   → l0.s() 不检查返回值继续调用 native bridge → Activity$Companion.jni_bind
     *   → UnsatisfiedLinkError: No implementation found → FATAL EXCEPTION
     *
     * 根因: app 自带的 ReLinker 加载链路在 Android 17 Canary 上失败 (so 本身可直接 dlopen);
     *       16KB 页设备上 4KB 对齐的 so 则彻底无法加载。
     *
     * 修复策略 (抢救优先, 短路兜底):
     *   1. 抢救: hook app 统一加载入口 c1.loadLibrary() — 放行原逻辑, 失败时改用宿主
     *      nativeLibraryDir 直接 System.load()。成功则 native 层完整可用 (网络加解密正常),
     *      且不预探测 (避免启动期 load 失败留下 linker 负缓存污染环境)。
     *   2. 兜底: 对全部 native bridge 安装条件守卫 — 仅当 sNativeBroken (抢救也失败) 时:
     *      - native 方法: 直接返回类型默认值, 绝不执行原实现
     *        (半加载状态下执行原实现会触发无法捕获的 native SIGSEGV)
     *      - Java 包装方法: 放行并吞 UnsatisfiedLinkError
     */
    private static volatile boolean sNativeBroken = false;

    private void hookNativeBridgeCompat(ClassLoader classLoader, String nativeLibDir) {
        if (!isFeatureEnabled("fix_native_bridge_crash", false)) return;

        final java.util.Set<String> logged = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
        final XposedInterface.Hooker nativeShortCircuit = new XposedInterface.Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                if (!sNativeBroken) return chain.proceed(); // 库可用: 完整放行, 保证网络等 native 功能
                logOnce(logged, chain.getExecutable());
                return defaultValueOf((Method) chain.getExecutable());
            }
        };
        final XposedInterface.Hooker javaGuard = new XposedInterface.Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                try {
                    return chain.proceed();
                } catch (UnsatisfiedLinkError e) {
                    logOnce(logged, chain.getExecutable());
                    return defaultValueOf((Method) chain.getExecutable());
                }
            }
        };

        int hooked = 0;
        for (String name : NATIVE_BRIDGE_CLASSES) {
            hooked += hookAllMethodsGuarded(classLoader, NATIVE_BRIDGE_PACKAGE + name, nativeShortCircuit, javaGuard);
        }
        for (String name : AJNIXX_BRIDGE_CLASSES) {
            hooked += hookAllMethodsGuarded(classLoader, name, nativeShortCircuit, javaGuard);
        }

        hookLibraryLoader(classLoader, nativeLibDir);
        log(4, TAG, "NativeBridge compat installed, methods hooked: " + hooked);
    }

    /** 抢救加载: 先用宿主 nativeLibraryDir 的正版 so 抢先注册 (幂等), 再放行 app 原加载逻辑 */
    private void hookLibraryLoader(ClassLoader classLoader, String nativeLibDir) {
        if (nativeLibDir == null) return;
        try {
            Class<?> c1Cls = classLoader.loadClass("com.wifitutu.link.foundation.sdk.c1");
            for (Method m : c1Cls.getDeclaredMethods()) {
                if (!m.getName().equals("loadLibrary") || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                hook(m).intercept(chain -> {
                    String name = (String) chain.getArgs().get(0);
                    File so = new File(nativeLibDir, "lib" + name + ".so");
                    boolean rescued = false;
                    if (so.exists()) {
                        // link-foundation 的 JNI_OnLoad 可能依赖 patch 库先行注册符号, 先加载 patch
                        if ("link-foundation".equals(name)) {
                            File patchSo = new File(nativeLibDir, "libpatch.so");
                            if (patchSo.exists()) {
                                try { System.load(patchSo.getAbsolutePath()); } catch (Throwable ignored) {}
                            }
                        }
                        // JNI_OnLoad 的 JNI_ERR 多为多进程并发/时序竞争, 重试通常可成
                        for (int attempt = 0; attempt < 3 && !rescued; attempt++) {
                            try {
                                if (attempt > 0) Thread.sleep(250L * attempt);
                                System.load(so.getAbsolutePath()); // 已加载则为 no-op
                                rescued = true;
                                log(4, TAG, "Preloaded native library: " + name
                                        + (attempt > 0 ? " (attempt " + (attempt + 1) + ")" : ""));
                            } catch (UnsatisfiedLinkError e) {
                                log(6, TAG, "Preload failed (" + name + ", attempt " + (attempt + 1) + "): " + e.getMessage());
                            } catch (InterruptedException ie) {
                                break;
                            }
                        }
                    }
                    Object r = chain.proceed(); // 幂等放行原逻辑
                    if (!rescued) {
                        // 正版路径彻底加载失败 → proceed 的"成功"不可信 (半初始化 so 会 SIGSEGV),
                        // 条件守卫转为短路模式保护后续所有 bridge 调用
                        sNativeBroken = true;
                        log(6, TAG, "Native layer unavailable (" + name + ") — short-circuit engaged");
                        return Boolean.FALSE;
                    }
                    return Boolean.TRUE;
                });
                log(4, TAG, "Hooked c1.loadLibrary rescue");
                break;
            }
        } catch (Throwable t) {
            log(6, TAG, "hookLibraryLoader failed: " + t.getMessage());
        }
    }

    private void logOnce(java.util.Set<String> logged, java.lang.reflect.Executable ex) {
        String key = ex.getDeclaringClass().getSimpleName() + "." + ex.getName();
        if (logged.add(key)) {
            log(6, TAG, "Native bridge degraded (no native layer): " + key);
        }
    }

    private int hookAllMethodsGuarded(ClassLoader classLoader, String className,
                                      XposedInterface.Hooker nativeHooker, XposedInterface.Hooker javaHooker) {
        int count = 0;
        try {
            Class<?> cls = classLoader.loadClass(className);
            for (Method m : cls.getDeclaredMethods()) {
                if (Modifier.isNative(m.getModifiers())) {
                    try { m.setAccessible(true); } catch (Exception ignored) {}
                }
                try {
                    hook(m).intercept(Modifier.isNative(m.getModifiers()) ? nativeHooker : javaHooker);
                    count++;
                } catch (Throwable t) {
                    log(6, TAG, "Skip guard on " + className + "." + m.getName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            log(6, TAG, "Bridge class not found (version drift?): " + className);
        }
        return count;
    }

    /** 按方法返回类型给出安全默认值 (String 给空串, 避免 Kotlin @NotNull 调用方 NPE) */
    private static Object defaultValueOf(Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive()) {
            if (r == String.class) return "";
            return null;
        }
        if (r == boolean.class) return Boolean.FALSE;
        if (r == long.class) return Long.valueOf(0L);
        if (r == float.class) return Float.valueOf(0.0f);
        if (r == double.class) return Double.valueOf(0.0d);
        if (r == short.class) return Short.valueOf((short) 0);
        if (r == byte.class) return Byte.valueOf((byte) 0);
        if (r == char.class) return Character.valueOf('\0');
        return Integer.valueOf(0);
    }

    /**
     * 5.2.52（261104，pg0.c 变体）：正常联网后不进入自动加速或推广跳转。
     * 保留连接结果、认证门户和用户主动打开的加速工具。
     */
    private void hookPostConnectPromotions(ClassLoader classLoader) {
        try {
            Class<?> dialogType = classLoader.loadClass("com.wifitutu.ui.dialog.ConnectDialog");
            Class<?> stateType = classLoader.loadClass("com.wifitutu.widget.core.ConnProcessState");
            Class<?> resultType = classLoader.loadClass("l80.o");
            Class<?> appType = classLoader.loadClass("com.wifitutu.ui.launcher.TuTuApp");
            Class<?> redirectType = classLoader.loadClass("pg0.c");
            Method showSpeedLayout = dialogType.getDeclaredMethod("w1", Long.class);
            Method launchSpeedUp = dialogType.getDeclaredMethod("h1");
            Method updateState = dialogType.getDeclaredMethod("E1", stateType);
            Method succeeded = resultType.getMethod("b");
            Method redirectAfterConnect = redirectType.getDeclaredMethod("q", String.class);
            Field connectResult = dialogType.getDeclaredField("c0");
            Field resultFlags = dialogType.getDeclaredField("q0");
            Field portalFlag = dialogType.getDeclaredField("m0");
            Field currentApp = appType.getDeclaredField("o");
            Field available = stateType.getField("CON_NET_AVAILABLE");
            if (!android.app.Dialog.class.isAssignableFrom(dialogType)
                    || !android.app.Application.class.isAssignableFrom(appType)
                    || !stateType.isEnum()
                    || showSpeedLayout.getReturnType() != void.class
                    || launchSpeedUp.getReturnType() != void.class
                    || updateState.getReturnType() != void.class
                    || succeeded.getReturnType() != boolean.class
                    || redirectAfterConnect.getReturnType() != boolean.class
                    || connectResult.getType() != resultType
                    || resultFlags.getType() != int.class || portalFlag.getType() != int.class
                    || currentApp.getType() != appType || !Modifier.isStatic(currentApp.getModifiers())
                    || !available.isEnumConstant()) {
                throw new NoSuchMethodException("Unexpected post-connect signatures");
            }

            Object netAvailable = available.get(null);
            hook(showSpeedLayout).intercept(chain -> {
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                if (!supportsPostConnectPromotions(dialog.getContext())
                        || !isPostConnectSuccess(dialog, connectResult, succeeded, resultFlags, portalFlag)) {
                    return chain.proceed();
                }
                if (dialog.isShowing()) {
                    updateState.invoke(dialog, netAvailable);
                    dialog.dismiss();
                }
                log(4, TAG, "Skipped automatic WiFi speed-up");
                return null;
            });
            // 有些引导关闭后会直接调用 h1，不经过 w1 的动画。
            hook(launchSpeedUp).intercept(chain -> {
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                if (supportsPostConnectPromotions(dialog.getContext())
                        && isPostConnectSuccess(dialog, connectResult, succeeded, resultFlags, portalFlag)) {
                    return null;
                }
                return chain.proceed();
            });
            // 连接页与加速结果回调共用这个推广入口，不拦截一般页面跳转。
            hook(redirectAfterConnect).intercept(chain -> {
                Context app = (Context) currentApp.get(null);
                if (app != null && supportsPostConnectPromotions(app)) {
                    log(4, TAG, "Skipped WiFi post-connect promotion");
                    return false;
                }
                return chain.proceed();
            });
            log(4, TAG, "Hooked WiFi post-connect promotions");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Post-connect hooks not installed: " + e.getMessage());
        }
    }

    private boolean supportsPostConnectPromotions(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0)
                    .getLongVersionCode() == 261104;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isPostConnectSuccess(Object dialog, Field result, Method succeeded,
            Field resultFlags, Field portalFlag) throws ReflectiveOperationException {
        int flag = portalFlag.getInt(dialog);
        if (flag != 4 || (resultFlags.getInt(dialog) & flag) != 0) return false;
        Object connection = result.get(dialog);
        return connection != null && Boolean.TRUE.equals(succeeded.invoke(connection));
    }

    /** 5.2.52 原生连接弹窗：仅收起顶部推广，不隐藏共用容器或蓝牙提示。 */
    private void hookConnectDialogBanner(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass("com.wifitutu.ui.dialog.ConnectDialog");
            Method create = type.getDeclaredMethod("onCreate", android.os.Bundle.class);
            Method applyVipSkin = type.getDeclaredMethod("o1");
            Method openVipPromotion = type.getDeclaredMethod("E0");
            if (!android.app.Dialog.class.isAssignableFrom(type)
                    || create.getReturnType() != void.class
                    || applyVipSkin.getReturnType() != void.class
                    || openVipPromotion.getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Unexpected connect dialog signatures");
            }

            hook(applyVipSkin).intercept(chain -> {
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                if (shouldHideConnectDialogBanner(dialog)) return null;
                return chain.proceed();
            });
            hook(openVipPromotion).intercept(chain -> {
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                if (shouldHideConnectDialogBanner(dialog)) return false;
                return chain.proceed();
            });
            hook(create).intercept(chain -> {
                Object result = chain.proceed();
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                if (!shouldHideConnectDialogBanner(dialog)) return result;

                android.content.res.Resources res = dialog.getContext().getResources();
                int bannerId = res.getIdentifier("connect_bg", "id", TARGET_PACKAGE);
                int containerId = res.getIdentifier("ad_layout", "id", TARGET_PACKAGE);
                if (bannerId == 0 || containerId == 0) return result;
                View banner = dialog.findViewById(bannerId);
                View container = dialog.findViewById(containerId);
                // ad_layout 也承载自动切网提示，不能隐藏整个 FrameLayout。
                if (banner instanceof android.widget.ImageView && container != null
                        && banner.getParent() == container && !"askBleSwitch".equals(banner.getTag())) {
                    banner.setOnClickListener(null);
                    banner.setClickable(false);
                    banner.setVisibility(View.GONE);
                    log(4, TAG, "Hidden WiFi connect dialog banner");
                }
                return result;
            });
            log(4, TAG, "Hooked WiFi connect dialog banner");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Connect dialog banner hooks not installed: " + e.getMessage());
        }
    }

    private boolean shouldHideConnectDialogBanner(android.app.Dialog dialog) {
        return isFeatureEnabled("remove_ads", false)
                && supportsPostConnectPromotions(dialog.getContext());
    }

    private void hookAds(ClassLoader classLoader) {
        hookConnectDialogBanner(classLoader);
        try {
            Class<?> launcherClass = classLoader.loadClass("com.wifitutu.ui.launcher.LauncherActivity");
            Method loadSplashAd = launcherClass.getDeclaredMethod("S0", int.class, int.class, boolean.class);
            Field noSplash = launcherClass.getDeclaredField("d0");
            if (loadSplashAd.getReturnType() != void.class || noSplash.getType() != boolean.class) {
                throw new IllegalStateException("Unexpected splash ad signature");
            }
            noSplash.setAccessible(true);
            hook(loadSplashAd).intercept(chain -> {
                if (!isFeatureEnabled("remove_ads", false)) return chain.proceed();
                android.app.Activity launcher = (android.app.Activity) chain.getThisObject();
                // 5.2.52 的 d0 是 noSplash，宿主会自行结束开屏并继续启动。
                if (launcher.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0).getLongVersionCode() != 261104) {
                    return chain.proceed();
                }
                boolean original = noSplash.getBoolean(launcher);
                noSplash.setBoolean(launcher, true);
                try {
                    log(4, TAG, "Skipping splash ad load");
                    return chain.proceed();
                } finally {
                    noSplash.setBoolean(launcher, original);
                }
            });
        } catch (Exception e) {
            log(5, TAG, "Failed to hook splash ads: " + e.getMessage());
        }

        try {
            Class<?> abstractAdsClass = classLoader.loadClass("com.wifi.business.potocol.sdk.base.ad.AbstractAds");
            for (Method m : abstractAdsClass.getDeclaredMethods()) {
                if (m.getName().equals("isBlocked") && m.getParameterCount() == 0) {
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("remove_ads", false)) return true;
                        return chain.proceed();
                    });
                }
            }
        } catch (Exception ignored) {}

        try {
            Class<?> adStrategyClass = classLoader.loadClass("com.wifi.business.potocol.sdk.base.strategy.AdStrategy");
            for (Method m : adStrategyClass.getDeclaredMethods()) {
                if (m.getName().equals("getBlock") && m.getParameterCount() == 0) {
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("remove_ads", false)) return true;
                        return chain.proceed();
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    /** 隐藏首页加速推广，保留复用同一控件的认证和网络状态提示。 */
    private void hookHomeSpeedUpBadge(ClassLoader classLoader) {
        try {
            Class<?> home = classLoader.loadClass("com.wifitutu.ui.home.HomeDialog");
            Class<?> bindingType = classLoader.loadClass("com.wifitutu.databinding.DialogHomeBinding");
            Method render = home.getDeclaredMethod("l2");
            Field binding = home.getDeclaredField("g");
            Field currentAction = bindingType.getField("currentAction");
            if (render.getReturnType() != void.class || binding.getType() != bindingType
                    || currentAction.getType() != TextView.class) {
                throw new NoSuchMethodException("Unexpected home action binding");
            }
            hook(render).intercept(chain -> {
                Object result = chain.proceed();
                Object bound = binding.get(chain.getThisObject());
                TextView action = bound != null ? (TextView) currentAction.get(bound) : null;
                if (action != null && "该WiFi可加速".contentEquals(action.getText())
                        && supportsPostConnectPromotions(action.getContext())
                        && action.getVisibility() != View.GONE) {
                    action.setVisibility(View.GONE);
                    log(4, TAG, "Hidden WiFi acceleration badge");
                }
                return result;
            });
            log(4, TAG, "Hooked WiFi acceleration badge");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "WiFi acceleration badge hook not installed: " + e.getMessage());
        }
    }

    /** XML 绑定需要保留控件实例；跳过此实例刷新，不读取历史、不绑定推广列表。 */
    private void hookMineRecentApps(ClassLoader classLoader) {
        try {
            Class<?> recent = classLoader.loadClass("com.wifitutu.ui.liteapp.view.RecentUsedAppView");
            Method refresh = recent.getDeclaredMethod("refresh");
            if (!View.class.isAssignableFrom(recent) || refresh.getReturnType() != void.class) {
                throw new NoSuchMethodException("Unexpected recent apps refresh");
            }
            hook(refresh).intercept(chain -> {
                View view = (View) chain.getThisObject();
                Context context = view.getContext();
                int mineId = context.getResources().getIdentifier("recent_used_view", "id", TARGET_PACKAGE);
                if (mineId == 0 || view.getId() != mineId || !supportsPostConnectPromotions(context)) {
                    return chain.proceed();
                }
                if (view.getVisibility() != View.GONE) {
                    view.setVisibility(View.GONE);
                    log(4, TAG, "Skipped Mine recent apps refresh");
                }
                return null;
            });
            log(4, TAG, "Hooked Mine recent apps refresh");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Mine recent apps hook not installed: " + e.getMessage());
        }
    }

    private void hookMeFragment(ClassLoader classLoader) {
        try {
            Class<?> meFragmentClass = classLoader.loadClass(ME_FRAGMENT_CLASS);

            // 5.2.29: 旧 a2/d2 方法已随混淆改名消失, 改挂生命周期方法作为注入时机:
            // onResume() / onHiddenChanged(boolean) / y0() — 统一负责注入模块设置入口 + VIP 横幅隐藏
            for (Method m : meFragmentClass.getDeclaredMethods()) {
                String name = m.getName();
                boolean injectPoint = name.equals("onResume") || name.equals("onHiddenChanged") || name.equals("y0");
                if (!injectPoint || m.getParameterCount() > 1) continue;
                try {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object frag = chain.getThisObject();
                            if (isFeatureEnabled("unlock_vip", false)) {
                                hideVipBanners(frag, isFeatureEnabled("deep_clean_vip", false));
                            }
                            if (isFeatureEnabled("block_update", true)) {
                                hideUpdateDot(frag);
                            }
                            injectCustomSettings(frag);
                        } catch (Exception ignored) {}
                        return result;
                    });
                } catch (Throwable ignored) {}
            }
            log(4, TAG, "Hooked MeFragment lifecycle (settings entry + vip banners)");
        } catch (Throwable e) {
            log(6, TAG, "Failed to hook MeFragment: " + e.getMessage());
        }
    }

    /**
     * 禁止更新。
     * 更新逻辑入口: ba0.e implements ll0.l1 → wq(Context, boolean) → 订阅版本检查并弹窗。
     * 手动「检查更新」(MeFragment.D0) 与自动检查都走这里, 因此在 wq 上拦截;
     * 另外兜底挂一次 MeFragment.D0, 防止宿主改实现类。
     */
    private void hookAppUpdate(ClassLoader classLoader) {
        try {
            Class<?> impl = classLoader.loadClass("ba0.e");
            for (Method m : impl.getDeclaredMethods()) {
                if (!m.getName().equals("wq") || m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != Context.class || m.getParameterTypes()[1] != boolean.class) continue;
                hook(m).intercept(chain -> {
                    if (isFeatureEnabled("block_update", true)) {
                        log(4, TAG, "Blocked app update check, auto=" + chain.getArgs().get(1));
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Hooked ba0.e.wq (app update)");
            }
        } catch (Throwable t) {
            log(6, TAG, "hookAppUpdate(wq) failed: " + t.getMessage());
        }

        try {
            Class<?> meCls = classLoader.loadClass(ME_FRAGMENT_CLASS);
            for (Method m : meCls.getDeclaredMethods()) {
                if (!m.getName().equals("D0") || m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != Context.class || m.getParameterTypes()[1] != boolean.class) continue;
                hook(m).intercept(chain -> {
                    if (isFeatureEnabled("block_update", true)) {
                        log(4, TAG, "Blocked manual update check");
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Hooked MeFragment.D0 (app update)");
            }
        } catch (Throwable t) {
            log(6, TAG, "hookAppUpdate(D0) failed: " + t.getMessage());
        }
    }

    /** 「我的」页隐藏"有新版本"红点 */
    private void hideUpdateDot(final Object meFragment) {
        try {
            final Object binding = findBindingField(meFragment);
            if (binding == null) return;
            final View dot = getFieldSafe(binding, "newVersion");
            if (dot != null) {
                mainHandler.post(() -> dot.setVisibility(View.GONE));
            }
        } catch (Throwable ignored) {}
    }

    private void hideVipBanners(Object meFragment, boolean deepClean) {
        try {
            final Object binding = findBindingField(meFragment);
            if (binding == null) return;
            
            String[] promos = {"regionVip", "regionMovieVip"};
            for (String name : promos) {
                final View v = getFieldSafe(binding, name);
                if (v != null) {
                    mainHandler.post(() -> v.setVisibility(View.GONE));
                }
            }
            
            if (deepClean) {
                String[] flags = {"vipFlag", "vipSepWifiFlag", "vipSepMovieFlag"};
                for (String name : flags) {
                    final View v = getFieldSafe(binding, name);
                    if (v != null) {
                        mainHandler.post(() -> v.setVisibility(View.GONE));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void injectCustomSettings(final Object meFragment) throws Exception {
        Object binding = findBindingField(meFragment);
        if (binding == null) return;

        final View anchor = getFieldSafe(binding, "aboutUs");
        if (anchor != null) {
            mainHandler.post(() -> {
                try { performInjection(anchor); } catch (Exception ignored) {}
            });
        } else {
            final View checkUpdate = getFieldSafe(binding, "checkUpdate");
            if (checkUpdate != null) {
                mainHandler.post(() -> {
                    try { performInjection(checkUpdate); } catch (Exception ignored) {}
                });
            }
        }
    }

    private void performInjection(View anchor) {
        final Context context = anchor.getContext();
        View p = (View) anchor.getParent();

        if (!(p instanceof LinearLayout)) {
            if (p != null && p.getParent() instanceof LinearLayout) {
                anchor = p;
                p = (View) anchor.getParent();
            } else {
                return;
            }
        }

        LinearLayout parent = (LinearLayout) p;
        if (parent.findViewWithTag("wifikey_xposed_entry") != null) return;

        // 直接克隆原生菜单项外观 (aboutUs / privacyPolicy 等同款 TextView):
        // 字号/颜色/字体/内边距/右侧箭头全部取自真实菜单项, 不再自己拼一个"外来户"样式
        TextView item = new TextView(context);
        item.setTag("wifikey_xposed_entry");
        item.setText("Wifi万能钥匙增强");

        if (anchor instanceof TextView) {
            TextView src = (TextView) anchor;
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
            item.setTextColor(src.getTextColors());
            item.setTypeface(src.getTypeface());
            item.setGravity(src.getGravity());
            item.setIncludeFontPadding(src.getIncludeFontPadding());
            item.setPadding(src.getPaddingLeft(), src.getPaddingTop(), src.getPaddingRight(), src.getPaddingBottom());
            android.graphics.drawable.Drawable[] arrows = src.getCompoundDrawablesRelative();
            android.graphics.drawable.Drawable arrow = cloneDrawable(arrows != null && arrows.length > 2 ? arrows[2] : null);
            if (arrow == null) {
                android.graphics.drawable.Drawable[] absArrows = src.getCompoundDrawables();
                arrow = cloneDrawable(absArrows != null && absArrows.length > 2 ? absArrows[2] : null);
            }
            if (arrow != null) {
                item.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
            } else {
                // 兜底: 直接用宿主自己的箭头资源 (与菜单项 @drawable/wkr_icon_arrow_white 一致)
                try {
                    int arrowId = context.getResources().getIdentifier("wkr_icon_arrow_white", "drawable", TARGET_PACKAGE);
                    if (arrowId != 0) {
                        item.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, arrowId, 0);
                    }
                } catch (Throwable ignored) {}
            }
            item.setBackground(cloneDrawable(src.getBackground()));
        } else {
            int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
            item.setPadding(pad, pad, pad, pad);
        }

        item.setClickable(true);
        if (anchor.getLayoutParams() != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(anchor.getLayoutParams());
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            item.setLayoutParams(lp);
        }
        item.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.ty.wifikeyxposed", "com.ty.wifikeyxposed.SettingsActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });

        // 放在菜单最前面
        parent.addView(item, 0);

        // 原生分隔线: 优先复制兄弟节点的分隔线(高/背景完全一致), 否则退回 @style/line 的 1.5dp + #f2f2f2
        View sampleDivider = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getClass() == View.class) {
                sampleDivider = child;
                break;
            }
        }
        View divider = new View(context);
        if (sampleDivider != null) {
            divider.setBackground(cloneDrawable(sampleDivider.getBackground()));
            divider.setLayoutParams(new LinearLayout.LayoutParams(sampleDivider.getLayoutParams()));
        } else {
            divider.setBackgroundColor(Color.parseColor("#f2f2f2"));
            int dividerHeight = Math.max(1, Math.round(1.5f * context.getResources().getDisplayMetrics().density));
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dividerHeight));
        }
        parent.addView(divider, 1);
    }

    /** 复用同款 Drawable (箭头/背景) 时克隆一份, 避免共享 ConstantState 导致状态串台 */
    private android.graphics.drawable.Drawable cloneDrawable(android.graphics.drawable.Drawable src) {
        if (src == null) return null;
        try {
            android.graphics.drawable.Drawable.ConstantState cs = src.getConstantState();
            return cs != null ? cs.newDrawable() : src;
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookVipStatus(ClassLoader classLoader) {
        if (!isFeatureEnabled("unlock_vip", false)) return;

        // v2 策略: 对所有 VIP 类的方法做泛化拦截 + l5.c/d 精确 hook
        // int→2, boolean→true/false, long→2035年, enum→SVIP/YEAR

        // v3 策略: 仅 hook m5.c/d 精确入口 (不再泛化拦截所有VIP方法，避免NPE崩溃)

        // ★ 关键: m5.c() / m5.d() — VIP/SVIP 最终判断入口 (5.2.19: 旧版 l5→m5)
        try {
            Class<?> m5Cls = classLoader.loadClass("com.wifitutu.link.foundation.core.m5");
            for (Method m : m5Cls.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("d") && m.getParameterCount() == 1 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        log(4, TAG, "Spoofed m5.d(isVIP) → true");
                        return true;
                    });
                    log(4, TAG, "Hooked m5.d() (isVIP check)");
                } else if (name.equals("c") && m.getParameterCount() == 1 && m.getReturnType() == boolean.class) {
                    hook(m).intercept(chain -> {
                        log(4, TAG, "Spoofed m5.c(isSVIP) → true");
                        return true;
                    });
                    log(4, TAG, "Hooked m5.c() (isSVIP check)");
                }
            }
        } catch (Throwable e) {
            log(6, TAG, "m5 hook failed: " + e.getMessage());
        }

        // BridgeUserVipInfo 构造后篡改字段
        try {
            Class<?> bviCls = classLoader.loadClass("com.wifitutu.link.foundation.native_.model.generate.vip.BridgeUserVipInfo");
            for (Constructor<?> c : bviCls.getDeclaredConstructors()) {
                hook(c).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result != null) {
                        try {
                            for (Field f : result.getClass().getDeclaredFields()) {
                                f.setAccessible(true);
                                String fn = f.getName().toLowerCase();
                                if (fn.equals("svip")) f.set(result, true);
                                if (fn.equals("expired")) f.set(result, false);
                                if (fn.equals("category")) f.set(result, 2);
                                if (fn.equals("autorenew")) f.set(result, true);
                                if (fn.equals("endtime")) f.set(result, 2082729600000L);
                                if (fn.equals("starttime")) f.set(result, 1700000000000L);
                            }
                        } catch (Exception ignored) {}
                    }
                    return result;
                });
            }
            log(4, TAG, "Hooked BridgeUserVipInfo constructors");
        } catch (Throwable ignored) {}

        log(4, TAG, "VIP hook v3 (m5.c/d + BridgeUserVipInfo)");
    }

    private void hookStorage(ClassLoader classLoader) {
        XposedInterface.Hooker storageHooker = new XposedInterface.Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                if (!isFeatureEnabled("unlock_vip", false)) return chain.proceed();
                String key = (String) chain.getArgs().get(0);
                if (key == null) return chain.proceed();
                String lowerKey = key.toLowerCase();
                String methodName = chain.getExecutable().getName();
                if (lowerKey.contains("vip") || lowerKey.contains("svip")) {
                    if (methodName.equals("getBool")) return true;
                    if (methodName.equals("getInt")) return 2;
                    if (methodName.equals("getLong")) return 2082729600000L;
                    if (methodName.equals("getString")) return "2";
                }
                return chain.proceed();
            }
        };

        String[] storageClasses = {
            "com.wifitutu.link.foundation.sdk.z0",
            "com.wifitutu.link.foundation.sdk.feature.l",
            "com.wifitutu.widget.feature.u"
        };

        for (String cls : storageClasses) {
            try {
                Class<?> clazz = classLoader.loadClass(cls);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().startsWith("get") && m.getParameterCount() > 0 && m.getParameterTypes()[0] == String.class) {
                        hook(m).intercept(storageHooker);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private void hookCommonFlags(ClassLoader classLoader) {
        try {
            Class<?> aClass = classLoader.loadClass("com.wifitutu.movie.core.utils.a");
            for (Method m : aClass.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                    hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                            if (!isFeatureEnabled("unlock_vip", false)) return chain.proceed();
                            String name = chain.getExecutable().getName();
                            if (name.equals("j") || name.equals("i")) return true;
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookPushNotifications(ClassLoader classLoader) {
        try {
            Class<?> pushHelperClass = classLoader.loadClass("com.wifitutu.wakeup.imp.malawi.push.a");
            Class<?> mwTaskModelClass = classLoader.loadClass("com.wifitutu.wakeup.imp.malawi.strategy.bean.MwTaskModel");
            Method vMethod = pushHelperClass.getDeclaredMethod("v", mwTaskModelClass);

            hook(vMethod).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (isFeatureEnabled("block_news", false)) return null;
                    return chain.proceed();
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * 拦截 WiFi 万能钥匙静默删除网络配置的行为
     * 1. block_wifi_clear_config: 连接前静默清除 App 配置的 WiFi 网络 (Companion.a)
     * 2. block_wifi_delete_model: 连接时先删后加的 useDeleteModel 模式 (x1.f)
     * 3. block_wifi_post_clean: 连接失败后静默清理 WiFi 配置 (x1.n)
     */
    private void hookWifiSilentDelete(ClassLoader classLoader) {
        try {
            Class<?> x1Cls = classLoader.loadClass("com.wifitutu.link.feature.wifi.x1");
            Class<?> companionCls = classLoader.loadClass("com.wifitutu.link.feature.wifi.x1$a");

            // Hook 1: 拦截 Companion.a(clearAppConfiged, ignoreWifiId)
            // 该方法在连接 WiFi 前会静默删除所有 App 自建的网络配置
            try {
                Method clearMethod = null;
                for (Method m : companionCls.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == boolean.class) {
                        clearMethod = m;
                        break;
                    }
                }
                if (clearMethod != null) {
                    final Method m = clearMethod;
                    hook(m).intercept(chain -> {
                        if (isFeatureEnabled("block_wifi_clear_config", false)) {
                            log(4, TAG, "Blocked: Companion.a (clearAppConfiged) — 阻止连接前静默清除网络配置");
                            chain.getArgs().set(0, false);
                        }
                        return chain.proceed();
                    });
                    log(4, TAG, "Hooked: x1$a.a(clearAppConfiged)");
                }
            } catch (Exception e) {
                log(6, TAG, "Failed to hook x1$a.a: " + e.getMessage());
            }

            // Hook 2: 拦截 x1.f() 中的 useDeleteModel 行为
            // 该方法在连接 WiFi 时会先删除旧配置再添加新配置
            try {
                Class<?> y2Cls = classLoader.loadClass("jz.x2");
                Class<?> wifiKeyModeCls = classLoader.loadClass("com.wifitutu.link.foundation.kernel.WIFI_KEY_MODE");
                Method fMethod = x1Cls.getDeclaredMethod("f", y2Cls, wifiKeyModeCls);

                hook(fMethod).intercept(chain -> {
                    if (isFeatureEnabled("block_wifi_delete_model", false)) {
                        try {
                            Object x1Instance = chain.getThisObject();
                            Field useDeleteField = x1Cls.getDeclaredField("useDeleteModel");
                            useDeleteField.setAccessible(true);
                            useDeleteField.set(x1Instance, false);
                            log(4, TAG, "Blocked: x1.f() useDeleteModel — 禁止先删后加连接模式");
                        } catch (Exception e) {
                            log(6, TAG, "Failed to disable useDeleteModel: " + e.getMessage());
                        }
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Hooked: x1.f() (useDeleteModel control)");
            } catch (Exception e) {
                log(6, TAG, "Failed to hook x1.f: " + e.getMessage());
            }

            // Hook 3: 拦截 x1.n() — 连接失败后静默清理 WiFi 配置
            try {
                Method nMethod = x1Cls.getDeclaredMethod("n");

                hook(nMethod).intercept(chain -> {
                    if (isFeatureEnabled("block_wifi_post_clean", false)) {
                        log(4, TAG, "Blocked: x1.n() — 阻止连接失败后静默清理网络配置");
                        return null;
                    }
                    return chain.proceed();
                });
                log(4, TAG, "Hooked: x1.n() (post-connect cleanup)");
            } catch (Exception e) {
                log(6, TAG, "Failed to hook x1.n: " + e.getMessage());
            }
        } catch (Exception e) {
            log(6, TAG, "Failed to hook WiFi silent delete: " + e.getMessage());
        }
    }

    /**
     * 绕过 QS 磁贴添加引导弹窗
     * 
     * 拦截流程分析：
     * 1. ConnectHandler.P() / HomeAction 调用 u3.b().Im(proc)
     * 2. f0.Im() 检查 ku() — 如果 QS 磁贴已添加则直接 proc.invoke() 继续连接
     * 3. 若 ku() 返回 false → 弹出 APP_QUICK_SETTINGS_GUIDE_BEFORE_CONNECT 引导弹窗
     * 4. 弹窗回调中检查 q7$b.Gb() — 已添加则继续，否则"连接取消"
     * 
     * Hook 策略：
     * - 主 Hook: com.wifitutu.widget.qs.feature.a.ku() → 始终返回 true (跳过弹窗)
     * - 安全网: com.wifitutu.widget.qs.mgr.a.Gb() → 始终返回 true (兜底弹窗回调)
     */
    private void hookQuickSettingsBypass(ClassLoader classLoader) {
        // 主 Hook: ku() 检查 QS 磁贴是否已添加 → 始终返回 true
        try {
            Class<?> qsFeatureCls = classLoader.loadClass("com.wifitutu.widget.qs.feature.a");
            Method kuMethod = qsFeatureCls.getDeclaredMethod("ku");
            hook(kuMethod).intercept(chain -> {
                if (isFeatureEnabled("bypass_qs_guide", false)) {
                    log(4, TAG, "Bypassed QS tile check (ku) → true");
                    return true;
                }
                return chain.proceed();
            });
            log(4, TAG, "Hooked: qs.feature.a.ku() (QS tile added check)");
        } catch (Exception e) {
            log(6, TAG, "Failed to hook ku(): " + e.getMessage());
        }

        // 安全网: qs.mgr.a.Gb() — 弹窗回调中的二次检查
        try {
            Class<?> qsMgrCls = classLoader.loadClass("com.wifitutu.widget.qs.mgr.a");
            Method gbMethod = qsMgrCls.getDeclaredMethod("Gb");
            hook(gbMethod).intercept(chain -> {
                if (isFeatureEnabled("bypass_qs_guide", false)) {
                    log(4, TAG, "Bypassed QS guide check (Gb) → true");
                    return true;
                }
                return chain.proceed();
            });
            log(4, TAG, "Hooked: qs.mgr.a.Gb() (QS guide dialog callback)");
        } catch (Exception e) {
            log(6, TAG, "Failed to hook Gb(): " + e.getMessage());
        }

        // 悬浮窗权限绕过: Hook permission.c.B0()，对 SYSTEM_ALERT_WINDOW 直接返回 true
        try {
            Class<?> permCheckerCls = classLoader.loadClass("com.wifitutu.link.foundation.kernel.permission.c");
            Method b0Method = permCheckerCls.getMethod("B0",
                    classLoader.loadClass("com.wifitutu.link.foundation.kernel.f6"));
            hook(b0Method).intercept(chain -> {
                if (isFeatureEnabled("bypass_overlay_guide", false)) {
                    try {
                        Object f6Obj = chain.getArgs().get(0);
                        // f6 的 target 字段存着权限 ID (混淆后字段名 "a")
                        Field targetField = f6Obj.getClass().getDeclaredField("a");
                        targetField.setAccessible(true);
                        String target = (String) targetField.get(f6Obj);
                        if ("android:system_alert_window".equals(target)) {
                            log(4, TAG, "Spoofed B0(SYSTEM_ALERT_WINDOW) → true");
                            return true;
                        }
                    } catch (Exception ex) {
                        log(6, TAG, "B0() field access error: " + ex.getMessage());
                    }
                }
                return chain.proceed();
            });
            log(4, TAG, "Hooked: permission.c.B0() (overlay permission bypass)");
        } catch (Exception e) {
            log(6, TAG, "Failed to hook permission.c.B0(): " + e.getMessage());
        }
    }

    /**
     * 屏蔽首页右下角浮动广告气泡 (com.wifitutu.ui.bubble.*)，包含「典典帮你支招」和「签到领现金」。
     *
     * 5.2.52 的真实结构: BubbleHostView(容器, 按 AB 开关 new BubbleView 或 BubbleViewV2),
     * 真正负责显示的是私有方法 refreshBubbleView()，数据入口是 fetchDataIfNecessary()。
     * 旧版只挂了 showBubbleIfOrNot / fetchData，这两个在 5.2.52 都不是实际执行路径 (后者根本不存在)，
     * 所以气泡依旧会出现。
     */
    /**
     * 5.2.52（261104，pg0.c 变体）：ca0.l.i() 将广告气泡交给首页生命周期注册器。
     * 拦截注册可避免创建组件实例；旧布局直接创建的气泡仍走原有屏蔽逻辑。
     */
    private void hookOptionalHomeModules(ClassLoader classLoader) {
        if (!TARGET_PACKAGE.equals(android.app.Application.getProcessName())) return;
        try {
            Class<?> registryType = classLoader.loadClass("an0.q");
            Class<?> registryInterface = classLoader.loadClass("ml0.g");
            Class<?> lifecycleType = classLoader.loadClass("ml0.f");
            Class<?> bubbleModule = classLoader.loadClass("ug0.a");
            Class<?> filmModule = classLoader.loadClass("ug0.d");
            Class<?> appType = classLoader.loadClass("com.wifitutu.ui.launcher.TuTuApp");
            Method register = registryType.getDeclaredMethod("a", Class.class);
            Field currentApp = appType.getDeclaredField("o");
            if (!registryInterface.isAssignableFrom(registryType)
                    || !lifecycleType.isAssignableFrom(bubbleModule)
                    || !lifecycleType.isAssignableFrom(filmModule)
                    || !android.app.Application.class.isAssignableFrom(appType)
                    || register.getReturnType() != boolean.class || Modifier.isStatic(register.getModifiers())
                    || currentApp.getType() != appType || !Modifier.isStatic(currentApp.getModifiers())) {
                throw new NoSuchMethodException("Unexpected home module registration signatures");
            }

            hook(register).intercept(chain -> {
                Object component = chain.getArgs().get(0);
                boolean blockBubble = component == bubbleModule && isAdBubbleBlocked();
                boolean blockFilm = component == filmModule && isOptionalHomeTabBlocked("film");
                if (blockBubble || blockFilm) {
                    Context app = (Context) currentApp.get(null);
                    if (app != null && supportsPostConnectPromotions(app)) {
                        log(4, TAG, blockBubble ? "Skipped registration of home ad bubble module"
                                : "Skipped registration of home film module");
                        return false;
                    }
                }
                return chain.proceed();
            });
            log(4, TAG, "Hooked optional home module registration");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Optional home module hook not installed: " + e.getMessage());
        }
    }

    /**
     * 5.2.52 首页：过滤已隐藏的非核心页签，避免配置入表和页面按需创建。
     * 不触碰连接、认证、安全、消息和模块设置入口，也不禁用共享 SDK。
     */
    private void hookOptionalHomeTabs(ClassLoader classLoader) {
        if (!TARGET_PACKAGE.equals(android.app.Application.getProcessName())) return;
        try {
            Class<?> configRegistry = classLoader.loadClass("an0.j");
            Class<?> tabLayout = classLoader.loadClass("com.wifitutu.widget.maintab.HomeTabLayout");
            Class<?> tabLayoutInterface = classLoader.loadClass("ml0.j");
            Class<?> tabItem = classLoader.loadClass("ml0.i");
            Class<?> appType = classLoader.loadClass("com.wifitutu.ui.launcher.TuTuApp");
            Field currentApp = appType.getDeclaredField("o");
            Method getId = tabItem.getMethod("getId");
            Method registerDynamic = configRegistry.getDeclaredMethod("a0", List.class);
            Method registerOverrides = configRegistry.getDeclaredMethod("c0", List.class);
            Method setTabs = tabLayout.getDeclaredMethod("setTabs", List.class, android.os.Bundle.class);
            if (!tabLayoutInterface.isAssignableFrom(tabLayout)
                    || !tabItem.isInterface() || getId.getReturnType() != String.class
                    || !android.app.Application.class.isAssignableFrom(appType)
                    || currentApp.getType() != appType || !Modifier.isStatic(currentApp.getModifiers())) {
                throw new NoSuchMethodException("Unexpected home tab types");
            }
            Method[] methods = {registerDynamic, registerOverrides, setTabs};
            for (Method method : methods) {
                if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) {
                    throw new NoSuchMethodException("Unexpected home tab registration signature");
                }
            }
            for (Method method : methods) {
                hook(method).intercept(chain -> {
                    Object arg = chain.getArgs().get(0);
                    if (arg instanceof List<?> && !((List<?>) arg).isEmpty()) {
                        Context app = (Context) currentApp.get(null);
                        if (app != null && supportsPostConnectPromotions(app)) {
                            List<?> original = (List<?>) arg;
                            List<?> filtered = filterOptionalHomeTabs(original, tabItem, getId);
                            if (filtered != original) {
                                Object[] args = chain.getArgs().toArray();
                                args[0] = filtered;
                                log(4, TAG, "Filtered optional home tabs before registration: "
                                        + (original.size() - filtered.size()));
                                return chain.proceed(args);
                            }
                        }
                    }
                    return chain.proceed();
                });
            }
            log(4, TAG, "Hooked optional home tab configuration");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Optional home tab hooks not installed: " + e.getMessage());
        }
    }

    private List<?> filterOptionalHomeTabs(List<?> original, Class<?> tabItem, Method getId)
            throws ReflectiveOperationException {
        List<Object> filtered = null;
        for (int i = 0; i < original.size(); i++) {
            Object item = original.get(i);
            String id = tabItem.isInstance(item) ? (String) getId.invoke(item) : null;
            if (isOptionalHomeTabBlocked(id)) {
                if (filtered == null) filtered = new ArrayList<>(original.subList(0, i));
            } else if (filtered != null) {
                filtered.add(item);
            }
        }
        return filtered == null ? original : filtered;
    }

    private boolean isOptionalHomeTabBlocked(String id) {
        if (id == null) return false;
        switch (id) {
            case "movie":
            case "film":
            case "nearby":
            case "welfare":
            case "deepseek":
            case "shopmall":
            case "bus":
            case "kouxin":
            case "game":
                return isFeatureEnabled(mapTabIdToPrefKey(id), false);
            default:
                return false;
        }
    }

    private void hookFloatingBall(ClassLoader classLoader) {
        String[] classes = {
            "com.wifitutu.ui.bubble.BubbleView",
            "com.wifitutu.ui.bubble.BubbleViewV2"
        };
        int hooked = 0;
        for (String className : classes) {
            try {
                Class<?> cls = classLoader.loadClass(className);
                for (Method m : cls.getDeclaredMethods()) {
                    String mn = m.getName();
                    if (m.getReturnType() != void.class) continue;
                    boolean target = mn.equals("refreshBubbleView")
                            || mn.equals("fetchDataIfNecessary")
                            || mn.equals("showBubbleIfOrNot")
                            || mn.equals("access$refreshBubbleView");
                    if (!target) continue;
                    try {
                        hook(m).intercept(chain -> {
                            if (isAdBubbleBlocked()) {
                                log(4, TAG, "Blocked ad bubble: " + chain.getExecutable().getName());
                                return null; // void 方法, 直接跳过显示/拉取
                            }
                            return chain.proceed();
                        });
                        hooked++;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        log(4, TAG, "hookFloatingBall installed: " + hooked);
    }

    /**
     * 屏蔽启动时的「恭喜!红包到账」VIP 红包弹窗。
     *
     * 它是广告 SDK 的全屏引导页 com.wifitutu.ad.imp.busi.entry.ui.FullscreenGuideActivity,
     * onCreate 从 Intent 里读 isVipGuide / vipPrice / vipCouponAmount 等参数, isVipGuide=true 即红包样式。
     * 命中后立刻 finish, 不显示该页。仅拦 VIP 红包样式, 不动其它全屏引导。
     */
    private void hookFullscreenGuide(ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass("com.wifitutu.ad.imp.busi.entry.ui.FullscreenGuideActivity");
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("onCreate") || m.getParameterCount() != 1) continue;
                try {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (isFeatureEnabled("remove_ads", false)
                                    && chain.getThisObject() instanceof android.app.Activity) {
                                android.app.Activity act = (android.app.Activity) chain.getThisObject();
                                android.content.Intent intent = act.getIntent();
                                if (intent != null
                                        && (intent.getBooleanExtra("isVipGuide", false)
                                            || intent.getIntExtra("vipCouponAmount", 0) > 0)) {
                                    log(4, TAG, "Blocked VIP red-packet guide ad");
                                    act.finish();
                                }
                            }
                        } catch (Throwable ignored) {}
                        return result;
                    });
                    hooked = true;
                } catch (Throwable ignored) {}
            }
            log(4, TAG, "Hooked FullscreenGuideActivity: " + hooked);
        } catch (Throwable t) {
            log(6, TAG, "hookFullscreenGuide failed: " + t);
        }
    }

    /** 浮动广告气泡: 「去除内置广告」或「主页广告气泡」任一开启即屏蔽 */
    private boolean isAdBubbleBlocked() {
        return isFeatureEnabled("remove_ads", false) || isFeatureEnabled("block_coin_task_ball", false);
    }

    /** 视图层兜底: 隐藏 com.wifitutu.ui.bubble 下的浮层容器 (BubbleHostView / BubbleView / BubbleViewV2) */
    private void hideBubbleOverlay(android.view.ViewGroup root) {
        java.util.List<android.view.View> stack = new java.util.ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            android.view.View v = stack.remove(stack.size() - 1);
            String className = v.getClass().getName();
            if (className.startsWith("com.wifitutu.ui.bubble.")) {
                if (v.getVisibility() != android.view.View.GONE) {
                    v.setVisibility(android.view.View.GONE);
                    log(4, TAG, "Hid ad bubble: " + className);
                }
                continue;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    stack.add(g.getChildAt(i));
                }
            }
        }
    }



    private View getFieldSafe(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return (View) f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private Object findBindingField(Object obj) {
        Class<?> curr = obj.getClass();
        while (curr != null && !curr.getName().equals("java.lang.Object")) {
            try {
                Field f = curr.getDeclaredField("binding");
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception ignored) {}
            for (Field f : curr.getDeclaredFields()) {
                if (f.getType().getName().endsWith("Binding")) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val != null) return val;
                    } catch (Exception ignored) {}
                }
            }
            curr = curr.getSuperclass();
        }
        return null;
    }
}
