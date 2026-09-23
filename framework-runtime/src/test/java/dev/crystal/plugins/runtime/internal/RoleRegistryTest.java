package dev.crystal.plugins.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.crystal.plugins.api.ConflictResolver;
import dev.crystal.plugins.api.RoleImplementation;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;

class RoleRegistryTest {

    record Named(String format) implements ReportExporter {
        public String export(List<String> rows) {
            return String.join(",", rows);
        }
    }

    private static Contribution contribution(String plugin, String version, String format, String... replaces) {
        return new Contribution(plugin, version, new Named(format), Set.of(ReportExporter.class), List.of(replaces));
    }

    private static List<String> formats(Set<ReportExporter> view) {
        return view.stream().map(ReportExporter::format).toList();
    }

    @Test
    void replacementIsReversible() {
        RoleRegistry registry = new RoleRegistry(ConflictResolver.standard());
        Set<ReportExporter> view = registry.view(ReportExporter.class);

        registry.publish(List.of(contribution("csv", "1.0.0", "csv")));
        assertEquals(List.of("csv"), formats(view));

        registry.publish(List.of(contribution("fast", "1.0.0", "fast", "csv")));
        assertEquals(List.of("fast"), formats(view), "hidden while the replacement is active");

        registry.withdraw("fast", new ClassLoader(null) { });   // a plugin's loader, not the role's
        assertEquals(List.of("csv"), formats(view), "and back as soon as it is not");
        assertSame(view, registry.view(ReportExporter.class));
    }

    @Test
    void aResolverMayOnlyChooseAmongWhatItIsGiven() {
        RoleImplementation<?> stranger = new RoleImplementation<>(new Named("x"), "x", "1.0.0", List.of());
        RoleRegistry registry = new RoleRegistry((role, all) -> List.of(stranger));
        registry.publish(List.of(contribution("csv", "1.0.0", "csv")));

        assertThrows(IllegalStateException.class, () -> registry.view(ReportExporter.class).size());
    }
}
