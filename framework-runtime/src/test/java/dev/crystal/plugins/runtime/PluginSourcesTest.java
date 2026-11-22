package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.PluginArtifact;

class PluginSourcesTest {

    @TempDir
    Path repo;

    @Test
    void directoryOffersOnlyJarsThatArePlugins() throws IOException {
        Path plugin = PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", """
                package acme.csv;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "csv"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """).buildInto(repo);
        // Typical stowaway: a build tool's jar with a manifest but no plugin identity.
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(repo.resolve("surefirebooter-123.jar"));
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.finish();
        }

        List<PluginArtifact> artifacts = PluginSources.directory(repo).artifacts();

        assertEquals(List.of(new PluginArtifact("csv", "1.0.0", PluginSources.sha256(plugin))), artifacts);
    }

    /** What a plugin offers without it being an extension comes with the rest, as a kind of what it extends. */
    @Test
    void whatIsOfferedWithoutBeingAnExtensionIsOfferedToo() throws IOException {
        String json = """
                {"format": 1,
                 "extensions": [{"class": "p.Devices", "roles": ["app.Extension"], "replaces": [], "needs": [], "lifecycle": false}],
                 "offered": [{"class": "p.Plus3", "roles": ["app.Machine"],
                              "offers": [{"text": "Spectrum Plus 3", "icon": "desktop", "description": "A disk drive"}]}],
                 "definesRoles": []}
                """;
        dev.crystal.plugins.api.PluginDescription described =
                PluginSources.description(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals(List.of(new dev.crystal.plugins.api.Offer("p.Plus3", "Spectrum Plus 3", "desktop", List.of("app.Machine"),
                "A disk drive", "")), described.offers());
        assertEquals(List.of("app.Extension"), described.implementsRoles(), "what it is a kind of is no role it plays");
    }
}
