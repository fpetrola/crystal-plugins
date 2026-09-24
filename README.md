# Crystal Plugins

A plugin framework for Java 21 where **a plugin is just a class that implements an interface**.

You write the interface, someone writes a class that implements it, and the framework does the rest:
it builds the plugin jar, finds what it depends on, installs it, loads it in isolation, wires it, and
hands it to your application. No plugin descriptor, no base class, no registry to call, no framework
types in the plugin's code. Under the hood it uses [PF4J](https://pf4j.org) for loading and
[Guice](https://github.com/google/guice) for injection, but neither shows up in your code or in the
plugins'.

## Why

Most plugin systems leak into both sides. Plugin authors extend framework classes and maintain descriptors;
the application looks plugins up, tracks their lifecycle, and has to be careful not to keep a reference
after a plugin goes away. Crystal keeps that out of everybody's code:

- **The plugin author** implements an interface and adds one Maven plugin. The build works out the rest:
  which roles it provides and which other plugins it needs, pinned to the exact versions it was compiled
  against.
- **The application** asks for "all the exporters" or "the preferred exporter" and gets plain Java objects,
  or live `Set`s that follow plugins as they are installed and removed. It never deals with classloaders,
  descriptors or start/stop order.
- **The user** installs, updates and removes plugins while the application runs, from a catalog you
  control. The application can also ship its default plugins inside its own jar.

## A complete example

**1. The application declares a role**, an ordinary interface in its API jar:

```java
@RoleInterface
public interface ReportExporter {
    String format();
    String export(List<String> header, List<List<String>> rows);
}
```

**2. A plugin implements it.** This is the whole plugin:

```java
public class CsvExporter implements ReportExporter {
    public String format() { return "csv"; }
    public String export(List<String> header, List<List<String>> rows) { ... }
}
```

Its `pom.xml` depends on the API jar (`provided`) and adds the build plugin, which can also be declared once
in a parent POM:

```xml
<plugin>
  <groupId>dev.crystal.plugins</groupId>
  <artifactId>framework-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <extensions>true</extensions>
</plugin>
```

`mvn package` produces a regular jar that is also a plugin. There is nothing else to configure.

**3. The application uses the role** with no reference to plugins at all:

```java
public class ReportScreen {
    @Inject Set<ReportExporter> exporters;          // every exporter installed now, updated live

    void export(Report report, String format) {
        exporters.stream().filter(e -> e.format().equals(format)).findFirst()
                 .ifPresent(e -> save(e.export(report.header(), report.rows())));
    }
}
```

**4. Something starts the plugins, once:**

```java
try (PluginService plugins = PluginService.builder()
        .source(myCatalog)                        // where plugins come from: a folder, a server, GitHub...
        .defaults(PluginSources.bundled())        // plugins shipped inside the application jar
        .cacheDirectory(appData.resolve("plugins"))
        .build()) {
    plugins.start();                              // loads what is installed, from the local cache
    ReportScreen screen = plugins.create(ReportScreen.class);
    ...
}
```

Install a PDF exporter while the application runs, and `screen.exporters` has one more element; remove it,
and it has one less. Neither the screen nor the CSV plugin changes.

If the application already has its own Guice injector, `PluginsModule.of(plugins)` (in `framework-guice`)
makes every role injectable there too, discovered automatically:

```java
Injector injector = Guice.createInjector(new AppModule(), PluginsModule.of(plugins));
```

## What the framework takes care of

- **Isolation.** Each plugin gets its own classloader and injector, created and dropped together. Two
  plugins can use different versions of the same library.
- **Dependencies between plugins.** If `csv-semicolon` uses classes from `csv-exporter`, the build notices it
  in the bytecode and records `csv-exporter@1.0.0`. Installing one brings the other, and removing the base
  removes what depends on it, dependents first.
- **Plugins that extend plugins.** A plugin can declare its own `@RoleInterface`, and sub-plugins implement it
  exactly like the application's roles.
- **Third-party libraries.** A plugin that needs a library the application does not have carries it inside its
  jar (`lib/`), automatically. What the application already has is never loaded twice.
- **Choosing between implementations.** `plugins.preferred(ReportExporter.class)` gives one; `@Replaces("csv")`
  lets a plugin hide another one while it is installed. The rule can be replaced with a `ConflictResolver`.
- **Safe removal.** A plugin still referenced by the application is not pulled away: it is removed on the
  next start instead. `beforeUnload(...)` lets the application let go of anything it took from a plugin, such
  as a look and feel, before its classes disappear.
- **No surprises on start.** Starting only loads what is installed, from a local content-addressed cache, and
  never needs the network. Installing and updating are explicit calls: `install(id)`, `uninstall(id)`,
  `checkForUpdates()`.
- **Your application's own implementations count too.** Implementations shipped in the application itself
  appear alongside the plugins', so "every exporter" means every exporter.
- **Answering questions without loading anything.** A plugin can declare the keys it handles, such as
  `@Answers({"tap", "tzx"})` for file extensions. `plugins.availableAnswering(role, "tzx")` then tells which
  plugin in the catalog to install, without downloading it.

## A plugin window for Swing applications

`framework-swing` adds a ready-made settings panel. Put it in any dialog or tab:

```java
dialog.add(new PluginsPanel(plugins));
```

It shows the installed plugins next to what the catalog offers, with buttons to move them from one list to the
other, several at a time. Two more tabs show the role tree (who implements what, and what is hidden by a
replacement) and the dependency tree ("requires" and "used by"). Plugins are grouped by id prefix, named by
their `<name>`, and marked by where they come from (bundled, folder, catalog). The panel refreshes itself
whenever plugins change, from any source. Its icons are OpenMoji (CC BY-SA 4.0) SVGs, rendered by a
relocated copy of JSVG that never clashes with the application's own.

## Shipping plugins with the application

Set one property in the application's POM:

```xml
<crystal.bundleGroupId>com.example</crystal.bundleGroupId>
```

The build copies the plugins of that group into the application jar, and `PluginSources.bundled()` installs
them on first start, without the network. The user can still remove them, and a newer application build
updates them.

## Testing a plugin on its own

With `framework-test-harness` in test scope, a plugin runs in a real `PluginService`, in its own classloader,
without the application:

```java
try (PluginHarness harness = PluginHarness.builder().expose(Clock.class, () -> 0L).start()) {
    Peripheral beeper = harness.one(Peripheral.class);
    assertEquals(0xBF, beeper.in(0xFE));
}
```

## Modules

| Artifact | Used by | What it is |
|---|---|---|
| `framework-api` | plugins and application | `@RoleInterface`, `@Replaces`, `@Needs`, `@Answers`, `HasLifecycle`, `PluginSource`, `ConflictResolver` |
| `framework-maven-plugin` | plugin and application builds | Builds plugin jars, bundles default plugins, checks single jars |
| `framework-runtime` | application | `PluginService` |
| `framework-guice` | application (optional) | `PluginsModule` for an existing Guice injector |
| `framework-swing` | application (optional) | `PluginsPanel` |
| `framework-test-harness` | plugin tests | `PluginHarness` |

`examples/` is a separate build that uses the framework the way a third party would: two unrelated
applications (an emulator and a reports app), their APIs, and plugins for each, including a sub-plugin.

```bash
mvn install                          # the framework and its tests
(cd examples && mvn clean install)   # the examples, end to end
```

## More

- [docs/metadata-format.md](docs/metadata-format.md): what the build writes into a plugin jar; a public contract
  other build tools can produce.
- [docs/design-notes.es.md](docs/design-notes.es.md): design decisions and their reasons, milestone by
  milestone (Spanish).
