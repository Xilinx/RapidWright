package com.xilinx.rapidwright.checker;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Check that a testcase does leak open files
 *
 * Only works on Linux. On other OSes it cannot detect errors and will fail silently.
 */
public class CheckOpenFilesExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private String getOwnPid() {
        final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        String name = runtimeBean.getName();
        int atPosition = name.indexOf("@");
        if (atPosition<=1) {
            return name;
        }
        return name.substring(0,atPosition);
    }
    private List<String> getOpenFiles() {
        final Path fdList = Paths.get("/proc/" + getOwnPid() + "/fd");
        if (!Files.exists(fdList)) {
            //We are probably not on Linux, fail silently
            return Collections.emptyList();
        }
        try (final Stream<Path> list = Files.list(fdList)) {
            return list.map(p -> {
                try {
                    final Path linkTarget = Files.readSymbolicLink(p);
                    return linkTarget.toString();
                } catch (IOException e) {
                    return p.toString();
                }
            })
                    .filter(this::checkIgnore)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkIgnore(String path) {
        //Ignore Random Device
        if (path.equals("/dev/random") || path.equals("/dev/urandom")) {
            return false;
        }
        //Socket for debugging should be ignored
        if (path.startsWith("socket:")) {
            return false;
        }
        //Ignore JDK internals (may need to load new code during execution)
        if (path.startsWith(System.getProperty("java.home"))) {
            return false;
        }
        return true;
    }

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create("com", "xilinx", "rapidwright", "checker");
    private static final String OPEN_FILES = "openFiles";

    @Override
    public void afterTestExecution(ExtensionContext extensionContext) {
        final List<String> afterList = getOpenFiles();
        @SuppressWarnings("unchecked")
        List<String> beforeList = extensionContext.getStore(NAMESPACE).get(OPEN_FILES, List.class);


        if (!beforeList.equals(afterList)) {
            final Stream<String> newlyOpened = afterList.stream().filter(s -> !beforeList.contains(s)).map(s -> "Newly opened: " + s);
            final Stream<String> closed = beforeList.stream().filter(s -> !afterList.contains(s)).map(s -> "Closed: " + s);

            final String res = Stream.concat(newlyOpened, closed)
                    .collect(Collectors.joining("\n","List of open Files changed: \n", ""));
            Assertions.fail(res);
        }
    }

    @Override
    public void beforeTestExecution(ExtensionContext extensionContext) {
        extensionContext.getStore(NAMESPACE).put(OPEN_FILES, getOpenFiles());
    }
}
