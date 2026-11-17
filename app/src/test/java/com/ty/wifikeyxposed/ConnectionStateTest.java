package com.ty.wifikeyxposed;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionStateTest {
    @Test public void failedOrNewAttemptCannotReuseSuccess() {
        for (String state : new String[]{"CON_START", "CON_RETRY", "CON_ERROR", "CON_NET_UNAVAILABLE", "NEW_STATE"}) {
            assertNull(ConnectionState.update(true, state));
        }
    }

    @Test public void presentationCannotInventSuccess() {
        assertNull(ConnectionState.update(null, "CON_SHOW_SUCCESS"));
        assertNull(ConnectionState.update(null, "CON_NET_AVAILABLE"));
        assertNull(ConnectionState.update(null, "CON_FINISH"));
        assertEquals(Boolean.TRUE, ConnectionState.update(null, "CON_SUCCESS"));
        assertEquals(Boolean.TRUE, ConnectionState.update(true, "CON_FINISH"));
    }
}
