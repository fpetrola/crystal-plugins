package dev.crystal.plugins.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StandardConflictResolverTest {

    interface Exporter {
    }

    static final class Csv implements Exporter {
    }

    static final class FastCsv implements Exporter {
    }

    static final class Turbo implements Exporter {
    }

    private static RoleImplementation<?> impl(Object instance, String plugin, String version, String... replaces) {
        return new RoleImplementation<>(instance, plugin, version, List.of(replaces));
    }

    private static List<String> plugins(List<RoleImplementation<?>> resolved) {
        return resolved.stream().map(RoleImplementation::pluginId).toList();
    }

    private static List<String> resolve(RoleImplementation<?>... all) {
        return plugins(ConflictResolver.standard().resolve(Exporter.class, List.of(all)));
    }

    @Test
    void aReplacedImplementationIsHidden() {
        assertEquals(List.of("fast"), resolve(impl(new Csv(), "csv", "1.0.0"),
                impl(new FastCsv(), "fast", "1.0.0", "csv")));
    }

    @Test
    void aSingleClassCanBeTargeted() {
        assertEquals(List.of("fast", "csv"), resolve(
                impl(new Csv(), "csv", "1.0.0"),
                impl(new Turbo(), "csv", "1.0.0"),
                impl(new FastCsv(), "fast", "2.0.0", "csv:" + Turbo.class.getName())),
                "only Turbo is replaced; Csv of the same plugin stays");
    }

    @Test
    void chainsHideEverythingSuperseded() {
        assertEquals(List.of("c"), resolve(
                impl(new Csv(), "a", "1.0.0"),
                impl(new FastCsv(), "b", "1.0.0", "a"),
                impl(new Turbo(), "c", "1.0.0", "b")));
    }

    @Test
    void replacementCyclesAreIgnored() {
        assertEquals(List.of("a", "b"), resolve(
                impl(new Csv(), "a", "1.0.0", "b"),
                impl(new FastCsv(), "b", "1.0.0", "a")));
    }

    @Test
    void theRestIsOrderedByVersionThenId() {
        assertEquals(List.of("md", "csv", "xml", "pdf"), resolve(
                impl(new Csv(), "pdf", "1.0.0-SNAPSHOT"),
                impl(new FastCsv(), "xml", "1.0.0"),
                impl(new Turbo(), "csv", "1.0.0"),
                impl(new Csv(), "md", "1.10.0")));
    }

    @Test
    void customResolversCanBeLambdasComposedWithTheStandardOne() {
        ConflictResolver onlyOne = (role, all) -> ConflictResolver.standard().resolve(role, all).subList(0, 1);
        List<RoleImplementation<?>> resolved = onlyOne.resolve(Exporter.class,
                List.of(impl(new Csv(), "csv", "1.0.0"), impl(new FastCsv(), "md", "2.0.0")));
        assertEquals(List.of("md"), plugins(resolved));
    }

    @Test
    void semanticVersionPrecedence() {
        List<String> ascending = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.2.0", "1.10.0", "2.0.0");
        for (int i = 0; i + 1 < ascending.size(); i++) {
            assertTrue(SemanticVersions.compare(ascending.get(i), ascending.get(i + 1)) < 0,
                    ascending.get(i) + " < " + ascending.get(i + 1));
        }
        assertEquals(0, SemanticVersions.compare("1.0.0+build.1", "1.0.0+build.2"), "build metadata is ignored");
    }
}
