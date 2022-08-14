/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *  
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
 
package com.xilinx.rapidwright.util;

import java.security.Permission;

/**
 * A custom SecurityManager to enable catching System.exit() calls and throwing an exception instead.
 * Since the SecurityManager is being deprecated in Java 17, it will not be used by default but can 
 * be invoked by 'PythonSecurityManager.blockSystemExitCalls()'.    
 * @see <a href="https://openjdk.java.net/jeps/411">JEPS 411</a>
 * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8199704">JDK-8199704</a>
 */
@SuppressWarnings("removal")
public class BlockExitSecurityManager extends SecurityManager {
    
    private static BlockExitSecurityManager singleton = null; 
    
    @Override 
    public void checkExit(int status) {
        throw new SecurityException("JVM attempted to exit with status: " + status);
    }

    @Override 
    public void checkPermission(Permission p) {
    }

    /**
     * Blocks System.exit() calls from exiting the JVM and instead throws a 
     * {@link SecurityException} in its place.  This is useful when running a JVM through an 
     * interactive interpreter such as Python so that individual commands to not cause the REPL
     * to exit. This will generate a WARNING message in Java 17, and must be explicitly allowed in 
     * future JDK releases.
     * 
     * @see <a href="https://openjdk.java.net/jeps/411">JEPS 411</a>
     * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8199704">JDK-8199704</a>
     */
    public static void blockSystemExitCalls() {
        if(singleton == null) {
            singleton = new BlockExitSecurityManager();
            System.setSecurityManager(singleton);            
        }
    }
}
