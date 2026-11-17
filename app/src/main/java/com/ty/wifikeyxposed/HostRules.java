package com.ty.wifikeyxposed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 规则不使用混淆短名；独立于 Android，便于在真实 APK 上复用同一套验证。 */
final class HostRules {
    static final int SCHEMA = 1;
    static final String DIALOG = "com.wifitutu.ui.dialog.ConnectDialog";
    static final String LAUNCHER = "com.wifitutu.ui.launcher.LauncherActivity";
    static final String HOME = "com.wifitutu.ui.home.HomeDialog";
    static final String STATE = "com.wifitutu.widget.core.ConnProcessState";
    static final String[] KEYS = {"update", "vip", "teen_running", "teen_limited", "teen_open", "teen_close",
            "qs_added", "overlay", "wifi_clear", "wifi_connect", "wifi_delete_mode", "wifi_cleanup",
            "connect_speed", "connect_launch", "connect_state", "connect_flag", "portal_flag",
            "connect_redirect", "banner_skin", "splash", "no_splash", "home_badge",
            "map_bind", "map_binding", "home_register", "bubble_module", "film_module", "cloud_config"};

    private final DexKitBridge mBridge;
    private final Map<String, MethodData> mMethods = new LinkedHashMap<>();

    HostRules(DexKitBridge bridge) {
        mBridge = bridge;
    }

    List<String> resolve(String key) {
        if (key.equals("cloud_config")) {
            List<MethodData> methods = concrete(signature(null, "org.json.JSONObject", "java.lang.String")
                    .name("getConfig").declaredClass(ClassMatcher.create()
                            .addInterface("com.link.ida.sdk.protocol.api.interfaces.IWfRemoteConfig")));
            if (methods.isEmpty()) throw new IllegalStateException("Remote config implementations: candidates=0");
            List<String> result = new ArrayList<>();
            for (MethodData method : methods) result.add(method.getDescriptor());
            return result;
        }
        if (key.equals("film_module")) return Arrays.asList(classWithStrings("HomeFilmBlock").getName());
        if (key.equals("vip")) {
            ClassData type = classWithStrings("null cannot be cast to non-null type com.wifitutu.link.foundation.core.IUserManager");
            List<MethodData> methods = concrete(MethodMatcher.create().declaredClass(type.getName())
                    .returnType("boolean").paramCount(1));
            if (methods.size() != 2 || methods.stream().anyMatch(m -> !Modifier.isStatic(m.getModifiers()))) {
                throw new IllegalStateException("VIP checks: expected two static checks, found " + methods.size());
            }
            List<String> result = new ArrayList<>();
            for (MethodData method : methods) result.add(method.getDescriptor());
            return result;
        }
        if (key.equals("wifi_delete_mode")) {
            MethodData connect = method("wifi_connect");
            return Arrays.asList(fieldUsedBy(connect, "boolean").getDescriptor());
        }
        if (key.equals("portal_flag")) {
            return Arrays.asList(fieldUsedBy(method("connect_speed"), "int").getDescriptor());
        }
        if (key.equals("no_splash")) {
            return Arrays.asList(fieldUsedBy(method("splash"), "boolean").getDescriptor());
        }
        if (key.equals("bubble_module")) {
            ClassData type = unique(mBridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                    .addFieldForType("com.wifitutu.ui.bubble.BubbleHostView")
                    .addFieldForType("java.util.Set"))), "bubble lifecycle");
            return Arrays.asList(type.getName());
        }
        return Arrays.asList(method(key).getDescriptor());
    }

    private MethodData method(String key) {
        if (mMethods.containsKey(key)) return mMethods.get(key);
        MethodMatcher matcher;
        switch (key) {
            case "update":
                matcher = signature(classWithStrings("app_update").getName(), "void", "android.content.Context", "boolean");
                break;
            case "teen_running":
            case "teen_limited":
            case "teen_open":
            case "teen_close":
                String type = unique(concrete(MethodMatcher.create().name("open").returnType("boolean")
                        .paramTypes("java.lang.String").declaredClass(ClassMatcher.create()
                                .addMethod(signature(null, "boolean").name("isRunning"))
                                .addMethod(signature(null, "boolean").name("isLimited")))), "teenager manager").getClassName();
                String name = key.equals("teen_running") ? "isRunning" : key.equals("teen_limited") ? "isLimited" : "open";
                if (key.equals("teen_close")) {
                    MethodData close = unique(filter(concrete(signature(type, "boolean", "java.lang.String")),
                            m -> !m.getName().equals("open")), "teenager close");
                    mMethods.put(key, close);
                    return close;
                }
                matcher = signature(type, "boolean", key.equals("teen_open") ? new String[]{"java.lang.String"} : new String[0]).name(name);
                break;
            case "qs_added":
                ClassData quickSettings = classWithStrings("tt_qsettings");
                String preferencesOwner = quickSettings.getName();
                int nested = preferencesOwner.indexOf('$');
                if (nested >= 0) quickSettings = mBridge.getClassData(preferencesOwner.substring(0, nested));
                // 区分“至少一个已添加”和“全部已添加”，后者会统计数量并比较 List.size。
                MethodData added = unique(filter(quickSettings.getMethods(), m -> m.getParamCount() == 0
                        && m.getReturnTypeName().equals("boolean")
                        && m.getInvokes().stream().anyMatch(call -> call.getName().equals("getComponentName")
                                && call.getReturnTypeName().equals("java.util.List"))
                        && m.getInvokes().stream().noneMatch(call -> call.getClassName().equals("java.util.List")
                                && call.getName().equals("size"))), "QS any-added check");
                mMethods.put(key, added);
                return added;
            case "overlay":
                matcher = signature(null, "boolean", "java.lang.String", "android.content.Context")
                        .addInvoke(MethodMatcher.create().declaredClass("android.provider.Settings")
                                .name("canDrawOverlays").paramTypes("android.content.Context"));
                break;
            case "wifi_clear":
                matcher = signature(null, "void", "boolean", null)
                        .usingEqStrings("wifi|connect");
                break;
            case "wifi_connect":
                matcher = signature(null, "void", null, "com.wifitutu.link.foundation.kernel.WIFI_KEY_MODE")
                        .usingEqStrings("_config", "wifi|connect");
                break;
            case "wifi_cleanup":
                matcher = signature(method("wifi_connect").getClassName(), "void")
                        .addUsingField(logSingleton("删除连接失败的网络配置").getDescriptor());
                break;
            case "connect_speed":
                matcher = signature(DIALOG, "void", "java.lang.Long").usingEqStrings("showSpeedLayout: ", "showSpeedLayout: wifi 异常");
                break;
            case "connect_launch":
                matcher = signature(DIALOG, "void").addUsingField(logSingleton("Go to Speed_Up Page - 1!").getDescriptor());
                break;
            case "connect_state":
                matcher = signature(DIALOG, "void", STATE);
                break;
            case "connect_flag":
                matcher = signature(DIALOG, "boolean", "int");
                break;
            case "connect_redirect":
                matcher = signature(null, "boolean", "java.lang.String")
                        .addInvoke(MethodMatcher.create().name("getConnSuccDirct").paramTypes().returnType("java.lang.String"));
                break;
            case "banner_skin":
                matcher = signature(DIALOG, "void").usingEqStrings("askBleSwitch")
                        .addInvoke(MethodMatcher.create().declaredClass("android.view.View").name("setBackgroundResource"));
                break;
            case "splash":
                matcher = signature(LAUNCHER, "void", "int", "int", "boolean");
                break;
            case "home_badge":
                matcher = signature(HOME, "void").usingEqStrings("该WiFi可加速");
                break;
            case "map_bind":
                matcher = signature("com.wifitutu_common.ui.WifiList$WifiAdapter", "void",
                        "com.wifitutu.tutu_monitor.monitor.BindingViewHolder", "int");
                break;
            case "map_binding":
                matcher = signature("com.wifitutu.tutu_monitor.monitor.BindingViewHolder", "androidx.databinding.ViewDataBinding");
                break;
            case "home_register":
                type = classWithStrings("#release() 函数内必须显式调用 super.release()").getName();
                matcher = signature(type, "boolean", "java.lang.Class");
                break;
            default:
                throw new IllegalArgumentException("Unknown capability: " + key);
        }
        MethodData result = unique(concrete(matcher), key);
        mMethods.put(key, result);
        return result;
    }

    private ClassData classWithStrings(String... strings) {
        return unique(mBridge.findClass(FindClass.create().matcher(ClassMatcher.create().usingEqStrings(strings))),
                "class anchor " + Arrays.toString(strings));
    }

    private FieldData logSingleton(String text) {
        ClassData message = unique(mBridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(text))), "log anchor").getDeclaredClass();
        return unique(filter(message.getFields(), f -> Modifier.isStatic(f.getModifiers())
                && f.getTypeName().equals(message.getName())), "log singleton");
    }

    private List<MethodData> concrete(MethodMatcher matcher) {
        return filter(mBridge.findMethod(FindMethod.create().matcher(matcher)),
                m -> m.isMethod() && !Modifier.isAbstract(m.getModifiers()) && !m.getName().startsWith("access$"));
    }

    private static MethodMatcher signature(String owner, String result, String... params) {
        MethodMatcher matcher = MethodMatcher.create().returnType(result).paramTypes(params);
        return owner == null ? matcher : matcher.declaredClass(owner);
    }

    private static FieldData fieldUsedBy(MethodData method, String type) {
        Map<String, FieldData> fields = new LinkedHashMap<>();
        for (UsingFieldData use : method.getUsingFields()) {
            FieldData field = use.getField();
            if (field.getClassName().equals(method.getClassName()) && field.getTypeName().equals(type)
                    && !Modifier.isStatic(field.getModifiers())) fields.put(field.getDescriptor(), field);
        }
        return unique(new ArrayList<>(fields.values()), method.getName() + " " + type + " field");
    }

    private static <T> List<T> filter(List<T> source, Predicate<T> predicate) {
        List<T> result = new ArrayList<>();
        for (T value : source) if (predicate.test(value)) result.add(value);
        return result;
    }

    static <T> T unique(List<T> candidates, String capability) {
        if (candidates.size() != 1) throw new IllegalStateException(capability + ": candidates=" + candidates.size());
        return candidates.get(0);
    }
}
