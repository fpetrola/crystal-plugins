package dev.crystal.plugins.build.maven;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import dev.crystal.plugins.build.core.BuildException;
import dev.crystal.plugins.build.core.ClasspathEntry;
import dev.crystal.plugins.build.core.PackagingResult;
import dev.crystal.plugins.build.core.PluginBuildRequest;
import dev.crystal.plugins.build.core.PluginDependency;
import dev.crystal.plugins.build.core.PluginPackager;

/**
 * Finishes a plugin jar. All the logic lives in {@link PluginPackager} (framework-build-core); this mojo
 * only translates the Maven project into its terms.
 *
 * <p>A module without role implementations is left untouched, so the build plugin can be declared once in
 * a parent POM that also builds API modules. Bound to {@code package} by {@link CrystalLifecycleParticipant},
 * after {@code jar:jar}.
 */
@Mojo(name = "package-plugin", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PackagePluginMojo extends AbstractMojo {

    static final String FRAMEWORK_GROUP = "dev.crystal.plugins";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Plugin id; defaults to the artifactId, which is already unique within a groupId. */
    @Parameter(property = "crystal.pluginId", defaultValue = "${project.artifactId}")
    private String pluginId;

    /**
     * Whether the plugin carries its third-party libraries in {@code lib/} (see {@link #library}). False: none,
     * the host must supply them all.
     */
    @Parameter(property = "crystal.pluginLibraries", defaultValue = "true")
    private boolean libraries;

    @Parameter(property = "crystal.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("crystal: skipped");
            return;
        }
        Artifact main = project.getArtifact();
        File jar = main == null ? null : main.getFile();
        if (jar == null || !jar.isFile()) {
            throw new MojoExecutionException("crystal: no jar to finish; package-plugin must run after jar:jar");
        }
        List<ClasspathEntry> classpath = project.getArtifacts().stream()
                .filter(a -> a.getFile() != null)
                .map(a -> new ClasspathEntry(a.getFile().toPath(), a.getGroupId(), a.getArtifactId(),
                        a.getBaseVersion(),
                        Artifact.SCOPE_PROVIDED.equals(a.getScope()) || Artifact.SCOPE_SYSTEM.equals(a.getScope()),
                        libraries && library(a)))
                .toList();
        PluginBuildRequest request = new PluginBuildRequest(new File(project.getBuild().getOutputDirectory()).toPath(),
                jar.toPath(), pluginId, project.getVersion(), project.getGroupId(), project.getArtifactId(),
                project.getDescription(), classpath, name());

        PackagingResult result;
        try {
            result = PluginPackager.finish(request);
        } catch (BuildException e) {
            throw new MojoExecutionException("crystal: " + e.getMessage(), e);
        }
        if (!result.plugin()) {
            getLog().debug("crystal: no role implementations in " + jar.getName() + ", not a plugin");
            return;
        }
        result.warnings().forEach(w -> {
            if (w.startsWith("carries ")) {
                getLog().info("crystal: " + w);
            } else {
                getLog().warn("crystal: " + w);
            }
        });
        String dependencies = result.dependencies().isEmpty() ? "no dependencies"
                : "depends on " + result.dependencies().stream().map(PluginMojos::describe)
                        .collect(Collectors.joining(", "));
        getLog().info("crystal: plugin " + result.pluginId() + "@" + result.version() + " ("
                + result.roles().size() + " role(s), " + dependencies + ") -> " + jar.getName());
    }

    /** Formatting helpers kept out of the mojo body. */
    static final class PluginMojos {
        private PluginMojos() {
        }

        static String describe(PluginDependency d) {
            return d.version() == null ? d.id() + " (any version, @Needs)" : d.id() + "@" + d.version();
        }
    }

    /**
     * Whether {@code a} is a third-party library the plugin carries: needed at run time ({@code compile} or
     * {@code runtime}) and not part of the application's family: not of the plugin's own groupId (or one under it,
     * where the application's modules live), not the framework, and not brought in by any of those. What the
     * application family brings, the application has; the runtime also skips a carried library the application
     * turns out to have.
     */
    private boolean library(Artifact a) {
        if (!Artifact.SCOPE_COMPILE.equals(a.getScope()) && !Artifact.SCOPE_RUNTIME.equals(a.getScope())) {
            return false;
        }
        if (!"jar".equals(a.getType()) || family(a.getGroupId())) {
            return false;
        }
        List<String> trail = a.getDependencyTrail();
        if (trail != null) {
            for (int i = 1; i < trail.size() - 1; i++) { // between the project and the artifact itself
                if (family(trail.get(i).substring(0, trail.get(i).indexOf(':')))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean family(String groupId) {
        String own = project.getGroupId();
        return groupId.equals(own) || groupId.startsWith(own + ".") || groupId.equals(FRAMEWORK_GROUP)
                || groupId.startsWith(FRAMEWORK_GROUP + ".");
    }

    /**
     * The plugin's name for people: {@code crystal.pluginName}, else the pom's own {@code <name>} (Maven does not
     * inherit it, so a module without one gets none and tools show its id).
     */
    private String name() {
        String configured = project.getProperties().getProperty("crystal.pluginName");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String name = project.getModel().getName();
        return name == null || name.isBlank() || name.equals(project.getArtifactId()) ? null : name;
    }
}
