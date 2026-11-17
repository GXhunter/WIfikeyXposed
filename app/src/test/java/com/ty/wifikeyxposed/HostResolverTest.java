package com.ty.wifikeyxposed;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import static org.junit.Assert.*;

public class HostResolverTest {
    @Test public void cacheRejectsChangedHostRulesAndDamagedMembers() {
        Properties cache = new Properties();
        cache.setProperty("identity", "1:host-a");
        cache.setProperty("vip", "method-one\nmethod-two");
        cache.setProperty("checksum", HostResolver.checksum(cache));
        assertTrue(HostResolver.validCache(cache, "1:host-a"));
        assertFalse(HostResolver.validCache(cache, "2:host-a"));
        assertFalse(HostResolver.validCache(cache, "1:host-b"));
        cache.setProperty("vip", "method-one");
        assertFalse(HostResolver.validCache(cache, "1:host-a"));
    }

    @Test public void fingerprintUsesContentEvenWhenFileSizeDoesNotChange() throws Exception {
        Path file = Files.createTempFile("wifikey-host", ".apk");
        try {
            Files.write(file, new byte[]{1, 2, 3});
            String old = HostResolver.fingerprint(file.toFile());
            Files.write(file, new byte[]{1, 2, 4});
            assertNotEquals(old, HostResolver.fingerprint(file.toFile()));
        } finally {
            Files.delete(file);
        }
    }
}
