package dev.crystal.plugins.guice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.Key;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.spi.ProvisionListener;
import com.google.inject.util.Types;

import dev.crystal.plugins.api.RoleInterface;
import dev.crystal.plugins.runtime.PluginService;

/**
 * Makes plugins injectable in the host application's own Guice injector.
 *
 * <pre>{@code
 * Injector injector = Guice.createInjector(new AppModule(), PluginsModule.of(plugins));
 *
 * class GameBrowser {
 *     @Inject GameBrowser(Set<Equipment> equipment) { ... }   // no import of the framework
 * }
 * }</pre>
 *
 * <p>For every role it binds, in the application's injector:
 * <ul>
 *   <li>{@code Set<Role>}: the live view ({@link PluginService#roles}), which follows plugins as they come and go;</li>
 *   <li>{@code Role}: the preferred implementation ({@link PluginService#preferred}) when the object is built, and
 *       {@code Provider<Role>} (Guice derives it) resolving on every {@code get()}.</li>
 * </ul>
 * A provision listener records which objects of the application's graph received a role as a fixed reference,
 * so {@link PluginService#uninstall} refuses to pull an implementation from under them ({@code Set} and
 * {@code Provider} never pin a plugin).
 *
 * <p>Roles consumed <em>before</em> the injector exists (for instance roles that are themselves Guice modules,
 * installed while the injector is built) are taken with {@link PluginService#snapshot} inside
 * {@link PluginService#building}; see there.
 */
public final class PluginsModule extends AbstractModule {

    /** Written by the build into every jar that defines roles; one interface name per line, {@code #} comments. */
    public static final String ROLES_INDEX = "META-INF/crystal/roles.idx";

    private final PluginService plugins;
    private final Set<Class<?>> roles;

    private PluginsModule(PluginService plugins, Set<Class<?>> roles) {
        this.plugins = Objects.requireNonNull(plugins);
        this.roles = Set.copyOf(roles);
    }

    /**
     * Binds every role found on the class path: the interfaces listed in {@value #ROLES_INDEX}, which the build
     * writes into every jar that defines roles (the application's APIs included). Nothing to list by hand.
     */
    public static PluginsModule of(PluginService plugins) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return new PluginsModule(plugins, discover(loader != null ? loader : PluginsModule.class.getClassLoader()));
    }

    /** Binds exactly {@code roles}: for role interfaces whose jars were not built with the build plugin. */
    public static PluginsModule of(PluginService plugins, Class<?>... roles) {
        Set<Class<?>> checked = new LinkedHashSet<>();
        for (Class<?> role : roles) {
            if (!role.isInterface() || !role.isAnnotationPresent(RoleInterface.class)) {
                throw new IllegalArgumentException(role.getName() + " is not an interface annotated with @RoleInterface");
            }
            checked.add(role);
        }
        return new PluginsModule(plugins, checked);
    }

    /** The roles this module binds. */
    public Set<Class<?>> roles() {
        return roles;
    }

    @Override
    protected void configure() {
        Set<Key<?>> own = new HashSet<>();
        for (Class<?> role : roles) {
            bindRole(role, own);
        }
        // Every object the application's injector builds is built inside PluginService.building, so the roles it
        // receives as fixed references are recorded as held by it. Not the role bindings themselves: an
        // implementation must not be recorded as holding itself.
        bindListener(new AbstractMatcher<Binding<?>>() {
            @Override
            public boolean matches(Binding<?> binding) {
                return !own.contains(binding.getKey());
            }
        }, new ProvisionListener() {
            @Override
            public <T> void onProvision(ProvisionInvocation<T> provision) {
                plugins.building(provision::provision);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void bindRole(Class<T> role, Set<Key<?>> own) {
        Key<Set<T>> set = (Key<Set<T>>) Key.get(Types.setOf(role));
        bind(set).toProvider(() -> plugins.roles(role));
        bind(role).toProvider(() -> plugins.preferred(role));
        own.add(set);
        own.add(Key.get(role));
    }

    private static Set<Class<?>> discover(ClassLoader loader) {
        Set<Class<?>> found = new LinkedHashSet<>();
        try {
            Enumeration<URL> indexes = loader.getResources(ROLES_INDEX);
            while (indexes.hasMoreElements()) {
                for (String name : read(indexes.nextElement())) {
                    Class<?> type = Class.forName(name, false, loader);
                    if (type.isInterface() && type.isAnnotationPresent(RoleInterface.class)) {
                        found.add(type);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + ROLES_INDEX, e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(ROLES_INDEX + " names a role that is not on the class path", e);
        }
        return found;
    }

    private static List<String> read(URL index) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
            return reader.lines().map(line -> line.replaceFirst("#.*", "").strip()).filter(line -> !line.isEmpty())
                    .toList();
        }
    }
}
