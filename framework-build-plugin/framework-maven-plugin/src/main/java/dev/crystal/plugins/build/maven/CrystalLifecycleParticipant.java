package dev.crystal.plugins.build.maven;

import java.util.Arrays;
import java.util.List;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Makes "declare the plugin with {@code <extensions>true</extensions>}" the only thing an author writes.
 *
 * <p>For every {@code jar} project that declares this plugin it:
 * <ol>
 *   <li>adds the role processor as a {@code provided} dependency (never leaks to consumers);</li>
 *   <li>turns annotation processing on explicitly ({@code -proc:full}): since JDK 23 javac no longer runs
 *       processors found on the classpath implicitly. If the author already uses
 *       {@code annotationProcessorPaths}, the processor is appended there instead, because javac then ignores
 *       the classpath for discovery. If the author lists {@code annotationProcessors} by class name, ours is
 *       appended to that list too, because javac then runs only those;</li>
 *   <li>binds {@code package-plugin} to {@code package}, and {@code bundle-plugins} (a no-op unless configured)
 *       to {@code process-classes}, and {@code check-roles} (roles missing from the index of a single jar) to
 *       {@code verify}.</li>
 * </ol>
 * Explicit author configuration always wins: nothing here overrides a value that is already set.
 *
 * <p>Registered through {@code META-INF/plexus/components.xml} (see the comment there).
 */
public class CrystalLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    static final String PLUGIN_KEY = PackagePluginMojo.FRAMEWORK_GROUP + ":framework-maven-plugin";
    static final String PROCESSOR_ARTIFACT = "framework-build-processor";
    static final String PROCESSOR_CLASS = "dev.crystal.plugins.build.processor.RoleProcessor";
    static final String COMPILER_KEY = "org.apache.maven.plugins:maven-compiler-plugin";
    static final String EXECUTION_ID = "crystal-package-plugin";

    @Override
    public void afterProjectsRead(MavenSession session) {
        for (MavenProject project : session.getProjects()) {
            Plugin self = project.getPlugin(PLUGIN_KEY);
            if (self == null || !"jar".equals(project.getPackaging())) {
                continue;
            }
            String version = self.getVersion();
            wireProcessor(project, version);
            bindPackaging(self);
        }
    }

    static void wireProcessor(MavenProject project, String version) {
        Plugin compiler = project.getPlugin(COMPILER_KEY);
        if (compiler == null) {
            compiler = new Plugin();
            compiler.setGroupId("org.apache.maven.plugins");
            compiler.setArtifactId("maven-compiler-plugin");
            project.getBuild().addPlugin(compiler);
        }

        // Plugin-level configuration was already expanded into each execution when the model was built,
        // so both levels are updated.
        boolean usesProcessorPath = appendToProcessorPath((Xpp3Dom) compiler.getConfiguration(), version);
        compiler.setConfiguration(withProcessing(compiler.getConfiguration()));
        for (PluginExecution execution : compiler.getExecutions()) {
            usesProcessorPath |= appendToProcessorPath((Xpp3Dom) execution.getConfiguration(), version);
            execution.setConfiguration(withProcessing(execution.getConfiguration()));
        }

        if (!usesProcessorPath && !hasDependency(project.getDependencies())) {
            Dependency processor = new Dependency();
            processor.setGroupId(PackagePluginMojo.FRAMEWORK_GROUP);
            processor.setArtifactId(PROCESSOR_ARTIFACT);
            processor.setVersion(version);
            processor.setScope("provided");
            processor.setOptional(true);
            project.getModel().addDependency(processor);
        }
    }

    /** Appends our processor to an existing {@code annotationProcessorPaths}; returns whether one exists. */
    private static boolean appendToProcessorPath(Xpp3Dom configuration, String version) {
        if (configuration == null) {
            return false;
        }
        Xpp3Dom paths = configuration.getChild("annotationProcessorPaths");
        if (paths == null) {
            return false;
        }
        for (Xpp3Dom path : paths.getChildren()) {
            Xpp3Dom artifactId = path.getChild("artifactId");
            if (artifactId != null && PROCESSOR_ARTIFACT.equals(artifactId.getValue())) {
                return true;
            }
        }
        Xpp3Dom path = new Xpp3Dom("path");
        path.addChild(text("groupId", PackagePluginMojo.FRAMEWORK_GROUP));
        path.addChild(text("artifactId", PROCESSOR_ARTIFACT));
        path.addChild(text("version", version));
        paths.addChild(path);
        return true;
    }

    private static Object withProcessing(Object existing) {
        Xpp3Dom configuration = existing == null ? new Xpp3Dom("configuration") : (Xpp3Dom) existing;
        if (configuration.getChild("proc") == null) {
            configuration.addChild(text("proc", "full"));
        }
        // An explicit list of processors is all javac runs, whatever it finds: ours has to be in it.
        Xpp3Dom processors = configuration.getChild("annotationProcessors");
        if (processors != null && Arrays.stream(processors.getChildren())
                .noneMatch(p -> PROCESSOR_CLASS.equals(p.getValue()))) {
            processors.addChild(text("annotationProcessor", PROCESSOR_CLASS));
        }
        return configuration;
    }

    private static boolean hasDependency(List<Dependency> dependencies) {
        return dependencies.stream().anyMatch(d -> PackagePluginMojo.FRAMEWORK_GROUP.equals(d.getGroupId())
                && PROCESSOR_ARTIFACT.equals(d.getArtifactId()));
    }

    static void bindPackaging(Plugin self) {
        bind(self, EXECUTION_ID, "package", "package-plugin");
        // A no-op unless bundleGroupId is configured (host applications carrying default plugins).
        bind(self, "crystal-bundle-plugins", "process-classes", "bundle-plugins");
        // After package, so it sees the final jar (a shaded one included).
        bind(self, "crystal-check-roles", "verify", "check-roles");
    }

    private static void bind(Plugin self, String id, String phase, String goal) {
        if (self.getExecutions().stream().anyMatch(e -> e.getGoals().contains(goal))) {
            return;
        }
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase(phase);
        execution.addGoal(goal);
        self.addExecution(execution);
    }

    private static Xpp3Dom text(String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name);
        node.setValue(value);
        return node;
    }
}
