package dev.crystal.plugins.build.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import dev.crystal.plugins.build.core.BuildException;
import dev.crystal.plugins.build.core.PluginBundler;

/**
 * Puts default plugins inside the host application: the plugin jars of {@code bundleGroupId} (and the groupIds
 * under it) at {@code bundleVersion}, taken from the local repository, go into the application's classes, so
 * the jar (or the uber-jar) carries them. The application installs them with
 * {@code PluginService.builder().defaults(PluginSources.bundled())}.
 *
 * <p>Does nothing unless {@code bundleGroupId} is set. Runs at {@code process-classes}, before the tests, so the
 * application's tests see the bundled plugins too.
 */
@Mojo(name = "bundle-plugins", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class BundlePluginsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    private String localRepository;

    /** GroupId of the plugins to bundle; plugins of groupIds under it are included too. */
    @Parameter(property = "crystal.bundleGroupId")
    private String bundleGroupId;

    /** Version of the plugins to bundle; defaults to the application's own. Never "the latest". */
    @Parameter(property = "crystal.bundleVersion", defaultValue = "${project.version}")
    private String bundleVersion;

    @Override
    public void execute() throws MojoExecutionException {
        if (bundleGroupId == null || bundleGroupId.isBlank()) {
            return;
        }
        Path classes = new File(project.getBuild().getOutputDirectory()).toPath();
        List<PluginBundler.Bundled> bundled;
        try {
            bundled = PluginBundler.bundle(Path.of(localRepository), bundleGroupId, bundleVersion,
                    project.getGroupId() + ":" + project.getArtifactId(), classes);
        } catch (BuildException e) {
            throw new MojoExecutionException("crystal: " + e.getMessage(), e);
        }
        if (bundled.isEmpty()) {
            getLog().warn("crystal: no plugins of " + bundleGroupId + " at version " + bundleVersion + " in "
                    + localRepository);
        }
        bundled.forEach(b -> getLog().info("crystal: bundled " + b.id() + "@" + b.version() + " (" + b.coordinates()
                + ")"));
    }
}
