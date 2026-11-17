package com.ty.wifikeyxposed;

final class ConnectionState {
    private ConnectionState() {}

    static Boolean update(Boolean previous, String state) {
        switch (state) {
            case "CON_SUCCESS":
                return true;
            case "CON_SHOW_SUCCESS":
            case "CON_NET_AVAILABLE":
            case "CON_FINISH":
                return previous;
            default:
                // 重试、认证失败和未来新增状态均不允许沿用上次成功状态。
                return null;
        }
    }
}
