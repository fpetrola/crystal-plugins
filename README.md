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

## How it looks in a real application

The examples below come from [OOZX](https://github.com/fpetrola/oozx), a ZX Spectrum emulator built on Crystal.
Its machines, chips, file formats, windows, toolbar buttons and even its look and feels are plugins, some
sixty of them. The emulator's own code never names one.

**1. The application says what can be plugged in**, with ordinary interfaces:

```java
@RoleInterface
public interface DeskEquipment {          // a window on the emulator's desktop
    String name();
    JInternalFrame open();
}

@RoleInterface
public interface SnapshotFile {           // a snapshot format
    boolean reads(File file);
    SpectrumState load(File file);
}
```

**2. A plugin is a class that implements one.** Here is a complete plugin, the README viewer, with no
annotation and no registration:

```java
public class ReadmeEquipment implements DeskEquipment {
    public String name() { return "README"; }
    public JInternalFrame open() {
        JInternalFrame frame = new JInternalFrame("README - OOZX", true, true, true, true);
        frame.add(new JScrollPane(new JEditorPane("text/html", readmeAsHtml())));
        return frame;
    }
}
```

Its `pom.xml` names the plugin, `<name>README</name>`, and that is all. The build plugin is declared once,
in the parent POM of all the plugins:

```xml
<plugin>
  <groupId>dev.crystal.plugins</groupId>
  <artifactId>framework-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <extensions>true</extensions>
</plugin>
```

**3. The application asks for what it needs, and gets whatever is installed.** This is how the emulator
builds its menus and opens files:

```java
@Inject
WhatIsPluggedIn(Set<DeskEquipment> windows, Set<SnapshotFile> formats, Set<Equipment> equipment, ...)

for (DeskEquipment window : windows) {
    JMenuItem item = new JMenuItem(window.name());
    item.addActionListener(e -> desktop.add(window.open()));
    menu.add(item);
}

SnapshotFile format = formats.stream().filter(f -> f.reads(file)).findFirst().orElse(null);
```

The sets are live: install a plugin from the plugin window and the menu has one more entry, remove it and
the entry is gone. Nothing in this code knows that a plugin exists.

**4. A plugin can say what it handles, so the application can find it before it is installed.** A snapshot
format declares its file extension:

```java
@Answers("sp")
public class SnapshotSP implements SnapshotFile { ... }
```

When the user drops a file nothing installed can open, the emulator asks the catalog which plugin to
install, without downloading any of them:

```java
if (plugins.answering(role, "sp").isEmpty()) {
    offer(plugins.availableAnswering(role, "sp"));
}
```

**5. Plugins depend on plugins without the application noticing.** The 128K Spectrum is a plugin that uses
the AY sound chip, which is another plugin. The build sees the dependency in the bytecode and records it.
Installing the 128K brings the chip, and removing the chip takes the 128K with it. The emulator only asks
for machines:

```java
@Answers({"SPECTRUM128K", "SPECTRUMPLUS2"})
public class Spectrum128Devices extends AbstractModule implements Extension {
    protected void configure() {
        Multibinder.newSetBinder(binder(), Spectrum.class).addBinding().to(Spec128.class);
    }
}
```

**6. The setup is done once**, in one place:

```java
PluginService plugins = PluginService.builder()
        .source(new WhereAPluginComesFrom())             // a local folder plus a release board online
        .defaults(PluginSources.bundled())               // the plugins shipped inside the emulator's jar
        .cacheDirectory(home.resolve("plugin-cache"))
        .build();
plugins.start();

Injector injector = Guice.createInjector(PluginsModule.of(plugins));   // every role, injectable
```

The emulator's jar carries its default plugins because of one line in its POM,
`<crystal.bundleGroupId>com.fpetrola</crystal.bundleGroupId>`, and its plugin manager window is
`new PluginsPanel(plugins)`.

## What the framework takes care of

- **Isolation.** Each plugin gets its own classloader and injector, created and dropped together. Two
  plugins can use different versions of the same library.
- **Dependencies between plugins.** If `device-spectrum128` uses classes from `device-ay`, the build notices it
  in the bytecode and records `device-ay@<version>`. Installing one brings the other, and removing the base
  removes what depends on it, dependents first.
- **Plugins that extend plugins.** A plugin can declare its own `@RoleInterface`, and sub-plugins implement it
  exactly like the application's roles.
- **Third-party libraries.** A plugin that needs a library the application does not have carries it inside its
  jar (`lib/`), automatically. What the application already has is never loaded twice.
- **Choosing between implementations.** `plugins.preferred(SnapshotFile.class)` gives one; `@Replaces("device-snapshots")`
  lets a plugin hide another one while it is installed. The rule can be replaced with a `ConflictResolver`.
- **Safe removal.** A plugin still referenced by the application is not pulled away: it is removed on the
  next start instead. `beforeUnload(...)` lets the application let go of anything it took from a plugin, such
  as a look and feel, before its classes disappear.
- **No surprises on start.** Starting only loads what is installed, from a local content-addressed cache, and
  never needs the network. Installing and updating are explicit calls: `install(id)`, `uninstall(id)`,
  `checkForUpdates()`.
- **Your application's own implementations count too.** Implementations shipped in the application itself
  appear alongside the plugins', so "every snapshot format" means every one.
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
