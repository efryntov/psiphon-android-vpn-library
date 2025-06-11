# Preserve native methods in Tun2SocksJniLoader
-keep class ca.psiphon.Tun2SocksJniLoader {
    native <methods>;
}

# Keep the logTun2Socks method in PsiphonVpnManager, as it is called from native code
-keep class ca.psiphon.library.PsiphonVpnManager {
    public static void logTun2Socks(java.lang.String, java.lang.String, java.lang.String);
}

# Keep AIDL interfaces
-keep interface ca.psiphon.library.IPsiphon* { *; }
