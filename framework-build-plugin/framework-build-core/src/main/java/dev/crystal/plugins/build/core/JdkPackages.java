package dev.crystal.plugins.build.core;

import java.lang.module.ModuleFinder;
import java.util.Set;
import java.util.stream.Collectors;

/** Packages of the JDK the build runs on: every package of every system module. */
final class JdkPackages {

    private static final Set<String> PACKAGES = ModuleFinder.ofSystem().findAll().stream()
            .flatMap(module -> module.descriptor().packages().stream())
            .collect(Collectors.toUnmodifiableSet());

    private JdkPackages() {
    }

    /** @param internalName class name in internal form ({@code java/util/List}) */
    static boolean contains(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash > 0 && PACKAGES.contains(internalName.substring(0, slash).replace('/', '.'));
    }
}
