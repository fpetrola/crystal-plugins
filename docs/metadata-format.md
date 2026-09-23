# Formato de metadata de plugins (contrato público, `format: 1`)

Un plugin es un **jar estándar**. El runtime solo lee archivos estándar del jar y no le importa quién
los generó: el plugin de build es la forma cómoda de producirlos, pero escribirlos a mano (u
obtenerlos de Gradle, Bazel, un script, etc.) es igual de válido.

## Lo mínimo que lee el runtime

Hay exactamente dos archivos **obligatorios**:

### 1. `META-INF/MANIFEST.MF` (atributos de PF4J)

| Atributo              | Obligatorio | Significado                                                                                              |
|-----------------------|-------------|----------------------------------------------------------------------------------------------------------|
| `Plugin-Id`           | sí          | Id único del plugin.                                                                                     |
| `Plugin-Version`      | sí          | SemVer (`1.2.3`, `1.2.3-SNAPSHOT`).                                                                      |
| `Plugin-Class`        | no          | `dev.crystal.plugins.runtime.internal.RolePlugin` u omitido. Otro valor = plugin PF4J clásico.           |
| `Plugin-Dependencies` | no          | Sintaxis de PF4J: `id[@versión]` separados por coma; `id?` = opcional. Ver "Dependencias" abajo.         |
| `Plugin-Description`  | no          | Texto libre.                                                                                             |
| `Plugin-Provider`     | no          | Texto libre (el build pone el groupId).                                                                  |
| `Crystal-Api-Version` | no          | Versión de `framework-api` contra la que se compiló.                                                     |
| `Crystal-Metadata`    | no          | Ruta del descriptor JSON (siempre `META-INF/plugin-metadata.json`).                                      |

### 2. `META-INF/extensions.idx` (índice de extensiones de PF4J)

Un nombre binario de clase por línea (clases anidadas con `$`). `#` inicia un comentario. Es el mismo
archivo que produce el processor `@Extension` de PF4J, así que el descubrimiento lo hace el finder
estándar de PF4J.

Lista las **extensiones** del plugin. Una extensión es una clase:

- pública, concreta, y de nivel superior o anidada `static`;
- que implementa, directamente o a través de sus supertipos, al menos una interfaz anotada con
  `@dev.crystal.plugins.api.RoleInterface`;
- que el framework puede construir: tiene un constructor público sin argumentos o exactamente un
  constructor anotado con `@Inject`, sea `jakarta.inject.Inject`, `javax.inject.Inject` o
  `com.google.inject.Inject`.

Una clase que implementa un rol pero no cumple esto (un decorador, una variante que se construye con
parámetros, un helper interno) **no** es una extensión: el framework no la crea y no va al índice.

Los roles se determinan por reflexión sobre la clase, así que el índice no tiene que decir qué rol
implementa cada una. Lo mismo `@Replaces`: el runtime lo lee de la clase (retención `RUNTIME`). Su valor
es un id de plugin (reemplaza todas las implementaciones de ese plugin para los roles compartidos) o
`plugin-id:nombre.binario.DeLaClase` (reemplaza solo esa clase).

**Ejemplo mínimo escrito a mano**

```
META-INF/MANIFEST.MF
    Manifest-Version: 1.0
    Plugin-Id: csv
    Plugin-Version: 2.0.0

META-INF/extensions.idx
    com.acme.csv.CsvExporter
```

(Esto es exactamente lo que ejercita `PluginServiceTest.handWrittenMetadataIsEnough`.)

## Lo que genera además el build

### `META-INF/services/<rol>`

Registros de `java.util.ServiceLoader`, uno por rol. El runtime no los usa; están para que el jar
sirva sin el framework (tests, herramientas, otros contenedores). Solo incluyen las extensiones que
`ServiceLoader` puede instanciar, es decir las que tienen constructor público sin argumentos. Una
extensión que solo se construye por su constructor `@Inject` está en `extensions.idx` pero no acá:
listarla haría que `ServiceLoader` lance `ServiceConfigurationError` para todos los que consumen ese rol.

### `META-INF/crystal/roles.idx`

Las interfaces `@RoleInterface` que *define* el jar, un nombre binario por línea (`#` inicia un
comentario). Se escribe en todo jar que define roles, sea plugin o no (las APIs de la app también), y es
lo que usan los adaptadores de inyección (`framework-guice`) para descubrir los roles sin que nadie los
liste. Se puede escribir a mano.

### `META-INF/plugin-metadata.json`

Descriptor completo, para herramientas y para los próximos hitos (resolución de conflictos, chequeos
de compatibilidad, dependencias ancladas). Hoy el runtime no lo lee. Todas las claves aparecen siempre
y siempre en este orden, así el archivo es reproducible byte a byte.

```json
{
  "format": 1,
  "id": "plugin-beeper",
  "version": "1.0.0",
  "apiVersion": "0.1.0",
  "extensions": [
    {
      "class": "com.example.beeper.Beeper",
      "roles": ["com.example.emulator.Peripheral"],
      "replaces": [],
      "needs": [],
      "lifecycle": true
    }
  ],
  "definesRoles": [],
  "roleApis": [
    { "role": "com.example.emulator.Peripheral", "artifact": "com.example:emulator-api", "version": "1.0.0" }
  ],
  "dependencies": [
    {
      "id": "plugin-csv-exporter",
      "version": "1.0.0",
      "source": "bytecode",
      "types": ["com.example.csv.CsvDialect"]
    }
  ]
}
```

| Clave          | Escrita por       | Significado                                                                                          |
|----------------|-------------------|------------------------------------------------------------------------------------------------------|
| `format`       | processor         | Versión del layout. Quien lo consuma debe rechazar formatos que no conozca.                          |
| `id`, `version`| build tool        | Igual que en el manifiesto.                                                                          |
| `apiVersion`   | build tool        | Versión de `framework-api` en tiempo de compilación.                                                 |
| `extensions`   | processor         | Las extensiones (ver arriba): roles (ordenados), `@Replaces`, `@Needs`, si implementa `HasLifecycle`.|
| `definesRoles` | processor         | Interfaces `@RoleInterface` declaradas *dentro* de este plugin (puntos de extensión para sub-plugins).|
| `roleApis`     | build tool        | Para cada rol implementado, el artefacto (y su versión) que aportó la interfaz al compilar.          |
| `dependencies` | build tool        | `{id, version?, source, types?}`. Ver "Dependencias". |

El processor escribe una mitad (lo que se deriva del código fuente) y la integración con la
herramienta de build agrega la otra (lo que solo conoce el build: coordenadas y classpath). Un
build system distinto puede producir el mismo archivo completando las claves que faltan.

## Dependencias

Una dependencia entre plugins sale de alguna de estas dos fuentes:

- **`bytecode`**: el código compilado del plugin referencia tipos (en descriptores, firmas genéricas,
  anotaciones o cuerpos de métodos) de una entrada del classpath de compilación **que es un plugin**.
  Se reconoce por los archivos estándar, no por cómo se construyó: su manifiesto tiene `Plugin-Id`. Se
  ancla a su `Plugin-Version`, que es la versión contra la que se compiló. `types` lista los tipos
  referenciados, para saber de dónde viene la dependencia.
- **`needs`**: un id declarado con `@Needs`, para lo que el bytecode no muestra (reflexión, recursos). Si
  ese plugin está en el classpath se ancla a su versión; si no, queda sin versión (cualquier versión).

No son dependencias: los tipos del JDK, los del propio plugin y la **API compartida** (una entrada
`provided` que no es un plugin, como `framework-api` o la API de la app, que provee el host). Un tipo que
viene de una entrada `compile` que no es un plugin produce un aviso: un plugin jar no lleva librerías.

En el manifiesto, `id@1.2.3` significa **exactamente** esa versión (nunca "la última") y `id` solo significa
cualquier versión. PF4J no puede expresar versiones pre-release en sus expresiones (`1.0.0-SNAPSHOT` no
se parsea); el runtime de Crystal compara esos pins exactos por su cuenta y deja los rangos
(`>=1.0.0 & <2.0.0`) a PF4J, así que un manifiesto escrito a mano también puede usar rangos.

Si un plugin tiene una dependencia que no se cumple (falta, o la versión instalada es otra), el runtime
lo rechaza **a él**, y en cascada a los que dependen de él, con el motivo visible en `plugins()`. La
dependencia, que está sana, sigue cargada.
