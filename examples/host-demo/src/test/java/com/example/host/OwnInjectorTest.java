package com.example.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.emulator.Clock;
import com.example.emulator.Peripheral;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

import dev.crystal.plugins.guice.PluginsModule;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.PluginSources;
import jakarta.inject.Inject;

/** An application that builds its own object graph with Guice: plugins just appear in it. */
class OwnInjectorTest {

    private static final Path PLUGINS = Path.of(System.getProperty("plugins.dir"));

    /** Application code: no import of the framework. */
    public static final class Machine {
        final Set<Peripheral> peripherals;
        final Clock clock;

        @Inject
        public Machine(Set<Peripheral> peripherals, Clock clock) {
            this.peripherals = peripherals;
            this.clock = clock;
        }
    }

    /** The application's own module, as it was before plugins. */
    private static final class EmulatorModule extends AbstractModule {
        @Override
        protected void configure() {
            bind(Clock.class).toInstance(() -> 3_500_000L);
        }
    }

    @Test
    void rolesAreInjectedByTheApplicationsInjector() {
        Clock clock = () -> 69_888L;
        try (PluginService plugins = PluginService.builder()
                .source(PluginSources.directory(PLUGINS.resolve("emulator")))
                .expose(Clock.class, clock)
                .build()) {
            plugins.start();

            // The only place the framework is named: the startup, where the injector is built.
            Injector injector = Guice.createInjector(new EmulatorModule(), PluginsModule.of(plugins));
            Machine machine = injector.getInstance(Machine.class);

            assertEquals(Set.of("beeper"), Set.copyOf(machine.peripherals.stream().map(Peripheral::name).toList()));
            assertEquals(3_500_000L, machine.clock.tStates(), "the application's own bindings are untouched");
        }
    }
}
