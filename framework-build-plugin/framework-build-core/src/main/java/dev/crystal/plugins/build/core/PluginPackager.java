package dev.crystal.plugins.build.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns a built jar into a plugin: the build-tool agnostic end of the build plugin.
 *
 * <ol>
 *   <li><b>Cross-check.</b> The extensions found in the bytecode must be exactly the ones the annotation
 *       processor indexed; otherwise the processor did not run on the current classes (a stale
 *       incremental build, or another compiler) and the build fails instead of producing a jar that silently
 *       lacks or invents extensions.</li>
 *   <li><b>Dependencies.</b> Every type referenced by the compiled classes is classified: the plugin's own,
 *       the JDK's, shared API (a provided non-plugin entry: dropped), another plugin (a dependency pinned to
 *       the version compiled against) or a private library (a warning, since jar plugins do not carry
 *       libraries). {@code @Needs} ids are added, pinned too when the plugin is on the classpath.</li>
 *   <li><b>Writing.</b> PF4J manifest attributes, and {@code plugin-metadata.json} completed with what only
 *       the build knows.</li>
 * </ol>
 */
public final class PluginPackager {

    static final String METADATA = "META-INF/plugin-metadata.json";
    static final String EXTENSIONS_INDEX = "META-INF/extensions.idx";
    static final String GENERIC_PLUGIN_CLASS = "dev.crystal.plugins.runtime.internal.RolePlugin";
    static final String FRAMEWORK_GROUP = "dev.crystal.plugins";
    static final String FRAMEWORK_API = "framework-api";
    static final String ROLE_INTERFACE_CLASS = "dev/crystal/plugins/api/RoleInterface";

    private PluginPackager() {
    }

    /**
     * @return what was done; {@link PackagingResult#plugin()} is false for a module without role
     *         implementations, whose jar is left untouched
     * @throws BuildException if the jar cannot be made into a consistent plugin
     */
    public static PackagingResult finish(PluginBuildRequest request) {
        try (Classpath classpath = new Classpath(request.classesDirectory(), request.jar(), request.classpath())) {
            BytecodeAnalyzer bytecode = new BytecodeAnalyzer(classpath);
            Set<String> implementations = bytecode.extensions();
            String json = PluginJarFinisher.read(request.jar(), METADATA);

            if (json == null) {
                if (implementations.isEmpty()) {
                    return PackagingResult.notAPlugin();
                }
                throw new BuildException(implementations + " are role extensions but " + METADATA
                        + " is missing: the role processor did not run on these classes (typically they were "
                        + "compiled before the build plugin was added). Rebuild from clean.");
            }
            PluginMetadata metadata = PluginMetadata.parse(json);
            checkConsistency(metadata.extensionClasses(), implementations);
            checkIndex(PluginJarFinisher.read(request.jar(), EXTENSIONS_INDEX), metadata.extensionClasses());

            String version = PluginVersions.toSemVer(request.version());
            List<String> warnings = new ArrayList<>();
            List<PluginDependency> dependencies = dependencies(request, classpath, bytecode.references(),
                    metadata.needs(), warnings);
            String apiVersion = frameworkApiVersion(classpath);

            Map<String, String> manifest = new LinkedHashMap<>();
            manifest.put("Plugin-Id", request.pluginId());
            manifest.put("Plugin-Version", version);
            manifest.put("Plugin-Class", GENERIC_PLUGIN_CLASS);
            manifest.put("Plugin-Provider", request.groupId());
            if (request.name() != null && !request.name().isBlank()) {
                manifest.put("Plugin-Name", request.name().strip().replaceAll("\\s+", " "));
            }
            if (request.description() != null && !request.description().isBlank()) {
                manifest.put("Plugin-Description", request.description().strip().replaceAll("\\s+", " "));
            }
            if (!dependencies.isEmpty()) {
                manifest.put("Plugin-Dependencies", PluginMetadata.toPf4jDependencies(dependencies));
            }
            manifest.put("Crystal-Api-Version", apiVersion);
            manifest.put("Crystal-Metadata", METADATA);

            Map<String, Path> libraries = new LinkedHashMap<>();
            for (ClasspathEntry entry : request.classpath()) {
                if (entry.library() && Files.isRegularFile(entry.path()) && classpath.plugin(entry).isEmpty()) {
                    libraries.put("lib/" + entry.artifactId() + "-" + entry.version() + ".jar", entry.path());
                }
            }
            PluginJarFinisher.finish(request.jar(), manifest, METADATA, metadata.complete(request.pluginId(), version,
                    apiVersion, roleApis(request, classpath, metadata), dependencies), libraries);
            if (!libraries.isEmpty()) {
                warnings.add("carries " + libraries.size() + " librar" + (libraries.size() == 1 ? "y" : "ies")
                        + " in lib/: " + String.join(", ", libraries.keySet().stream()
                        .map(n -> n.substring(4, n.length() - 4)).toList()));
            }
            return new PackagingResult(true, request.pluginId(), version, metadata.implementedRoles(), dependencies,
                    warnings);
        } catch (IOException e) {
            throw new BuildException("Cannot finish plugin jar " + request.jar() + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException | com.google.gson.JsonParseException e) {
            throw new BuildException(e.getMessage(), e);
        }
    }

    private static void checkConsistency(Set<String> indexed, Set<String> found) {
        if (indexed.equals(found)) {
            return;
        }
        Set<String> missing = new TreeSet<>(found);
        missing.removeAll(indexed);
        Set<String> stale = new TreeSet<>(indexed);
        stale.removeAll(found);
        StringBuilder message = new StringBuilder("Plugin metadata does not match the compiled classes");
        if (!missing.isEmpty()) {
            message.append("; extensions not indexed: ").append(missing);
        }
        if (!stale.isEmpty()) {
            message.append("; indexed but no longer extensions: ").append(stale);
        }
        throw new BuildException(message.append(". The metadata is stale (the role processor did not run on the "
                + "current classes). Rebuild from clean.").toString());
    }

    /**
     * The runtime discovers extensions through PF4J's index only, so every extension must be in it. The index can
     * lack them when PF4J's own @Extension processor wrote it (it runs whenever pf4j is on the compile classpath,
     * and only one processor may create the file per compilation).
     */
    private static void checkIndex(String index, Set<String> extensions) {
        Set<String> listed = new TreeSet<>();
        if (index != null) {
            for (String line : index.split("\\R")) {
                String entry = line.replaceFirst("#.*", "").strip();
                if (!entry.isEmpty()) {
                    listed.add(entry);
                }
            }
        }
        Set<String> missing = new TreeSet<>(extensions);
        missing.removeAll(listed);
        if (!missing.isEmpty()) {
            throw new BuildException(EXTENSIONS_INDEX + " does not list " + missing + ": another annotation processor "
                    + "wrote it, PF4J's own @Extension processor, which runs whenever pf4j is on the compile classpath. "
                    + "A plugin should depend on framework-api and the application's API only; remove the dependency "
                    + "that brings pf4j (typically framework-runtime).");
        }
    }

    private static List<PluginDependency> dependencies(PluginBuildRequest request, Classpath classpath,
                                                       Set<String> references, Set<String> needs,
                                                       List<String> warnings) {
        Map<String, Classpath.PluginIdentity> plugins = new TreeMap<>();
        Map<String, Set<String>> typesByPlugin = new TreeMap<>();
        Map<ClasspathEntry, Set<String>> privateLibraries = new LinkedHashMap<>();

        for (String reference : references) {
            if (classpath.isOwn(reference) || JdkPackages.contains(reference)) {
                continue;
            }
            Optional<ClasspathEntry> entry = classpath.owner(reference);
            if (entry.isEmpty()) {
                continue; // e.g. an annotation whose class javac did not need; nothing to load at runtime
            }
            String type = reference.replace('/', '.');
            Optional<Classpath.PluginIdentity> plugin = classpath.plugin(entry.get());
            if (plugin.isPresent()) {
                if (!plugin.get().id().equals(request.pluginId())) {
                    plugins.put(plugin.get().id(), plugin.get());
                    typesByPlugin.computeIfAbsent(plugin.get().id(), id -> new TreeSet<>()).add(type);
                }
            } else if (!entry.get().provided()) {
                privateLibraries.computeIfAbsent(entry.get(), e -> new TreeSet<>()).add(type);
            }
            // else: shared API, provided by the host
        }

        Map<String, PluginDependency> dependencies = new TreeMap<>();
        plugins.forEach((id, plugin) -> dependencies.put(id, new PluginDependency(id, plugin.version(),
                PluginDependency.Source.BYTECODE, List.copyOf(typesByPlugin.get(id)))));
        for (String needed : needs) {
            if (!dependencies.containsKey(needed) && !needed.equals(request.pluginId())) {
                String version = classpath.pluginById(needed).map(Classpath.PluginIdentity::version).orElse(null);
                dependencies.put(needed, new PluginDependency(needed, version, PluginDependency.Source.NEEDS, List.of()));
            }
        }

        privateLibraries.keySet().removeIf(ClasspathEntry::library); // carried in lib/
        privateLibraries.forEach((entry, types) -> warnings.add("Types of " + entry.coordinates() + " are used "
                + "(e.g. " + types.iterator().next() + ") but it is neither a plugin nor provided: a jar plugin does "
                + "not carry libraries. Bundle it into the plugin jar, or declare it provided if the host supplies it."));
        return List.copyOf(dependencies.values());
    }

    /** For each implemented role, the artifact that supplied the interface when this plugin was compiled. */
    private static List<PluginMetadata.RoleApi> roleApis(PluginBuildRequest request, Classpath classpath,
                                                         PluginMetadata metadata) {
        Set<String> own = metadata.definedRoles();
        List<PluginMetadata.RoleApi> result = new ArrayList<>();
        for (String role : metadata.implementedRoles()) {
            if (own.contains(role)) {
                result.add(new PluginMetadata.RoleApi(role, request.groupId() + ":" + request.artifactId(),
                        request.version()));
                continue;
            }
            classpath.owner(role.replace('.', '/')).ifPresent(entry -> result.add(new PluginMetadata.RoleApi(role,
                    entry.groupId() + ":" + entry.artifactId(), entry.version())));
        }
        return result;
    }

    /** The version of the classpath entry that provides the framework API (found by content, not coordinates). */
    private static String frameworkApiVersion(Classpath classpath) {
        return classpath.owner(ROLE_INTERFACE_CLASS)
                .map(ClasspathEntry::version)
                .orElseThrow(() -> new BuildException(FRAMEWORK_GROUP + ":" + FRAMEWORK_API
                        + " is not on the compile classpath, but the plugin implements roles"));
    }
}
