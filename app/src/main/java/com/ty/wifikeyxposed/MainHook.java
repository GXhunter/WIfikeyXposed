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
    private static final String AD_STRATEGY_CLASS = "com.wifi.business.potocol.sdk.base.strategy.AdStrategy";
    private static final String REMOTE_CONFIG_INTERFACE = "com.link.ida.sdk.protocol.api.interfaces.IWfRemoteConfig";
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
    private android.content.pm.ApplicationInfo mHostInfo;
    private HostResolver mResolver;
    private SharedPreferences mPreferences;
    private volatile java.util.Map<String, ?> mSettings = java.util.Collections.emptyMap();
    private final SharedPreferences.OnSharedPreferenceChangeListener mSettingsListener = (preferences, key) -> {
        mSettings = new java.util.HashMap<>(preferences.getAll());
        if (mainHandler != null && TARGET_PACKAGE.equals(android.app.Application.getProcessName())) {
            mainHandler.post(this::scheduleStartupHomeHides);
        }
    };
    private Field mHomeSizeCallback;
    private Method mHomeSizeInvoke;
    private final java.util.Map<Object, Boolean> mConnectedDialogs =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public MainHook() {
        super();
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field result = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            if (result != null) throw new NoSuchFieldException("Ambiguous " + type.getSimpleName());
            result = field;
        }
        if (result == null) throw new NoSuchFieldException(type.getSimpleName());
        result.setAccessible(true);
        return result;
    }

    private boolean isFeatureEnabled(String key, boolean def) {
        Object value = mSettings.get(key);
        return value instanceof Boolean ? (Boolean) value : def;
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
        mHostInfo = info;
        hostNativeLibDir = (info != null) ? info.nativeLibraryDir : null;
        installAllHooks(hostClassLoader, hostNativeLibDir);
    }

    /** 安装全部 hook — 首次加载与热重载后共用 */
    private void installAllHooks(ClassLoader classLoader, String nativeLibDir) {
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            mPreferences = getRemotePreferences("settings");
            mPreferences.registerOnSharedPreferenceChangeListener(mSettingsListener);
            mSettings = new java.util.HashMap<>(mPreferences.getAll());
        } catch (Exception e) {
            log(5, TAG, "Settings unavailable, using defaults: " + e.getMessage());
        }
        hookNativeBridgeCompat(classLoader, nativeLibDir);
        hookAntiTamper(classLoader);
        // UI 和连接业务入口只在主进程使用，辅助进程不扫描 DEX。
        if (!TARGET_PACKAGE.equals(android.app.Application.getProcessName())) return;
        try {
            mResolver = new HostResolver(mHostInfo, classLoader, message -> log(4, TAG, message));
        } catch (Exception e) {
            log(5, TAG, "Adaptive resolver unavailable: " + e.getMessage());
        }
        try {
            hookVipStatus();
            hookBottomNavigation(classLoader);
            hookOptionalHomeModules();
            hookOptionalHomeTabs(classLoader);
            hookFloatingBall(classLoader);
            hookFullscreenGuide(classLoader);
            hookAppUpdate();
            hookWifiMapRow(classLoader);
            hookHomeRootWatcher(classLoader);
            hookRnHomeView(classLoader);
            hookPostConnectPromotions();
            hookAds(classLoader);
            hookCloudControl(classLoader);
            hookTeenagerMode();
            hookWifiSilentDelete();
            hookQuickSettingsBypass();
            hookHomeSpeedUpBadge(classLoader);
            hookMineRecentApps(classLoader);
            hookMeFragment(classLoader);
            scheduleStartupHomeHides();
        } finally {
            if (mResolver != null) mResolver.close();
            mResolver = null;
            log(4, TAG, "Hook installation attempts finished in "
                    + (android.os.SystemClock.elapsedRealtime() - started)
                    + "ms; check each capability's installed/unavailable result");
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
            if (mPreferences != null) mPreferences.unregisterOnSharedPreferenceChangeListener(mSettingsListener);
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
            state.add(mHostInfo);
            mConnectedDialogs.clear();
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
                if (state.size() > 4) mHostInfo = (android.content.pm.ApplicationInfo) state.get(4);
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
            Method tabFilter = findTabFilter(classLoader);
            Method getId = tabFilter.getParameterTypes()[0].getMethod("getId");
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
            log(5, TAG, "Bottom-tab filter unavailable: " + e.getMessage());
        }

        // 旧式底栏使用 navigation_* 资源 ID。
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

                if (requestedVisibility == View.VISIBLE && isFeatureEnabled(prefKey, false)) {
                    log(4, TAG, "Blocking Visibility on tab: " + resName);
                    return chain.proceed(new Object[]{View.GONE});
                }

                return chain.proceed();
            });

            // 生命周期刷新 (二次压制)
            Class<?> mainActivityCls = classLoader.loadClass("com.wifitutu.ui.main.MainActivity");
            Method onResumeMethod = mainActivityCls.getDeclaredMethod("onResume");
            hook(onResumeMethod).intercept(chain -> {
                Object result = chain.proceed();
                Object activity = chain.getThisObject();
                mainHandler.postDelayed(() -> refreshBottomTabs(activity), 1500);
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
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            isPerformingForcedHide = false;
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

    private boolean supportsHomeLayout() {
        if (mSupportsHomeToolsResize == null) {
            try {
                Class<?> tools = hostClassLoader.loadClass("com.wifitutu.ui.view.RnHomesView");
                mHomeSizeCallback = tools.getDeclaredField("onSizeChanged");
                mHomeSizeCallback.setAccessible(true);
                mHomeSizeInvoke = mHomeSizeCallback.getType().getMethod("invoke", Object.class, Object.class, Object.class, Object.class);
                mSupportsHomeToolsResize = ViewGroup.class.isAssignableFrom(tools);
            } catch (ReflectiveOperationException e) {
                mSupportsHomeToolsResize = false;
                log(5, TAG, "Home size callback unavailable: " + e.getMessage());
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
            Method bind = mResolver.method("map_bind");
            Method getBinding = mResolver.method("map_binding");
            Class<?> mapBinding = classLoader.loadClass("com.wifitutu_common.databinding.ItemWifiMapBinding");
            Field itemView = holder.getField("itemView");
            if (bind.getReturnType() != void.class || itemView.getType() != View.class
                    || !getBinding.getReturnType().isAssignableFrom(mapBinding)) {
                throw new NoSuchMethodException("Unexpected WiFi row binding signature");
            }
            hook(bind).intercept(chain -> {
                View row = (View) itemView.get(chain.getArgs().get(0));
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
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Failed to hook WiFi map row: " + e.getMessage());
        }
    }

    private void collapseEmptyHomeTools(ViewGroup root) {
        View tools = findViewByClassName(root, "com.wifitutu.ui.view.RnHomesView");
        if (!(tools instanceof ViewGroup)) return;
        if (!supportsHomeLayout()) return;
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
                if (!supportsHomeLayout()) return;
                Object callback = mHomeSizeCallback.get(tools);
                if (callback == null) return;
                mHomeSizeInvoke.invoke(callback, tools.getWidth(), height, tools.getWidth(), tools.getHeight());
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

    private void hookCloudControl(ClassLoader classLoader) {
        try {
            List<Method> methods = mResolver.methods("cloud_config");
            Class<?> contract = classLoader.loadClass(REMOTE_CONFIG_INTERFACE);
            for (Method method : methods) {
                if (!contract.isAssignableFrom(method.getDeclaringClass()) || Modifier.isAbstract(method.getModifiers())) {
                    throw new NoSuchMethodException("Remote config implementation");
                }
            }
            for (Method method : methods) hook(method).intercept(chain ->
                    isFeatureEnabled("remove_cloud_control", false) ? null : chain.proceed());
            log(4, TAG, "Installed remote config implementations: " + methods.size());
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Remote config unavailable: " + e.getMessage());
        }
        try {
            Method method = classLoader.loadClass(AD_STRATEGY_CLASS).getDeclaredMethod("getTaiChiValue");
            if (method.getReturnType() != String.class) throw new NoSuchMethodException("Ad strategy");
            hook(method).intercept(chain -> isFeatureEnabled("remove_cloud_control", false) ? "" : chain.proceed());
            log(4, TAG, "Installed ad strategy cloud control");
        } catch (Exception e) {
            log(5, TAG, "Ad strategy cloud control unavailable: " + e.getMessage());
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

    private void hookTeenagerMode() {
        for (String key : new String[]{"teen_running", "teen_limited", "teen_open", "teen_close"}) {
            try {
                Method method = mResolver.method(key);
                if (method.getReturnType() != boolean.class) throw new NoSuchMethodException(key);
                hook(method).intercept(chain -> isFeatureEnabled("lite_teenager", false)
                        ? !key.equals("teen_limited") : chain.proceed());
                log(4, TAG, "Installed " + key);
            } catch (Exception | LinkageError e) {
                log(5, TAG, key + " unavailable: " + e.getMessage());
            }
        }
    }

    /** 宿主保留的 native 声明是已验证的入口。 */
    private void hookAntiTamper(ClassLoader classLoader) {
        try {
            Class<?> manager = classLoader.loadClass("com.wifitutu.link.foundation.native_.AppManager");
            Method check = manager.getDeclaredMethod("is_cheat_");
            if (check.getReturnType() != boolean.class || !Modifier.isNative(check.getModifiers())) {
                throw new NoSuchMethodException("Native integrity check signature");
            }
            hook(check).intercept(chain -> false);
            log(4, TAG, "Installed native integrity check");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Native integrity check unavailable: " + e.getMessage());
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
     * 正常联网后不进入自动加速或推广跳转。
     * 保留连接结果、认证门户和用户主动打开的加速工具。
     */
    private void hookPostConnectPromotions() {
        try {
            Method update = mResolver.method("connect_state");
            Method speed = mResolver.method("connect_speed");
            Method launch = mResolver.method("connect_launch");
            Method hasFlag = mResolver.method("connect_flag");
            Field portal = mResolver.field("portal_flag");
            Class<?> dialogType = speed.getDeclaringClass();
            Class<?> stateType = update.getParameterTypes()[0];
            if (!android.app.Dialog.class.isAssignableFrom(dialogType) || !stateType.isEnum()
                    || portal.getType() != int.class || portal.getDeclaringClass() != dialogType
                    || update.getDeclaringClass() != dialogType || hasFlag.getDeclaringClass() != dialogType
                    || launch.getDeclaringClass() != dialogType) throw new NoSuchMethodException("Connection state structure");
            Object available = Enum.valueOf(stateType.asSubclass(Enum.class), "CON_NET_AVAILABLE");
            hook(update).intercept(chain -> {
                Object result = chain.proceed();
                String state = ((Enum<?>) chain.getArgs().get(0)).name();
                Boolean connected = ConnectionState.update(mConnectedDialogs.get(chain.getThisObject()), state);
                if (connected == null) mConnectedDialogs.remove(chain.getThisObject());
                else mConnectedDialogs.put(chain.getThisObject(), connected);
                return result;
            });
            hook(speed).intercept(chain -> {
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                if (!isFeatureEnabled("remove_ads", false)
                        || !Boolean.TRUE.equals(mConnectedDialogs.get(dialog))
                        || portal.getInt(dialog) != 4 || Boolean.TRUE.equals(hasFlag.invoke(dialog, portal.getInt(dialog)))) {
                    return chain.proceed();
                }
                if (dialog.isShowing()) {
                    update.invoke(dialog, available);
                    dialog.dismiss();
                }
                log(4, TAG, "Skipped automatic WiFi speed-up after confirmed connection");
                return null;
            });
            hook(launch).intercept(chain -> {
                Object dialog = chain.getThisObject();
                if (isFeatureEnabled("remove_ads", false) && Boolean.TRUE.equals(mConnectedDialogs.get(dialog))
                        && portal.getInt(dialog) == 4 && !Boolean.TRUE.equals(hasFlag.invoke(dialog, portal.getInt(dialog)))) return null;
                return chain.proceed();
            });
            log(4, TAG, "Installed WiFi post-connect speed-up control");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Post-connect speed-up unavailable: " + e.getMessage());
        }
        try {
            Method redirect = mResolver.method("connect_redirect");
            hook(redirect).intercept(chain -> isFeatureEnabled("remove_ads", false) ? false : chain.proceed());
            log(4, TAG, "Installed WiFi post-connect promotion control");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Post-connect promotion unavailable: " + e.getMessage());
        }
    }

    /** 仅收起顶部推广，保留共用容器和蓝牙提示。 */
    private void hookConnectDialogBanner() {
        try {
            Method skin = mResolver.method("banner_skin");
            Class<?> type = skin.getDeclaringClass();
            if (!android.app.Dialog.class.isAssignableFrom(type)) throw new NoSuchMethodException("Dialog type");
            Method create = type.getDeclaredMethod("onCreate", android.os.Bundle.class);
            hook(skin).intercept(chain -> isFeatureEnabled("remove_ads", false) ? null : chain.proceed());
            hook(create).intercept(chain -> {
                Object result = chain.proceed();
                android.app.Dialog dialog = (android.app.Dialog) chain.getThisObject();
                android.content.res.Resources res = dialog.getContext().getResources();
                int bannerId = res.getIdentifier("connect_bg", "id", TARGET_PACKAGE);
                int containerId = res.getIdentifier("ad_layout", "id", TARGET_PACKAGE);
                if (bannerId == 0 || containerId == 0) return result;
                View banner = dialog.findViewById(bannerId);
                View container = dialog.findViewById(containerId);
                // 同一容器也承载蓝牙切网提示，只处理已验证的推广图片。
                if (banner instanceof android.widget.ImageView && container != null
                        && banner.getParent() == container && !"askBleSwitch".equals(banner.getTag())) {
                    setHomePromotionHidden(banner, isFeatureEnabled("remove_ads", false), false);
                }
                return result;
            });
            log(4, TAG, "Installed WiFi connect banner control");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Connect banner unavailable: " + e.getMessage());
        }
    }

    private void hookAds(ClassLoader classLoader) {
        hookConnectDialogBanner();
        try {
            Class<?> launcherClass = classLoader.loadClass("com.wifitutu.ui.launcher.LauncherActivity");
            Method loadSplashAd = mResolver.method("splash");
            Field noSplash = mResolver.field("no_splash");
            if (loadSplashAd.getDeclaringClass() != launcherClass || noSplash.getDeclaringClass() != launcherClass
                    || loadSplashAd.getReturnType() != void.class || noSplash.getType() != boolean.class) {
                throw new IllegalStateException("Unexpected splash ad signature");
            }
            noSplash.setAccessible(true);
            hook(loadSplashAd).intercept(chain -> {
                if (!isFeatureEnabled("remove_ads", false)) return chain.proceed();
                android.app.Activity launcher = (android.app.Activity) chain.getThisObject();
                boolean original = noSplash.getBoolean(launcher);
                noSplash.setBoolean(launcher, true);
                try {
                    log(4, TAG, "Skipping splash ad load");
                    return chain.proceed();
                } finally {
                    noSplash.setBoolean(launcher, original);
                }
            });
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Splash control unavailable: " + e.getMessage());
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
            Method render = mResolver.method("home_badge");
            Field binding = uniqueField(home, bindingType);
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
                        && isFeatureEnabled("remove_ads", false)
                        && action.getVisibility() != View.GONE) {
                    action.setVisibility(View.GONE);
                    log(4, TAG, "Hidden WiFi acceleration badge");
                }
                return result;
            });
            log(4, TAG, "Hooked WiFi acceleration badge");
        } catch (Exception | LinkageError e) {
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
                if (mineId == 0 || view.getId() != mineId) {
                    return chain.proceed();
                }
                boolean hidden = isFeatureEnabled("remove_ads", false);
                setHomePromotionHidden(view, hidden, false);
                return hidden ? null : chain.proceed();
            });
            log(4, TAG, "Hooked Mine recent apps refresh");
        } catch (Exception | LinkageError e) {
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

    /** 自动与手动检查更新最终进入同一个入口。 */
    private void hookAppUpdate() {
        try {
            Method method = mResolver.method("update");
            if (method.getReturnType() != void.class || !java.util.Arrays.equals(method.getParameterTypes(),
                    new Class<?>[]{Context.class, boolean.class})) throw new NoSuchMethodException("Update signature");
            hook(method).intercept(chain -> {
                if (isFeatureEnabled("block_update", true)) return null;
                return chain.proceed();
            });
            log(4, TAG, "Installed automatic/manual update check");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Update check unavailable: " + e.getMessage());
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

    private void hookVipStatus() {
        try {
            List<Method> checks = mResolver.methods("vip");
            for (Method check : checks) {
                if (check.getReturnType() != boolean.class || !Modifier.isStatic(check.getModifiers())
                        || check.getParameterCount() != 1) throw new NoSuchMethodException("VIP signature");
            }
            for (Method check : checks) hook(check).intercept(chain ->
                    isFeatureEnabled("unlock_vip", false) ? true : chain.proceed());
            log(4, TAG, "Installed VIP checks: " + checks.size());
        } catch (Exception | LinkageError e) {
            log(5, TAG, "VIP checks unavailable: " + e.getMessage());
        }
    }

    /** 分别定位连接前清理、先删后加和失败后清理。 */
    private void hookWifiSilentDelete() {
        try {
            Method clear = mResolver.method("wifi_clear");
            hook(clear).intercept(chain -> {
                if (isFeatureEnabled("block_wifi_clear_config", false)) {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = false;
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            log(4, TAG, "Installed WiFi pre-connect cleanup protection");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "WiFi pre-connect cleanup unavailable: " + e.getMessage());
        }
        try {
            Method connect = mResolver.method("wifi_connect");
            Field deleteMode = mResolver.field("wifi_delete_mode");
            if (deleteMode.getType() != boolean.class || deleteMode.getDeclaringClass() != connect.getDeclaringClass()) {
                throw new NoSuchFieldException("Delete mode signature");
            }
            hook(connect).intercept(chain -> {
                if (!isFeatureEnabled("block_wifi_delete_model", false)) return chain.proceed();
                Object self = chain.getThisObject();
                boolean original = deleteMode.getBoolean(self);
                deleteMode.setBoolean(self, false);
                try {
                    return chain.proceed();
                } finally {
                    deleteMode.setBoolean(self, original);
                }
            });
            log(4, TAG, "Installed WiFi delete/add protection");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "WiFi delete/add protection unavailable: " + e.getMessage());
        }
        try {
            Method cleanup = mResolver.method("wifi_cleanup");
            hook(cleanup).intercept(chain -> isFeatureEnabled("block_wifi_post_clean", false) ? null : chain.proceed());
            log(4, TAG, "Installed WiFi failed-connect cleanup protection");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "WiFi failed-connect cleanup unavailable: " + e.getMessage());
        }
    }

    /** 仅处理引导入口，系统权限状态由 Android 管理。 */
    private void hookQuickSettingsBypass() {
        try {
            Method added = mResolver.method("qs_added");
            hook(added).intercept(chain -> isFeatureEnabled("bypass_qs_guide", false) ? true : chain.proceed());
            log(4, TAG, "Installed QS guide check");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "QS guide check unavailable: " + e.getMessage());
        }
        try {
            Method overlay = mResolver.method("overlay");
            hook(overlay).intercept(chain -> {
                if (isFeatureEnabled("bypass_overlay_guide", false)
                        && "android:system_alert_window".equals(chain.getArgs().get(0))) return true;
                return chain.proceed();
            });
            log(4, TAG, "Installed overlay guide check");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Overlay guide check unavailable: " + e.getMessage());
        }
    }

    /** 减少广告气泡与电影页签的注册；具体显示仍有视图层兜底。 */
    private void hookOptionalHomeModules() {
        try {
            Method register = mResolver.method("home_register");
            Class<?> bubble = mResolver.type("bubble_module");
            Class<?> film = mResolver.type("film_module");
            hook(register).intercept(chain -> {
                Object component = chain.getArgs().get(0);
                if (component == bubble && isAdBubbleBlocked()
                        || component == film && isOptionalHomeTabBlocked("film")) return false;
                return chain.proceed();
            });
            log(4, TAG, "Installed optional home module registration");
        } catch (Exception | LinkageError e) {
            log(5, TAG, "Optional home modules unavailable: " + e.getMessage());
        }
    }

    /** 在底栏入口过滤可选页签，保留连接、认证和设置入口。 */
    private void hookOptionalHomeTabs(ClassLoader classLoader) {
        try {
            Class<?> tabLayout = classLoader.loadClass("com.wifitutu.widget.maintab.HomeTabLayout");
            Class<?> tabItem = findTabFilter(classLoader).getParameterTypes()[0];
            Method getId = tabItem.getMethod("getId");
            Method setTabs = tabLayout.getDeclaredMethod("setTabs", List.class, android.os.Bundle.class);
            if (setTabs.getReturnType() != void.class || Modifier.isStatic(setTabs.getModifiers())) throw new NoSuchMethodException("Tab setter");
            // 在保留名称的列表入口过滤，避免依赖混淆后的配置注册类。
            hook(setTabs).intercept(chain -> {
                Object arg = chain.getArgs().get(0);
                if (!(arg instanceof List<?>)) return chain.proceed();
                List<?> original = (List<?>) arg;
                List<?> filtered = filterOptionalHomeTabs(original, tabItem, getId);
                if (filtered == original) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = filtered;
                return chain.proceed(args);
            });
            log(4, TAG, "Installed optional home tab filter");
        } catch (Exception e) {
            log(5, TAG, "Optional home tab filter unavailable: " + e.getMessage());
        }
    }

    private static Method findTabFilter(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> extensions = classLoader.loadClass("com.wifitutu.widget.maintab.ExtentionsKt");
        List<Method> candidates = new ArrayList<>();
        for (Method method : extensions.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                    || !method.getReturnType().isEnum()) continue;
            Class<?> tab = method.getParameterTypes()[0];
            if (!tab.isInterface()) continue;
            try {
                if (tab.getMethod("getId").getReturnType() != String.class) continue;
                Enum.valueOf(method.getReturnType().asSubclass(Enum.class), "TEENAGER");
                candidates.add(method);
            } catch (NoSuchMethodException | IllegalArgumentException ignored) {
                // 排除同签名但并非底栏过滤器的方法。
            }
        }
        return HostRules.unique(candidates, "tab filter");
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
