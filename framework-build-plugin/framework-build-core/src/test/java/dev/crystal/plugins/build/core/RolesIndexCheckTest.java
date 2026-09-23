package dev.crystal.plugins.build.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.RoleInterface;

class RolesIndexCheckTest {

    @TempDir
    Path dir;

    private Path module(String name, String role) {
        return Fixtures.compile(dir.resolve(name), Map.of(role, """
                package %s;
                @dev.crystal.plugins.api.RoleInterface
                public interface %s { }
                """.formatted(role.substring(0, role.lastIndexOf('.')), role.substring(role.lastIndexOf('.') + 1))),
                List.of(Fixtures.location(RoleInterface.class)), true);
    }

    @Test
    void aModuleJarListsItsRoles() {
        Path jar = Fixtures.jar(module("core", "app.core.Equipment"), dir.resolve("core.jar"), Map.of());
        assertEquals(Set.of(), RolesIndexCheck.missing(jar));
    }

    @Test
    void aSingleJarWhoseIndexesWereNotMergedIsCaught() throws IOException {
        Path core = module("core", "app.core.Equipment");
        Path desk = module("desk", "app.desk.DeskEquipment");
        // What shade does without a transformer: the classes of both, the index of only one.
        Path fat = Files.createDirectories(dir.resolve("fat"));
        for (Path module : List.of(desk, core)) {
            try (var files = Files.walk(module)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path target = fat.resolve(module.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path jar = Fixtures.jar(fat, dir.resolve("fat.jar"), Map.of());
        assertEquals(Set.of("app.desk.DeskEquipment"), RolesIndexCheck.missing(jar));

        Files.writeString(fat.resolve(RolesIndexCheck.INDEX), "app.core.Equipment\n# merged\napp.desk.DeskEquipment\n");
        assertEquals(Set.of(), RolesIndexCheck.missing(Fixtures.jar(fat, dir.resolve("merged.jar"), Map.of())));
    }
}
