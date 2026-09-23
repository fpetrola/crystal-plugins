package dev.crystal.plugins.build.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds the {@code @RoleInterface} interfaces of a jar that its {@value #INDEX} does not list. In a jar built
 * from one module that never happens (the processor writes the index); in a single jar made of several modules
 * (shade, assembly) it means the indexes were not merged: each module has one with the same name and only one
 * survived, so the roles of the others are not discovered at run time.
 */
public final class RolesIndexCheck {

    public static final String INDEX = "META-INF/crystal/roles.idx";
    private static final String ROLE_INTERFACE = "Ldev/crystal/plugins/api/RoleInterface;";

    private RolesIndexCheck() {
    }

    /** @return binary names of the role interfaces in {@code jar} missing from its index, sorted */
    public static Set<String> missing(Path jar) {
        Set<String> roles = new TreeSet<>();
        Set<String> indexed = new TreeSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals(INDEX)) {
                    indexed.addAll(read(file.getInputStream(entry)));
                } else if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        String role = role(in.readAllBytes());
                        if (role != null) {
                            roles.add(role);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new BuildException("Cannot read " + jar + ": " + e.getMessage(), e);
        }
        roles.removeAll(indexed);
        return roles;
    }

    /** The binary name of the class if it is an interface annotated with {@code @RoleInterface}, else null. */
    private static String role(byte[] bytes) {
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) == 0 || (reader.getAccess() & Opcodes.ACC_ANNOTATION) != 0) {
            return null;
        }
        boolean[] annotated = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                annotated[0] |= ROLE_INTERFACE.equals(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return annotated[0] ? reader.getClassName().replace('/', '.') : null;
    }

    private static Set<String> read(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Set<String> names = new TreeSet<>();
            reader.lines().map(line -> line.replaceFirst("#.*", "").strip()).filter(line -> !line.isEmpty())
                    .forEach(names::add);
            return names;
        }
    }
}
