package dev.crystal.plugins.build.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Turns "this class implements a {@code @RoleInterface}" into standard plugin metadata.
 *
 * <p>For every concrete class of the compilation that implements (directly or through supertypes) an
 * interface annotated with {@code @RoleInterface}, it generates:
 * <ul>
 *   <li>{@code META-INF/extensions.idx}: PF4J's extension index. This is exactly what PF4J's own
 *       {@code @Extension} processor would write, so the runtime discovers classes with PF4J's standard
 *       finder and the author never writes {@code @Extension};</li>
 *   <li>{@code META-INF/services/<role>}: plain {@link java.util.ServiceLoader} registrations, so the jar
 *       is also usable without the framework (tests, tools, other containers);</li>
 *   <li>{@code META-INF/plugin-metadata.json}: the source-derived half of the framework descriptor.</li>
 * </ul>
 *
 * <p>Only <em>extensions</em> are indexed: public, concrete, top-level or static nested classes the framework
 * can construct (see {@code whyNotAnExtension}). Contradictions that would otherwise fail at plugin load
 * time (an {@code @Inject} constructor on a class that cannot be built, misplaced annotations) are compile
 * errors pointing at the offending element.
 *
 * <p>The processor is an <em>aggregating</em> processor: outputs are written once, in the last round.
 */
public final class RoleProcessor extends AbstractProcessor {

    /** Binary class name to model; TreeMap keeps outputs byte-for-byte reproducible. */
    private final Map<String, ExtensionModel> extensions = new TreeMap<>();
    private final Set<String> definedRoles = new TreeSet<>();
    private final List<TypeElement> originating = new ArrayList<>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // Implementers carry no annotation at all, so we must look at every root element.
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            if (!extensions.isEmpty()) {
                writeOutputs();
            }
            return false;
        }
        for (Element root : round.getRootElements()) {
            if (root instanceof TypeElement type) {
                visit(type);
            }
        }
        // Never claim annotations: other processors (Lombok, Dagger, ...) must still see them.
        return false;
    }

    private void visit(TypeElement type) {
        inspect(type);
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
            visit(nested);
        }
    }

    private void inspect(TypeElement type) {
        boolean isRole = hasAnnotation(type, Contract.ROLE_INTERFACE);
        if (isRole) {
            if (type.getKind() != ElementKind.INTERFACE) {
                error(type, "@RoleInterface can only be placed on an interface");
            } else {
                definedRoles.add(binaryName(type));
            }
        }

        Set<String> roles = rolesOf(type);
        List<String> replaces = stringValues(type, Contract.REPLACES);
        List<String> needs = stringValues(type, Contract.NEEDS);
        String notAnExtension = roles.isEmpty() ? "it implements no @RoleInterface" : whyNotAnExtension(type, roles);

        if (notAnExtension != null) {
            if (!replaces.isEmpty() || !needs.isEmpty()) {
                error(type, "@Replaces/@Needs only apply to extensions, and %s is not one: %s",
                        type.getSimpleName(), notAnExtension);
            }
            return;
        }

        String name = binaryName(type);
        extensions.put(name, new ExtensionModel(name, List.copyOf(roles), replaces, needs,
                implementsInterface(type, Contract.HAS_LIFECYCLE), hasPublicNoArgConstructor(type)));
        originating.add(type);
    }

    /**
     * Returns null if {@code type} (which implements a role) is an <em>extension</em>, otherwise why it is not.
     *
     * <p>An extension is a public, concrete class or record, top-level or static nested, that the framework can
     * construct: through its {@code @Inject} constructor or its public no-arg one. Any other role implementation
     * (a decorator, a variant built with parameters, an internal helper) is an ordinary class its owner builds.
     * A public one gets a note, in case an {@code @Inject} was forgotten; a non-public one is clearly internal.
     *
     * <p>An {@code @Inject} constructor declares the intent to be built by the framework, so for such a class
     * every obstacle is an error.
     */
    private String whyNotAnExtension(TypeElement type, Set<String> roles) {
        if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
            return "it is not a class";
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return "it is abstract";
        }
        List<ExecutableElement> injectable = ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .filter(c -> hasAnnotation(c, Contract.JAKARTA_INJECT) || hasAnnotation(c, Contract.JAVAX_INJECT))
                .toList();
        List<String> problems = new ArrayList<>();
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            problems.add("it is not public");
        }
        if (type.getNestingKind() == NestingKind.MEMBER && type.getKind() == ElementKind.CLASS
                && !type.getModifiers().contains(Modifier.STATIC)) {
            problems.add("it is an inner (non-static) class");
        }
        if (!injectable.isEmpty()) {
            if (injectable.size() > 1) {
                problems.add("more than one constructor is annotated with @Inject");
            }
            for (String problem : problems) {
                error(injectable.get(0), "%s has an @Inject constructor but cannot be an extension: %s",
                        type.getSimpleName(), problem);
            }
            return problems.isEmpty() ? null : String.join(", ", problems);
        }
        if (!hasPublicNoArgConstructor(type)) {
            problems.add("it has neither a public no-arg constructor nor an @Inject one");
        }
        if (problems.isEmpty()) {
            return null;
        }
        String reason = String.join(", ", problems);
        if (type.getModifiers().contains(Modifier.PUBLIC)) {
            note(type, "%s implements %s but is not an extension (%s), so the framework will not create it. "
                    + "Annotate a constructor with @Inject if it should.", type.getSimpleName(), roles, reason);
        }
        return reason;
    }

    private static boolean hasPublicNoArgConstructor(TypeElement type) {
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(c -> c.getParameters().isEmpty() && c.getModifiers().contains(Modifier.PUBLIC));
    }

    /** All @RoleInterface interfaces reachable through the supertypes of {@code type}, sorted. */
    private Set<String> rolesOf(TypeElement type) {
        Set<String> roles = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        Deque<TypeMirror> work = new ArrayDeque<>(processingEnv.getTypeUtils().directSupertypes(type.asType()));
        while (!work.isEmpty()) {
            TypeMirror current = work.pop();
            if (current.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement element = (TypeElement) ((DeclaredType) current).asElement();
            if (!seen.add(element.getQualifiedName().toString())) {
                continue;
            }
            if (element.getKind() == ElementKind.INTERFACE && hasAnnotation(element, Contract.ROLE_INTERFACE)) {
                roles.add(binaryName(element));
            }
            work.addAll(processingEnv.getTypeUtils().directSupertypes(current));
        }
        return roles;
    }

    private boolean implementsInterface(TypeElement type, String interfaceName) {
        TypeElement target = processingEnv.getElementUtils().getTypeElement(interfaceName);
        return target != null && processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(type.asType()),
                processingEnv.getTypeUtils().erasure(target.asType()));
    }

    private void writeOutputs() {
        TypeElement[] origins = originating.toArray(new TypeElement[0]);

        StringBuilder index = new StringBuilder("# Generated by crystal-plugins (framework-build-processor)\n");
        Map<String, Set<String>> byRole = new TreeMap<>();
        for (ExtensionModel e : extensions.values()) {
            index.append(e.className()).append('\n');
            // ServiceLoader can only instantiate public classes with a public no-arg constructor; listing any
            // other one would make it throw ServiceConfigurationError for every consumer of that role.
            if (e.serviceLoadable()) {
                for (String role : e.roles()) {
                    byRole.computeIfAbsent(role, r -> new TreeSet<>()).add(e.className());
                }
            }
        }
        write(Contract.EXTENSIONS_INDEX, index.toString(), origins);

        for (Map.Entry<String, Set<String>> entry : byRole.entrySet()) {
            write(Contract.SERVICES_DIR + entry.getKey(), String.join("\n", entry.getValue()) + "\n", origins);
        }

        write(Contract.METADATA, MetadataJson.write(extensions.values(), definedRoles), origins);
    }

    private void write(String resource, String content, Element[] origins) {
        try {
            FileObject file = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", resource, origins);
            try (Writer writer = file.openWriter()) {
                writer.write(content);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "crystal-plugins: cannot write " + resource + ": " + e.getMessage());
        }
    }

    private static boolean hasAnnotation(Element element, String annotationName) {
        return findAnnotation(element, annotationName) != null;
    }

    private static AnnotationMirror findAnnotation(Element element, String annotationName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getQualifiedName().contentEquals(annotationName)) {
                return mirror;
            }
        }
        return null;
    }

    /** Reads {@code value()} of a {@code String[]} annotation (a single string is accepted too). */
    private static List<String> stringValues(Element element, String annotationName) {
        AnnotationMirror mirror = findAnnotation(element, annotationName);
        if (mirror == null) {
            return List.of();
        }
        Set<String> values = new TreeSet<>();
        mirror.getElementValues().forEach((method, value) -> {
            if (method.getSimpleName().contentEquals("value")) {
                collect(value, values);
            }
        });
        return List.copyOf(values);
    }

    private static void collect(AnnotationValue value, Set<String> out) {
        Object raw = value.getValue();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                collect((AnnotationValue) item, out);
            }
        } else if (raw instanceof String s) {
            out.add(s);
        }
    }

    private String binaryName(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    private void note(Element element, String format, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "crystal-plugins: " + String.format(format, args), element);
    }

    private void error(Element element, String format, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "crystal-plugins: " + String.format(format, args), element);
    }
}
