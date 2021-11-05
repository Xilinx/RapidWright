package com.xilinx.rapidwright.util;

import java.security.Permission;

/**
 * A custom SecurityManager to enable catching System.exit() calls and throwing an exception instead.
 * Since the SecurityManager is being deprecated in Java 17, it will not be used by default but can 
 * be invoked by 'PythonSecurityManager.blockSystemExitCalls()'.    
 * @see <a href="JEPS 411">https://openjdk.java.net/jeps/411</a>
 * @see <a href="JDK-8199704">https://bugs.openjdk.java.net/browse/JDK-8199704</a>
 */
@SuppressWarnings("removal")
public class BlockExitSecurityManager extends SecurityManager {
    
    private static BlockExitSecurityManager singleton = null; 
    
    @Override 
    public void checkExit(int status) {
        throw new SecurityException("Process Exited with Return Value: " + status);
    }

    @Override 
    public void checkPermission(Permission p) {
    }

    /**
     * Blocks {@link System.exit()} calls from exiting the JVM and instead throws a 
     * {@link SecurityException} in its place.  This is useful when running a JVM through an 
     * interactive interpreter such as Python so that individual commands to not cause the REPL
     * to exit. This will generate a WARNING message in Java 17, and must be explicitly allowed in 
     * future JDK releases.
     * 
     * @see <a href="JEPS 411">https://openjdk.java.net/jeps/411</a>
     * @see <a href="JDK-8199704">https://bugs.openjdk.java.net/browse/JDK-8199704</a>
     */
    public static void blockSystemExitCalls() {
        if(singleton == null) {
            singleton = new BlockExitSecurityManager();
            System.setSecurityManager(singleton);            
        }
    }
}
