package org.unlaxer.dsl;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.Assert;
import org.junit.Assume;

/** Compiler boundary for generated consumers whose contract uses Java 21. */
public final class Java21TestSupport {
    private Java21TestSupport() {}

    public static JavaCompiler compiler() {
        String reason = "Java21 baseline: generated consumer requires JDK 21 or newer";
        boolean supported = Runtime.version().feature() >= 21;
        if (!supported) System.out.println("[assumption] " + reason);
        Assume.assumeTrue(reason, supported);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assert.assertNotNull("Java21 generated consumer requires a JDK compiler", compiler);
        return compiler;
    }
}
