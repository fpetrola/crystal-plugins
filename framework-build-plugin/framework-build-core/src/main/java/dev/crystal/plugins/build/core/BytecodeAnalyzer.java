package dev.crystal.plugins.build.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * What the compiled plugin actually is, read from its bytecode.
 *
 * <ul>
 *   <li>{@link #references()}: every type the plugin's classes mention anywhere (descriptors, generic
 *       signatures, annotations, method bodies, constants): the input of dependency analysis.</li>
 *   <li>{@link #extensions()}: the classes the processor must have indexed, by the processor's own rules.
 *       It cross-checks the processor's output, which goes stale if javac did not run the processor.</li>
 * </ul>
 */
final class BytecodeAnalyzer {

    static final String ROLE_INTERFACE = "Ldev/crystal/plugins/api/RoleInterface;";
    private static final Set<String> INJECT = Set.of("Ljakarta/inject/Inject;", "Ljavax/inject/Inject;");

    /**
     * What extension detection needs from a class file.
     *
     * @param access           source-level modifiers: for a nested class they come from its own InnerClasses
     *                         entry, since the class file flags lose {@code private} and {@code static}
     * @param member           nested in another class (not local, not anonymous)
     * @param injectConstructors constructors annotated with {@code @Inject}
     * @param publicNoArg      has a public no-arg constructor
     */
    private record Header(int access, String superName, List<String> interfaces, Set<String> annotations,
                          boolean localOrAnonymous, boolean member, int injectConstructors, boolean publicNoArg) {
    }

    private final Classpath classpath;
    private final Map<String, Optional<Header>> headers = new HashMap<>();

    BytecodeAnalyzer(Classpath classpath) {
        this.classpath = classpath;
    }

    /** Internal names referenced by the classes compiled from the plugin's sources. */
    Set<String> references() throws IOException {
        Set<String> references = new TreeSet<>();
        Remapper collector = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                references.add(internalName);
                return internalName;
            }
        };
        for (String name : classpath.compiledClasses()) {
            byte[] bytes = classpath.bytes(name).orElseThrow();
            // The remapper sees every type name the class file contains; the writer is only there so every
            // visit (fields, methods, bodies, annotations) is delegated rather than skipped.
            new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), collector), 0);
        }
        return references;
    }

    /**
     * Binary names ({@code a.b.Outer$Inner}) of the compiled classes that are extensions, by the processor's
     * rules: a public, concrete, top-level or static nested class implementing a {@code @RoleInterface}, with
     * one {@code @Inject} constructor or a public no-arg one.
     */
    Set<String> extensions() throws IOException {
        Set<String> result = new TreeSet<>();
        for (String name : classpath.compiledClasses()) {
            if (isExtensionShaped(header(name).orElseThrow()) && implementsRole(name)) {
                result.add(name.replace('/', '.'));
            }
        }
        return result;
    }

    private static boolean isExtensionShaped(Header header) {
        int access = header.access();
        if ((access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ENUM | Opcodes.ACC_SYNTHETIC
                | Opcodes.ACC_ANNOTATION)) != 0 || header.localOrAnonymous()) {
            return false;
        }
        boolean isPublic = (access & Opcodes.ACC_PUBLIC) != 0;
        boolean inner = header.member() && (access & Opcodes.ACC_STATIC) == 0;
        boolean constructible = header.injectConstructors() > 0 || header.publicNoArg();
        return isPublic && !inner && constructible;
    }

    private boolean implementsRole(String className) throws IOException {
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.add(className);
        while (!work.isEmpty()) {
            String current = work.pop();
            if (!seen.add(current) || JdkPackages.contains(current)) {
                continue;
            }
            Optional<Header> header = header(current);
            if (header.isEmpty()) {
                continue;
            }
            int access = header.get().access();
            boolean plainInterface = (access & Opcodes.ACC_INTERFACE) != 0 && (access & Opcodes.ACC_ANNOTATION) == 0;
            if (plainInterface && header.get().annotations().contains(ROLE_INTERFACE)) {
                return true;
            }
            if (header.get().superName() != null) {
                work.add(header.get().superName());
            }
            work.addAll(header.get().interfaces());
        }
        return false;
    }

    private Optional<Header> header(String internalName) throws IOException {
        Optional<Header> cached = headers.get(internalName);
        if (cached != null) {
            return cached;
        }
        Optional<Header> header = classpath.bytes(internalName).map(BytecodeAnalyzer::readHeader);
        headers.put(internalName, header);
        return header;
    }

    private static Header readHeader(byte[] bytes) {
        int[] access = new int[1];
        String[] self = new String[1];
        String[] superName = new String[1];
        List<String> interfaces = new ArrayList<>();
        Set<String> annotations = new HashSet<>();
        boolean[] localOrAnonymous = new boolean[1];
        boolean[] member = new boolean[1];
        int[] injectConstructors = new int[1];
        boolean[] publicNoArg = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int flags, String name, String signature, String superType,
                              String[] implemented) {
                access[0] = flags;
                self[0] = name;
                superName[0] = superType;
                if (implemented != null) {
                    interfaces.addAll(List.of(implemented));
                }
            }

            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                // Only local and anonymous classes carry an EnclosingMethod attribute.
                localOrAnonymous[0] = true;
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int flags) {
                if (!name.equals(self[0])) {
                    return;
                }
                if (outerName == null || innerName == null) {
                    localOrAnonymous[0] = true;
                } else {
                    member[0] = true;
                    access[0] = flags;
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible) {
                    annotations.add(descriptor);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int flags, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (!name.equals("<init>")) {
                    return null;
                }
                if (descriptor.equals("()V") && (flags & Opcodes.ACC_PUBLIC) != 0) {
                    publicNoArg[0] = true;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (INJECT.contains(annotation)) {
                            injectConstructors[0]++;
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Header(access[0], superName[0], List.copyOf(interfaces), Set.copyOf(annotations),
                localOrAnonymous[0], member[0], injectConstructors[0], publicNoArg[0]);
    }
}
