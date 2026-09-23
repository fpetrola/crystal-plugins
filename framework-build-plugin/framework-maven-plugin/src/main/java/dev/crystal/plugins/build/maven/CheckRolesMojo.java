package dev.crystal.plugins.build.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import dev.crystal.plugins.build.core.BuildException;
import dev.crystal.plugins.build.core.RolesIndexCheck;

/**
 * Fails the build when a jar the project produced (the main one or an attached one, such as a shaded jar)
 * contains {@code @RoleInterface} interfaces its {@code META-INF/crystal/roles.idx} does not list. That is a
 * single jar made of several modules whose indexes were not merged: at run time those roles would not be
 * discovered, and the error would speak of a missing Guice binding instead. Runs at {@code verify}, after
 * everything {@code package} does (shade included).
 */
@Mojo(name = "check-roles", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckRolesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Skips the check (for instance, roles bound by hand with {@code PluginsModule.of(plugins, roles...)}). */
    @Parameter(property = "crystal.skipRolesCheck", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            return;
        }
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(project.getArtifact());
        artifacts.addAll(project.getAttachedArtifacts());
        for (Artifact artifact : artifacts) {
            File file = artifact.getFile();
            if (file == null || !file.isFile() || !file.getName().endsWith(".jar")) {
                continue;
            }
            Set<String> missing;
            try {
                missing = RolesIndexCheck.missing(file.toPath());
            } catch (BuildException e) {
                throw new MojoExecutionException("crystal: " + e.getMessage(), e);
            }
            if (!missing.isEmpty()) {
                throw new MojoExecutionException("crystal: " + file.getName() + " has roles that "
                        + RolesIndexCheck.INDEX + " does not list, so they would not be discovered: "
                        + String.join(", ", missing) + ". A single jar must merge the " + RolesIndexCheck.INDEX
                        + " of every module (maven-shade: AppendingTransformer with that resource)."
                        + " -Dcrystal.skipRolesCheck skips this check");
            }
        }
    }
}
