package com.ty.wifikeyxposed;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class HostRulesTest {
    @Test public void missingAndAmbiguousTargetsAreRejected() {
        assertThrows(IllegalStateException.class, () -> HostRules.unique(Collections.emptyList(), "missing"));
        assertThrows(IllegalStateException.class, () -> HostRules.unique(Arrays.asList("old", "new"), "ambiguous"));
        assertEquals("only", HostRules.unique(Collections.singletonList("only"), "unique"));
    }
}
