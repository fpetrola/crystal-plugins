# Crystal Plugins

Framework de plugins genérico para Java 21 sobre **PF4J** (carga y aislamiento) y **Guice** (inyección),
con convención sobre configuración. El autor de un plugin escribe **una clase que implementa una
interfaz-rol**. PF4J y Guice no aparecen ni en la app anfitriona ni en el código del plugin.

```java
// En la API de la app (o de un plugin):
@RoleInterface
public interface ReportExporter { String format(); String export(List<String> h, List<List<String>> rows); }

// Un plugin entero:
public class CsvExporter implements ReportExporter { ... }

// La app anfitriona:
try (PluginService plugins = PluginService.builder()
        .source(miCatalogo)                          // PluginSource de la app (red, disco, lo que sea)
        .cacheDirectory(datosDeLaApp.resolve("plugins"))
        .build()) {
    plugins.start();                                 // lo instalado, desde la caché local: nunca usa la red
    ReportScreen screen = plugins.create(ReportScreen.class);   // @Inject Set<ReportExporter>
    plugins.install("pdf-exporter");                 // el usuario elige del catálogo: entra ya, sin reiniciar
    UpdateReport report = plugins.checkForUpdates(); // actualiza lo instalado; report.available() = lo demás
}
```

## Módulos

| Artefacto                                         | Para quién          | Contenido                                                                     |
|---------------------------------------------------|---------------------|-------------------------------------------------------------------------------|
| `framework-api`                                   | autores + app       | `@RoleInterface`, `@Replaces`, `@Needs`, `HasLifecycle`, `PluginSource`, `ConflictResolver`. Depende solo de `jakarta.inject-api`. |
| `framework-build-plugin/framework-build-processor` | build del autor     | Annotation processor de javac, sin dependencias.                              |
| `framework-build-plugin/framework-build-core`     | build del autor     | Análisis de bytecode (ASM), dependencias ancladas y escritura del jar. No depende de ninguna herramienta de build. |
| `framework-build-plugin/framework-maven-plugin`   | build del autor     | Adaptador de Maven: conecta el processor y le pasa el proyecto a `framework-build-core`. |
| `framework-runtime`                               | app                 | `PluginService` + integración interna PF4J/Guice.                             |
| `framework-guice` (opcional)                      | app con Guice propio | `PluginsModule`: los roles se inyectan en el injector de la app.             |
| `app-api` (lo escribe cada app)                   | autores + app       | Interfaces-rol de la app; depende de `framework-api`.                         |
| `framework-test-harness`                          | autores (tests)     | `PluginHarness`: el plugin, solo, en un `PluginService` real y sin la app.     |
| `framework-swing` (opcional)                      | app Swing           | `PluginsPanel`: panel de referencia que pinta el modelo de plugins.           |

`examples/` es un build aparte que hace de terceros: dos apps sin relación entre sí (un emulador con
`Peripheral`, una app de reportes con `ReportExporter`), sus APIs y plugins para cada una, armados con
el plugin de Maven real. Incluye un sub-plugin (`plugin-csv-semicolon` implementa un rol que define
`plugin-csv-exporter`) cuya dependencia genera el build.

```bash
mvn install                       # framework (104 tests)
(cd examples && mvn clean install) # uso de punta a punta + prueba de genericidad
```

## Autor de un plugin: toda la configuración

```xml
<dependencies>
  <dependency>                       <!-- entorno reducido: solo el API jar de la app -->
    <groupId>com.example</groupId><artifactId>reports-api</artifactId>
    <scope>provided</scope>
  </dependency>
</dependencies>
<build><plugins>
  <plugin>
    <groupId>dev.crystal.plugins</groupId><artifactId>framework-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <extensions>true</extensions>
  </plugin>
</plugins></build>
```

Se puede declarar una sola vez en un POM padre: los módulos sin implementaciones de roles (APIs, la
app) quedan intactos.

Y para probarlo sin la app, con `framework-test-harness` en scope `test`:

```java
try (PluginHarness harness = PluginHarness.builder().expose(Clock.class, () -> 0L).start()) {
    Peripheral beeper = harness.one(Peripheral.class);     // el plugin, en su propio classloader
    assertEquals(0xBF, beeper.in(0xFE));
}
```

El formato de lo que se genera es un contrato público: [docs/metadata-format.md](docs/metadata-format.md).

---

## Decisiones de diseño

### Hito 1: `framework-api`

- **Implementar un rol *es* la declaración.** `@RoleInterface` va en la *interfaz*, nunca en la
  implementación, así la clase del autor no lleva anotaciones. Se busca a través de los
  super-interfaces, de modo que un sub-rol (`StreamingExporter extends Exporter`) no necesita repetirla.
- **`@RoleInterface` no tiene valor, a propósito.** La interfaz *es* la identidad del rol: sirve para
  agrupar, filtrar y mostrar. Un nombre o una categoría paralela ("device", "tool", "format"...) sería
  una tabla de sinónimos a mantener sincronizada con las interfaces. Esto se confirmó en una adopción
  real: al pasar a `@RoleInterface`, el campo de categoría y su tabla se borraron sin perder nada.
- **Retención `RUNTIME`** en todas las anotaciones: el processor encuentra roles en jars ya compilados
  (nunca ve su código fuente) y el runtime los vuelve a verificar por reflexión.
- **`jakarta.inject` como vocabulario de inyección.** Es un estándar (JSR-330), no Guice. Autores y app
  escriben `@Inject`, y Guice sigue siendo un detalle de implementación que se podría reemplazar.
- **`HasLifecycle` con métodos `default`.** Es opt-in y reemplaza `extends org.pf4j.Plugin`.
- **`PluginSource` = listar + abrir bytes, con sha256 obligatorio.** Todo lo de red queda del lado de la
  app. El hash es a la vez verificación de integridad y clave de caché (hito 4).
- `@Replaces` acepta un id de plugin o `plugin-id:Clase`. `ConflictResolver` se definió recién en el
  hito 6, junto con su uso, para no fijar una forma a ciegas.

### Hito 2: plugin de build

- **El processor no depende de nada** y reconoce las anotaciones **por nombre**. No fija una versión de
  `framework-api` en el build del autor y sirve igual para Maven, Gradle (se declara como `aggregating`
  en `META-INF/gradle/incremental.annotation.processors`) o javac a mano.
- **No se genera `@Extension`: se genera su efecto.** Lo que PF4J necesita del `@Extension` es
  `META-INF/extensions.idx`, que es lo que escribe el processor. El runtime descubre las clases con el
  finder estándar de PF4J, sin reimplementarlo.
- **Qué es una extensión.** Una clase pública, concreta, de nivel superior o anidada `static`, que
  implementa un rol y que el framework puede construir (constructor público sin argumentos o uno con
  `@Inject`). Una clase que implementa un rol sin cumplir eso (un decorador, una variante que se
  construye con parámetros, un helper interno) es una clase común que construye su dueño: no va al índice
  y, si es pública, javac muestra una nota por si faltó un `@Inject`. Solo es error de compilación la
  intención explícita que no se puede cumplir: un constructor `@Inject` en una clase que no puede ser
  extensión, dos constructores `@Inject`, o `@Replaces`/`@Needs` en algo que no es extensión.
- **`META-INF/services` solo lista lo que `ServiceLoader` puede instanciar** (constructor público sin
  argumentos). Una extensión que solo se construye con `@Inject` va a `extensions.idx` pero no ahí, porque
  si no `ServiceLoader` lanza `ServiceConfigurationError` a todos los que consumen el rol.
- **Convive con el processor propio de PF4J.** Cuando `pf4j` está en el classpath de compilación (una app
  host o sus tests, a través de `framework-runtime`), javac corre también el processor `@Extension` de
  PF4J, que siempre crea `META-INF/extensions.idx`, y un archivo solo lo puede crear un processor por
  compilación. En ese caso el nuestro no falla: deja un aviso y genera igual los services y la metadata.
  Eso alcanza para código que no se empaqueta como plugin. Si le pasa a un plugin, el build falla con el
  motivo, porque ese índice incompleto dejaría al plugin sin extensiones en runtime.
- **Metadata en dos mitades.** El processor escribe lo que se deriva del fuente; el Maven mojo agrega lo
  que solo conoce el build (id, versión SemVer, `apiVersion`, `roleApis`) y escribe el manifiesto. Esa
  división hace que otro build system solo tenga que reemplazar la segunda mitad.
- **Cero configuración con `<extensions>true</extensions>`.** Un `AbstractMavenLifecycleParticipant`:
  1. agrega el processor como dependencia `provided` (no llega a los consumidores);
  2. activa `-proc:full`, porque desde JDK 23 javac ya no ejecuta los processors que encuentra en el
     classpath. Si el autor usa `annotationProcessorPaths`, lo agrega ahí;
  3. engancha `package-plugin` en `package`, después de `jar:jar`.

  Lo que el autor configure explícitamente siempre tiene prioridad.
- **El mojo reescribe el jar ya construido** en vez de tocar la configuración de `maven-jar-plugin`: pone
  el manifiesto primero (como exige `JarInputStream`) y conserva los timestamps para que los builds
  reproducibles sigan siéndolo.
- El participant se registra con `META-INF/plexus/components.xml` y **no** con un índice Sisu. Durante
  este trabajo apareció que el Sisu que trae Maven 3.8.7 de Debian no puede leer class files de Java 17+,
  y en ese caso ignora el participant **sin avisar**. El descriptor Plexus carga la clase por nombre.

### Hito 3: `PluginService`

- **Única clase-frontera.** La app ve `PluginService`, `PluginSources`, `PluginInfo` y `PluginException`.
  No aparece ningún tipo de PF4J o Guice en esa API. Todo lo demás está en `...runtime.internal`.
- **PF4J es dueño del ciclo de vida y del orden; Guice reacciona.** `RolePlugin` (el `Plugin-Class`
  genérico) no tiene lógica propia: cuando PF4J lo arranca, `PluginScopes` crea el injector del plugin,
  instancia las implementaciones, llama a `onStart()` y las publica; cuando lo detiene, hace lo inverso.
  Así el orden por dependencias y el "dependientes primero" al detener siguen siendo los de PF4J. La
  excepción es el unload transitivo, que PF4J ordena mal (ver hito 7).
- **Aislamiento en espejo.** Un classloader por plugin (PF4J) y un injector por plugin (Guice), que se crean
  y descartan juntos. Cada injector usa `requireExplicitBindings()`, y un `InjectionPlanner` recorre los
  puntos de inyección y enlaza explícitamente todo lo que haga falta. Si algo se escapa, falla con un
  error en vez de enlazarse a escondidas. Hasta el hito 6 eran child-injectors de un root; el hito 7
  mostró que eso fuga y los convirtió en injectors independientes (ver ahí).
- **`Set<Rol>` inyectado es una vista viva.** No es una copia: siempre refleja los plugins activos.
  Un objeto de la app creado antes de `start()` ve los plugins cuando arrancan, y deja de verlos
  cuando se detienen. Internamente es copy-on-write: la iteración no toma locks y nunca ve un plugin a
  medio publicar.
- **`@Inject Rol` (uno solo)** se resuelve contra el único proveedor activo, con errores claros si hay
  cero o más de uno. El hito 6 reemplaza la regla "más de uno = error" por el `ConflictResolver`.
- **Servicios del host:** `builder().expose(Clock.class, clock)` los hace inyectables en plugins y en
  objetos de la app.
- **Sub-plugins:** un plugin puede declarar sus propios `@RoleInterface` e inyectar `Set<SuRol>`; otro
  plugin que dependa de él los implementa (ver `pluginsCanDefineRolesForSubPlugins`).
- **Un plugin roto no tira abajo a los demás.** Una dependencia sin resolver hace que se ignore ese
  plugin (`IGNORE_PLUGIN_AND_CONTINUE`); una excepción en `onStart()` lo deja en `FAILED` (con la causa en
  `PluginInfo`) y detiene lo que ya había arrancado.
- **Sin archivos de configuración:** el `enabled.txt`/`disabled.txt` de PF4J se reemplaza por estado en
  memoria. El modo es siempre deployment y solo se aceptan jars.
- **Camino manual:** `Plugin-Class` ausente (PF4J lo completa con `org.pf4j.Plugin`) se trata igual que el
  genérico; un `Plugin-Class` propio sigue funcionando como plugin PF4J clásico.
- **De dónde carga PF4J:** siempre de la caché local (ver hito 4).

### Hito 4: caché local, `PluginSource` y carga custom

- **Caché direccionada por contenido.** Contiene `<cache>/objects/<sha256>.jar` y `<cache>/installed.tsv`
  (el conjunto instalado). Un jar nunca se sobrescribe: sobrescribir un jar que un classloader tiene
  abierto puede romper la carga de clases de la JVM en curso. Una versión nueva es un archivo nuevo, así
  que un proceso puede actualizar mientras otro sigue usando los jars anteriores. El layout es interno y
  no forma parte del contrato; el contrato público es el jar.
- **La fuente es un catálogo; lo instalado es otra cosa.** `start()` lee el conjunto instalado y carga
  desde la caché, y **nunca** instala nada por su cuenta, ni siquiera con la caché vacía. Lo instalado
  cambia solo con operaciones explícitas, que son las únicas que consultan la fuente:
  - `install(id)` agrega un plugin, con las dependencias que le falten;
  - `uninstall(id)` lo saca;
  - `checkForUpdates()` actualiza lo que ya está instalado y además informa qué otros plugins hay
    disponibles y qué instalado dejó de ofrecerse;
  - `installAll()` deja instalado exactamente lo que se ofrece, para carpetas drop-in.

  La única excepción es restaurar un jar instalado que falta en la caché, y siempre con la misma
  versión. Una app sin red, o sin fuente configurada, arranca igual.

  (Hasta la versión `guice-1`, el primer arranque instalaba todo lo que ofrecía la fuente. Con un
  catálogo remoto de 52 plugins eso significaba bajarlos todos, así que se separó.)
- **Cada jar se descarga una sola vez y se verifica.** Si su hash ya está en la caché, no se descarga. El
  sha256 se calcula durante la copia, y además se verifica que el manifiesto diga el id y la versión que
  anunció la fuente. Solo entonces el archivo se hace visible, con un move atómico.
- **Las actualizaciones son todo o nada.** `checkForUpdates()` descarga y verifica todo antes de escribir
  el nuevo conjunto instalado (escritura atómica). Si algo falla, queda el conjunto anterior. Los
  plugins en ejecución no se tocan y el conjunto nuevo rige desde el próximo arranque: aplicarlo en
  caliente requiere el unload transitivo del hito 7. Si se llama antes de `start()`, actualiza lo que se
  va a cargar.
- **La fuente elige las versiones, no el framework.** Una fuente ofrece a lo sumo una versión por plugin
  (si ofrece dos, es un error explícito), y esa es la que se instala o a la que se actualiza. No hay
  "la última" implícita.
- **PF4J se extiende solo donde hace falta.** `CrystalPluginManager` (que extiende `DefaultPluginManager`)
  usa un `PluginRepository` que devuelve exactamente los jars del conjunto instalado, en lugar de escanear
  un directorio. El `PluginLoader` sigue siendo el `JarPluginLoader` estándar: ya carga desde disco local
  y reemplazarlo no aportaba nada.
- **El arranque es barato.** No se vuelven a hashear los jars: se verifican al entrar a la caché y después
  son inmutables y se nombran por su hash. Arrancar es leer un archivo chico y abrir los jars.
- **La limpieza es acotada.** Después de cada actualización se borran los jars que no están en el conjunto
  nuevo, ni en el anterior (otros procesos que comparten la caché pueden estar usándolo), ni cargados en
  este proceso.
- **Por defecto la caché es temporal.** Sin `cacheDirectory(...)`, cada arranque empieza sin nada
  instalado, lo que sirve para tests (`installAll()` y después `start()`). Una app real pasa su propio
  directorio: dónde guardar datos lo decide la app, no el framework.

### Hito 5: dependencias por bytecode, ancladas a la versión compilada

- **De dónde sale una dependencia.** `framework-build-core` recorre con ASM todas las clases compiladas del
  plugin y junta cada tipo que mencionan: descriptores, firmas genéricas (`Set<Dialect>` cuenta),
  anotaciones y cuerpos de métodos. Cada tipo se clasifica según quién lo provee: el propio plugin, el
  JDK (paquetes de los módulos del sistema), **API compartida** (entrada `provided` que no es un plugin:
  se descarta), **otro plugin** (dependencia) o **librería privada** (aviso: un plugin jar no lleva
  librerías).
- **Un plugin se reconoce por sus archivos estándar**, igual que en el runtime: el jar tiene `Plugin-Id`
  en el manifiesto. Da igual si lo construyó este plugin de build, otro o alguien a mano.
- **Se ancla la versión con que se compiló, nunca "la última".** La dependencia se escribe como
  `id@<Plugin-Version del jar compilado>`, que es un pin exacto. `@Needs` también se ancla si el plugin
  está en el classpath. `plugin-metadata.json` guarda además qué tipos generaron cada dependencia, así se
  puede responder "¿por qué dependo de esto?".
- **El build se valida a sí mismo.** Con el mismo ASM, el core busca las extensiones reales (aplicando las
  reglas del processor) y las compara con las que indexó el processor. Si no coinciden, la metadata está
  vieja (javac no volvió a correr el processor) y el build falla con un mensaje que dice qué hacer, en
  vez de producir un jar al que le faltan extensiones o que declara otras que ya no existen. Esto
  resuelve el problema del primer build después de agregar el plugin.
- **El core no depende de Maven.** `PluginPackager` recibe clases, jar, coordenadas y classpath (cada
  entrada marcada como `provided` o no). El mojo de Maven quedó como un adaptador chico que solo traduce el
  proyecto de Maven; un plugin de Gradle haría lo mismo.
- **Del lado del runtime aparecieron dos problemas de PF4J:**
  - Sus expresiones de versión (java-semver 0.10) no pueden expresar una pre-release: `1.0.0-SNAPSHOT` no
    se parsea. Un `VersionManager` propio compara los pins exactos, pre-releases incluidas, y deja los
    rangos a PF4J.
  - Cuando un plugin pide otra versión de una dependencia, la recuperación de PF4J
    (`IGNORE_PLUGIN_AND_CONTINUE`) descarga **la dependencia**, no al plugin que la pidió, y lo hace sin
    avisar. Así, un pin viejo en un plugin se llevaría puestos a un plugin sano y a todos sus otros
    dependientes. Se reemplazó ese paso (`resolveDependencies()`): se rechaza al plugin cuyo requisito no
    se cumple, y en cascada a los que dependen de él. El motivo queda en `plugins()` (por ejemplo
    `requires csv@1.0.0 but 2.0.0 is installed`). El grafo, el orden y los chequeos siguen siendo de PF4J.

### Hito 6: `ConflictResolver` y `@Replaces` reversible

- **Qué es un conflicto.** Muchos roles son naturalmente múltiples (todos los `Peripheral` conviven), así
  que el resolver no elige "uno ganador": decide qué implementaciones son *visibles* y en qué orden de
  preferencia. Las vistas `Set<Rol>` muestran ese resultado en ese orden, y un `@Inject Rol` recibe la
  primera.
- **El resolver estándar** hace lo que pide el enunciado:
  - **`@Replaces` gana.** Una implementación reemplazada por otra activa del mismo rol se oculta, solo
    para los roles que comparten: si el plugin reemplazado aporta además un `Peripheral`, ese sigue
    visible. Las cadenas funcionan (C reemplaza a B y B a A: queda C). Los reemplazos que forman un ciclo
    se ignoran, en vez de ocultar a todos.
  - **Después, la mayor versión** (precedencia SemVer, con las pre-releases antes que la release). A
    igual versión desempata el id, así el orden es siempre el mismo.
- **Reversible de verdad.** Lo reemplazado no se detiene: sigue activo y oculto. El registro recalcula
  con cada cambio, así que cuando lo que lo reemplazaba deja de estar activo vuelve a aparecer, incluso
  en las vistas que ya se habían entregado. Un reemplazo que llega con `install()` oculta al original en
  el momento. Uno que falla al arrancar no reemplaza nada.
- **Sin costo al leer.** El resultado se calcula una vez por rol y por snapshot (copy-on-write) y se
  descarta cuando algo cambia. Iterar un `Set` en un loop caliente no llama al resolver.
- **Uno fijo o uno que sigue los cambios:** `@Inject Rol` se resuelve una vez, al crear el objeto;
  `@Inject Provider<Rol>` (JSR-330 estándar) se resuelve en cada `get()`, así que sigue los reemplazos y
  las versiones que llegan.
- **La política es de la app.** Se inyecta con `builder().conflictResolver(...)`. Es una interfaz
  funcional sobre `RoleImplementation` (instancia, plugin, versión, `@Replaces`), así que se puede
  escribir como lambda y componer con `ConflictResolver.standard()` para conservar el resto del
  comportamiento. El registro verifica que el resolver solo devuelva implementaciones que recibió.
- **De dónde sale `@Replaces`:** el runtime lo lee de la clase, que tiene retención `RUNTIME`. Así vale
  también para el camino manual, y el campo `replaces` del JSON queda como informativo.

### Hito 7: sub-plugins con scopes anidados y unload transitivo

- **`uninstall(pluginId)`** saca el plugin y todos los que dependen de él: los detiene y descarga, tira
  juntos el classloader y el injector de cada uno, y los quita del conjunto instalado. Sus
  implementaciones salen de todas las vistas en el momento, y lo que habían reemplazado vuelve a verse:
  la reversibilidad del hito 6 ahora es observable también a nivel servicio.
- **Sub-plugins primero, de verdad.** El unload transitivo de PF4J procesa a un dependiente antes que a
  los dependientes de ese dependiente: en A ← B ← C descarga B mientras C todavía corre sobre él. El
  orden se calcula aparte (post-orden sobre el grafo de dependientes, hojas primero) y se descarga plugin
  por plugin en ese orden. Se probó con una cadena de tres, observando el orden de los `onStop()`.
- **"Limpio" es que nadie de afuera tenga una referencia fija.** El registro anota qué implementaciones
  se entregaron como referencia **fija** (un `@Inject Rol` simple) mientras se construía algo: los
  singletons de un plugin, o un objeto hecho con `create()` (este último con una referencia débil,
  mientras siga vivo). Si algo que no se está descargando tiene una de esas referencias, `uninstall` no
  hace nada y el mensaje dice quién la tiene. Las vistas `Set<Rol>` y los `Provider<Rol>` nunca atan a un
  plugin, así que son la forma de inyectar lo que puede irse.
- **Retenido es el caso normal en algunas apps, así que la API lo hace manejable.** Cuando los roles son
  módulos de Guice que se instalan en un injector que vive lo mismo que la app, sus plugins quedan
  retenidos mientras ese injector exista (en una adopción real, 37 de 52). Para eso hay tres cosas:
  - `heldBy(id)` dice quién impide desinstalarlo ahora (vacío = se puede), así una lista se pinta sin
    intentar y atrapar excepciones;
  - `uninstallOnNextStart(id)` lo saca del conjunto instalado junto con sus dependientes, sin descargarlo:
    sigue corriendo hasta cerrar el servicio, `pendingRemovals()` dice qué se va a ir, y un `install(id)`
    lo cancela. Es estado del propio conjunto instalado (cargado pero no instalado), no un archivo aparte;
  - `uninstall` rechaza con un `PluginRetainedException` que trae los plugins y los retenedores como datos,
    para que la app arme su propio mensaje corto.
- **Scopes anidados.** Un sub-plugin (un plugin con exactamente una dependencia requerida) ve los objetos
  que construyó su padre: el mismo singleton, no una copia. Con varios padres no hay un único scope que
  lo contenga, y en ese caso llega a los objetos de los otros plugins por roles, como todos.
- **La fuga que encontró un test, y por qué ya no hay child-injectors.** El test "desinstalar libera el
  classloader" falló. Un recorrido reflexivo del grafo de objetos dio el camino:
  `injector root → jitBindingData.bannedKeys (WeakKeySet) → Key<acme.csv.Exporter> → Class →
  PluginClassLoader`. Cuando un child-injector enlaza una clave, Guice la "prohíbe" en todos sus
  ancestros y guarda la `Key` de forma **fuerte**. Solo la limpia de a poco: después de que se recolecte el
  child, y únicamente cuando alguna operación posterior toca esa caché del padre. Por eso ahora cada
  plugin tiene un **injector independiente**:
  - los servicios del host vienen de un módulo que instala cada injector (con un provider, no con
    `toInstance`, para que Guice no les vuelva a inyectar miembros en cada plugin);
  - el anidamiento se hace **delegando**: el sub-plugin enlaza las claves que su padre ya tiene hacia el
    injector del padre, y el padre no guarda nada del sub-plugin.
  
  Con eso el classloader se recolecta apenas corre el GC, y el test lo verifica.

### Hito 8: `framework-test-harness`

- **El autor prueba su plugin solo, sin la app.** En un proyecto de plugin, que tiene su API como
  `provided` y el plugin de build, un test hace `PluginHarness.start()`, pone dobles de los servicios del
  host con `expose(...)` y usa el plugin por sus roles (`one(Rol)`, `roles(Rol)`, o `service()` para lo
  demás).
- **Fiel a producción.** El plugin corre en un `PluginService` real, en **su propio classloader** y cableado
  como lo va a cablear el runtime. Así los errores de empaquetado (una API que viajó adentro del jar, una
  dependencia que falta) aparecen en el test y no en la app.
- **Empaqueta al vuelo con el mismo `PluginPackager` del build.** `mvn test` corre antes de `package`, así
  que el jar todavía no existe: el harness arma uno desde `target/classes` con el mismo análisis de
  bytecode, las mismas dependencias ancladas y la misma verificación de metadata vieja. Con `jar(ruta)`
  prueba un jar ya construido (por ejemplo en tests de integración, después de `package`).
- **Las dependencias entran como plugins.** Los jars del classpath de test que son plugins (tienen
  `Plugin-Id`) se cargan como plugins, no como clases sueltas. Un sub-plugin se prueba con su padre sin
  configurar nada, y así lo hace `examples/plugin-csv-semicolon`.
- **Independiente del framework de tests.** Los problemas salen como `IllegalStateException`, con el
  motivo: un plugin que no arrancó, clases compiladas sin el processor, un rol con cero o varias
  implementaciones en `one()`.
- **Un límite de fidelidad.** En un test todo el classpath es visible para el plugin a través del
  classloader padre, así que una librería que en producción faltaría (no empaquetada y que el host no
  provee) en el test aparece. El build ya avisa sobre esas librerías.
- **Un ajuste que pidió el harness:** `PluginPackager` ahora encuentra `framework-api` por su contenido (la
  entrada que tiene `RoleInterface`) y no por coordenadas Maven, porque el classpath de un test no las
  tiene.

### Apps con su propio injector: `framework-guice`

Una app que arma su grafo de objetos con Guice quiere que sus clases pidan `Set<Rol>` a **su** injector,
sin `create()` ni `roles()` y sin importar nada del framework. Es lo que pide el enunciado ("la app
consume inyectando"). Para no meter Guice en la API del core, va como adaptador opcional:

```java
Injector injector = Guice.createInjector(new AppModule(), PluginsModule.of(plugins));

class GameBrowser {
    @Inject GameBrowser(Set<Equipment> equipment) { ... }   // aparecen, sin saber de dónde
}
```

- **Descubre los roles solo.** El processor escribe `META-INF/crystal/roles.idx` en todo jar que *define*
  roles, incluidas las APIs de la app. `PluginsModule.of(plugins)` lee esos índices del classpath y, si un
  jar no lo tiene, se le pasan los roles a mano.
- **En un jar único (fat jar) hay que fusionar `META-INF/crystal/roles.idx`.** Cada módulo trae el suyo y
  con el mismo nombre; `maven-shade` y `assembly` se quedan con uno solo, y los roles de los demás no se
  enlazan. El síntoma no dice la causa: Guice falla con "No implementation for Set<Rol> was bound". En
  shade:

  ```xml
  <transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
    <resource>META-INF/crystal/roles.idx</resource>
  </transformer>
  ```

  (y `ServicesResourceTransformer` para `META-INF/services`, como siempre). Los plugins que van adentro
  de la app con `bundle-plugins` no tienen este problema: viajan como jars enteros. Para que no se
  descubra en la máquina de un usuario, el goal `check-roles` (enganchado solo en `verify`, después de
  shade) revisa los jars que produjo el proyecto: si alguno tiene un `@RoleInterface` que su
  `roles.idx` no lista, el build falla con el nombre del rol. `-Dcrystal.skipRolesCheck` lo saltea (para
  roles que se pasan a mano). Ojo: `mvn package` no llega a `verify`.
- **En el IDE, el processor también tiene que estar.** IntelliJ (y otros) compilan por su cuenta, sin el
  plugin de Maven, así que no corre el processor que el plugin agrega: no se escribe `roles.idx` y Guice
  muere pidiendo `Set<Rol>`. Se arregla declarando `framework-build-processor` como dependencia
  `provided` (en el pom padre alcanza), así cualquier compilador lo encuentra por `META-INF/services`.
  Con Maven no hace falta nada de esto: si el proyecto usa `annotationProcessorPaths`, el plugin agrega el
  processor ahí (con maven-compiler-plugin anterior a 3.5, que ignora ese parámetro sin avisar, lo agrega
  igual como dependencia), y si lista `annotationProcessors` por nombre de clase (javac corre solo esos), lo agrega a
  esa lista.
- **Qué enlaza por cada rol:** `Set<Rol>` (la vista viva), `Rol` (el preferido, fijo al construir) y
  `Provider<Rol>` (el preferido en cada `get()`).
- **Rastrea referencias fijas también en el grafo de la app,** con un `ProvisionListener` que construye
  cada objeto dentro de `PluginService.building(...)`. Así `uninstall` sabe si un objeto de la app todavía
  tiene un plugin.
- **Roles que se consumen antes de que el injector exista.** El caso real es un rol que *es* un
  `Module` de Guice: los plugins configuran el injector de la app. Se toman con `snapshot(rol)` dentro de
  `building(...)`, y quedan registrados como retenidos por ese injector. Si no, un plugin cuyo módulo ya
  está adentro del injector de la app se podría desinstalar "limpio" y seguir ejecutando desde un
  classloader descargado.
- **El core ganó tres métodos que no dependen de ningún contenedor,** para que cualquier adaptador
  (Spring, CDI...) se escriba igual: `preferred(rol)`, `snapshot(rol)` y `building(supplier)`.
- **`@Inject` de Guice también cuenta** (`com.google.inject.Inject`), además de `jakarta` y `javax`, para
  decidir si una clase es extensión: los plugins se construyen con Guice, que lo acepta.

### El modelo para un panel de plugins

Lo que una app muestra en su panel de configuración lo da el framework: la app no abre jars.

- **`plugins()`** devuelve, por cada plugin, además del id, la versión y el estado: sus extensiones (clase,
  roles que implementa, `@Replaces`), los roles que **define** para sub-plugins, sus dependencias
  (`id@versión`) y la versión de `framework-api` con que se construyó.
- **`roleTree()`** devuelve, por cada rol, quién lo define (un plugin o la app) y quién lo implementa: de
  qué plugin viene cada implementación y si está visible u oculta por el `ConflictResolver` (un
  `@Replaces`). Es el árbol de plugins y sub-plugins visto desde los roles.
- Junto con `heldBy(id)` y `pendingRemovals()` alcanza para una lista con "se puede sacar ahora" o "se
  va al arrancar".
- **Avisos de cambio:** `onChange(Runnable)` corre después de `start()`, `install`, `uninstall` y
  `uninstallOnNextStart`, los haga quien los haga, y devuelve un `AutoCloseable` para desuscribirse. Los
  roles ya son vistas vivas; esto es para lo que la aplicación arma a partir de ellos (menús, ventanas,
  este panel). Corre en el hilo que hizo el cambio y un listener que falla se loguea sin afectar a los
  demás. El panel de Swing se suscribe y se refresca solo.
- **Lo que trae la app también cuenta.** Si la app implementa un rol suyo (un módulo propio, no un
  plugin) y lo declara en `META-INF/services` (el processor lo hace solo), `start()` lo activa antes que los
  plugins, como si fuera un plugin más con id `application` y versión `0.0.0`: aparece en `roles()`,
  `preferred()`, lo que se arma con `create()`, lo que inyectan los plugins y `PluginsModule`. Se crea una
  sola vez, con `@Inject` de los servicios expuestos y `HasLifecycle`. Pasa por el `ConflictResolver`: por
  la versión, a igual rol se prefiere un plugin, y un plugin con `@Replaces("application")` lo tapa (el
  árbol de roles lo muestra oculto). Los roles salen de los `roles.idx` del classpath de la app (el
  del class loader de contexto al construir el servicio). `builder().applicationImplementations(false)`
  lo apaga, por ejemplo en un test con dobles de prueba de un rol en el classpath. Ojo con un modo "sin
  plugins" que no construye el servicio: ahí tampoco llega lo propio de la app. Correr sin plugins es
  correr sin jars (un servicio sin fuente), no sin lo que trae la app; si de verdad no hay servicio, eso
  lo tiene que buscar la app por su cuenta (`ServiceLoader`).
- **Sale del estado real,** no de `plugin-metadata.json`: el índice de extensiones de PF4J, las clases
  cargadas, el manifiesto, `roles.idx` y el registro. Por eso vale también para plugins con metadata
  escrita a mano.
- **Nada gráfico en el core.** El panel de referencia para Swing está en un artefacto opcional aparte,
  `framework-swing`: `new PluginsPanel(plugins)` se pone en un diálogo o una pestaña. La pestaña
  "Plugins" tiene dos listas lado a lado, con botones para pasar de una a la otra:
  - "Installed": un árbol plugin → extensiones → roles, más los roles que define. Arranca colapsado, una
    línea por plugin con versión, estado y "en uso" o "se va al próximo arranque", y un tooltip con
    dependencias, quién lo retiene y el motivo de una falla;
  - "Available": lo que ofrece el catálogo (la `PluginSource` del servicio) y no está instalado. También
    es un árbol colapsado: al abrir un plugin se ve qué roles implementaría y definiría y qué requiere,
    sin instalarlo. Eso sale de `PluginService.describe(artifact)` → `PluginSource.describe`, que por
    defecto no sabe nada. La carpeta y el bundle lo leen del `plugin-metadata.json` que ya trae cada
    jar, sin red. Un catálogo remoto puede publicar ese archivo al lado del jar y parsearlo con
    `PluginSources.description(in)`.

  Las dos admiten selección múltiple. "← Install" instala los seleccionados con sus dependencias; arrancan
  en el momento, pasan a la otra lista y quedan seleccionados ahí, así se ve qué llegó. "Remove →"
  desinstala en el momento lo que nada retiene y deja lo demás para el próximo arranque. El catálogo se
  consulta al mostrar el panel por primera vez, después de cada remoción (lo sacado se vuelve a ofrecer)
  y con "Check", siempre fuera del hilo de Swing, porque puede ir a la red. Esa lista sale de
  `PluginService.available()`, que consulta la fuente y no cambia nada (a diferencia de
  `checkForUpdates()`). Cada entrada muestra también su origen: `PluginSource.origin(artifact)`, un texto
  para personas ("plugins folder", "GitHub release"...) con el que una fuente que une varios lugares
  dice cuál ganó. No forma parte de la identidad del artefacto.

  Cada plugin se muestra con su nombre para personas si lo declara: `Plugin-Name` en el manifiesto, que
  el build toma del `<name>` del pom del plugin (Maven no lo hereda del padre) o de la propiedad
  `crystal.pluginName`. El id queda en los datos chicos, y sin nombre se muestra el id como siempre
  (`PluginInfo.name()`, `PluginDescription.name()`). Los plugins cuyo id comparte prefijo (`device-`,
  `tool-`) van agrupados bajo un nodo por prefijo, cuando son al menos dos contando las dos listas: así un
  plugin solo de un lado sigue agrupado y con su nombre corto. Seleccionar el grupo equivale
  a seleccionar todos sus plugins, para instalar o sacar. Dentro de un grupo, un plugin sin nombre
  propio se muestra con su id sin el prefijo (`beeper`), y el id completo queda en los datos.

  La pestaña "Dependencies" muestra, por plugin, lo que requiere (abriendo hacia abajo toda la cadena) y
  quién lo usa ("used by"); una dependencia que no está instalada aparece en rojo como "missing". Queda en
  una pestaña aparte porque en la primera sería demasiado; ahí sigue estando en el tooltip.

  La pestaña "Roles" es el árbol rol y quién lo define → implementaciones, marcando las ocultas, también
  colapsado. Suma los plugins del catálogo que implementarían cada rol, marcados "(not installed)",
  incluidos roles que todavía nadie implementa. "Refresh" vuelve a leer el estado y conserva lo seleccionado y lo abierto. Armar los árboles
  (`PluginTrees`) está separado de los widgets, así que se prueba sin pantalla.

  Íconos: emojis de OpenMoji (https://openmoji.org, CC BY-SA 4.0) en SVG, cargados con JSVG, que viaja adentro de
  `framework-swing` con el paquete renombrado (`dev.crystal.plugins.swing.internal.jsvg`). Así no choca con
  el jsvg de la app (darklaf trae otra versión, incompatible) y no se hereda como dependencia. Si igual no
  se pueden dibujar, el panel abre sin íconos. Un plugin es una pieza de puzzle 🧩; un sub-plugin, un enchufe 🔌; uno que falló, ❌; uno
  que se va al próximo arranque, ⏳. Una insignia dice de dónde viene: 📦 del bundle, 📁 de una carpeta,
  🌐 del catálogo, 💻 de la app misma. Los roles son 🎭 (con 🧩 si los define un plugin, con ➕ en
  "defines"), las clases ⚙, las dependencias 🔗, y lo oculto o no instalado aparece desvaído.

### Plugins por defecto dentro de la aplicación

Una app puede traer sus plugins adentro y usarlos desde el primer arranque sin ir a internet.

- **En el build de la app**, con la propiedad `crystal.bundleGroupId` (por ejemplo `com.example`), el goal
  `bundle-plugins` (lo engancha solo el plugin de build, en `process-classes`) busca en el repositorio
  local (`~/.m2`) los artifacts de ese groupId y de los que cuelgan de él, en la versión
  `crystal.bundleVersion` (por defecto la de la app, nunca "la última"). Se queda con los que son
  plugins (tienen `Plugin-Id`), deja afuera a la app misma, y los copia a
  `META-INF/crystal/bundled/` con un índice (`bundled.idx`: id, versión, sha256, archivo). Como van a
  `target/classes`, cualquier empaquetado los lleva: `jar:jar`, `maven-shade`, etc. Hace falta haber
  hecho `mvn install` de los plugins antes: si no encuentra ninguno, el build falla (un jar sin sus
  plugins por defecto parece igual al bueno), y si encuentra, el log dice cuántos metió. Para armar la
  app sin plugins adentro, `-Dcrystal.bundleGroupId=` lo apaga.
- **En la app:** `PluginService.builder().defaults(PluginSources.bundled())`. En el primer arranque de
  una caché, los plugins que trae la app se extraen a la caché, verificados por sha256, y se instalan.
  Desde ahí son plugins instalados como cualquier otro: uno que el usuario desinstala no vuelve,
  `install(id)` lo puede traer otra vez desde adentro de la app, y `checkForUpdates()` los considera
  cuando la fuente principal (si hay) no ofrece ese id.
- **Una build nueva de la app actualiza lo instalado que trae distinto.** Al arrancar, un plugin instalado
  que el bundle trae con otros bytes en la misma versión (un `-SNAPSHOT` recompilado) o en una versión
  más nueva se reemplaza por el de la app, y se instalan las dependencias nuevas que traiga, si vienen en
  el bundle. Todo sale de adentro de la app, sin red. No vuelve lo que el usuario desinstaló, y no se toca
  una versión instalada más nueva que la del bundle (una actualización del catálogo).
- **Lo de adentro gana salvo que afuera haya algo más nuevo.** Si la fuente principal ofrece un plugin que
  también viene en la app, vale el de la app mientras la fuente no tenga una versión más nueva: sacar un
  plugin del bundle y volver a instalarlo trae el mismo jar sin bajar nada, aunque la fuente tenga esa
  misma versión armada en otro lado (otros bytes). Solo una versión más nueva baja de la red, en
  `install(id)` o en `checkForUpdates()`. La fuente se consulta igual, para saber si hay algo más nuevo.
- En `examples/host-demo`, el jar de la app lleva los 4 plugins de ejemplo y un test arranca solo con
  eso.

### Bibliotecas de terceros de un plugin

Un plugin lleva adentro las bibliotecas que necesita y la app no tiene, sin configurar nada.

- **En el build,** `package-plugin` mete en `lib/` del jar las dependencias `compile` y `runtime` que no
  son de la familia de la app. Deja afuera las del mismo groupId que el plugin (o uno debajo, donde viven
  los módulos de la app), el framework, los plugins, lo `provided` y todo lo que venga colgado de esas.
  Lo de la familia de la app cuenta como `provided` aunque no lo diga el pom: no se empaqueta ni genera la
  advertencia de "neither a plugin nor provided". El log dice cuáles lleva ("carries 2 libraries in lib/:
  gson-2.11.0, ..."). `-Dcrystal.pluginLibraries=false`
  lo apaga.
- **Al cargar,** cada `lib/*.jar` se extrae una vez a la caché (`libs/<sha256>.jar`, compartido entre plugins
  que lleven los mismos bytes) y se suma al classloader del plugin. Una biblioteca que la app ya tiene no
  se vuelve a cargar: se usa la de la app, así un objeto de esa biblioteca es la misma clase en la app y en
  el plugin.
- **Plugin biblioteca:** un módulo sin extensiones cuyas clases usan otros plugins (por ejemplo, el
  soporte IDE que comparten seis dispositivos) se marca con `<crystal.plugin>true</crystal.plugin>` en su
  pom. Recibe `Plugin-Id`, los plugins que lo usan lo detectan como dependencia por el bytecode, y viaja en
  el bundle como cualquier plugin. En tiempo de ejecución, sus clases se ven desde los que dependen de él.
- **Aislamiento:** cada plugin tiene su classloader (plugin, después sus dependencias, después la app),
  así que dos plugins con distintas versiones de la misma biblioteca no se pisan. Para compartir una sola
  copia entre varios, se hace un plugin "biblioteca" y los demás dependen de él con `@Needs`.

### Instalar sin reiniciar: `install(pluginId)`

Resuelve el flujo "falta el plugin que lee este archivo: lo traigo y lo abro". Qué plugin resuelve qué es
metadata de dominio de la app y vive en su `PluginSource`; el framework aporta el mecanismo.

- **Agrega, nunca reemplaza.** Instala el plugin y las dependencias que le falten, y los arranca en el
  momento: cuando `install` vuelve, sus implementaciones ya están en todas las vistas `roles()`, incluso
  en las que se pidieron antes. No hace falta el unload del hito 7 porque no se toca nada de lo que ya
  corre. Si una dependencia pide otra versión de un plugin que está corriendo, falla y lo dice.
- **Primero el plan.** Baja y verifica cada jar (sha256, una sola vez), lee su `Plugin-Dependencies`
  con el propio descriptor de PF4J y chequea las versiones con el mismo `VersionManager` que usa el
  runtime. Si algo no cierra (una dependencia que la fuente no ofrece, una versión que no encaja), tira
  una excepción y no cambia nada.
- **Se persiste antes de cargar.** Los plugins nuevos entran al conjunto instalado antes de cargarse:
  una caída en el medio no pierde la decisión del usuario, y en el próximo arranque ya están.
- **Un plugin instalado conserva su versión.** `install` no actualiza nada de manera implícita; para eso
  está `checkForUpdates()`.
- **Un catálogo grande funciona sin trucos.** La fuente puede ser el catálogo remoto completo: nada se
  instala hasta que el usuario lo elige con `install(id)`. Una ventana del tipo "lo que hay / lo que se
  puede tener" sale directo del reporte de `checkForUpdates()` (`changes`, `available`, `notOffered`).

## Estado y límites conocidos

- Hechos: los hitos 1 a 8 del plan, más `install(pluginId)`, el adaptador `framework-guice` y la
  separación entre catálogo e instalado. Tests: runtime 57, build core 15, processor 7, API 7, guice 5,
  harness 6, swing 7. Además, `examples/` con dos apps, un sub-plugin, una app con su
  propio injector y dos plugins que se prueban solos con el harness (8 tests de punta a punta).
- **Referencias que el framework no ve:** un objeto que la app guarda después de sacarlo de una vista, o
  un listener que un plugin registra en un servicio del host, no se pueden rastrear. Desregistrar es
  trabajo del `onStop()`, y de la vista hay que guardar la vista, no sus elementos.
- **Aplicar actualizaciones en caliente** (reemplazar una versión que corre por otra) todavía no está:
  `checkForUpdates()` rige desde el próximo arranque. Con `uninstall` + `install` ya están las piezas.
- **Varios procesos sobre la misma caché:** todas las escrituras son atómicas, pero no hay un lock entre
  procesos. Si dos procesos actualizan a la vez, gana el último; los dos estados son consistentes y, en
  el peor caso, un jar se descarga dos veces.
- **Metadata vieja:** si javac no vuelve a correr el processor (clases compiladas antes de agregar el
  plugin de build, o compiladas por otro compilador), el build falla y pide `mvn clean`. Se detecta, pero
  no se arregla solo.
- No hay plugin de Gradle todavía. El processor se puede usar tal cual con `annotationProcessor`, y el
  resto está en `framework-build-core`; falta el adaptador de Gradle, el equivalente al mojo.
- `plugin-metadata.json` se genera pero el runtime todavía no lo lee (lo necesita el hito 6).
- **Librerías privadas:** un plugin es un solo jar. Si usa una librería que no es un plugin ni la provee
  el host, el build avisa, pero empaquetarla (shading) queda a cargo del autor.
- **Pins exactos:** si la fuente ofrece `csv 1.0.1` y un plugin se compiló contra `1.0.0`, ese plugin se
  rechaza (con el motivo). Es la regla "nunca la última" del enunciado. Un rango compatible (`^1.0.0`)
  como opción configurable del build sería una extensión futura; un manifiesto escrito a mano ya puede
  usar rangos.
