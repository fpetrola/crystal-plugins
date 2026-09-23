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
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class PackagePluginMojo extends AbstractMojo {

    static final String FRAMEWORK_GROUP = "dev.crystal.plugins";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Plugin id; defaults to the artifactId, which is already unique within a groupId. */
    @Parameter(property = "crystal.pluginId", defaultValue = "${project.artifactId}")
    private String pluginId;

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
                        Artifact.SCOPE_PROVIDED.equals(a.getScope()) || Artifact.SCOPE_SYSTEM.equals(a.getScope())))
                .toList();
        PluginBuildRequest request = new PluginBuildRequest(new File(project.getBuild().getOutputDirectory()).toPath(),
                jar.toPath(), pluginId, project.getVersion(), project.getGroupId(), project.getArtifactId(),
                project.getDescription(), classpath);

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
        result.warnings().forEach(w -> getLog().warn("crystal: " + w));
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
}
