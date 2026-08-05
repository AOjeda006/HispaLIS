# PLAN — HispaLIS

> Plan de registro (**fuente de verdad del estado del trabajo**). Es lo que hace segura la
> resumabilidad: el agente lo lee al arrancar y tras cada `/compact`, y lo actualiza al avanzar.
> Mantenlo siempre coherente con la realidad del repo.
>
> El **porqué** de todo lo de aquí está en `docs/diseno.md` (documento de diseño v1.0, autosuficiente).
> Este PLAN es su bajada a ejecución: no lo dupliques, cítalo por sección (§4.8, §6.5…).

## Objetivo

Construir **HispaLIS**, una **simulación** de un **SIL** (Sistema de Información de Laboratorio) para
un laboratorio clínico privado de Sevilla, sobre **HL7 FHIR R5**. El resultado al terminar los tres
hitos: un sistema que atraviesa los ejes reales de interoperabilidad sanitaria —IG propia con
terminología, API FHIR conforme, puente HL7 v2, eventos, SMART on FHIR y una obligación legal
española implementada (notificación EDO)— sin degenerar en una HCE en miniatura.

**Objetivo de este encargo: cerrar el hito 1** — el circuito básico end-to-end
(petición → espécimen → resultado → informe), **sin Kafka, sin HL7 v2 y sin Keycloak**. Al terminarlo
ya hay un proyecto FHIR presentable.

## Alcance / No-objetivos

- **Dentro (hito 1):** IG FHIR R5 con los 9 perfiles y la terminología · backend Java 21 + Spring Boot
  + HAPI FHIR R5 (dominio propio + proyección HAPI JPA en la misma transacción) · web Angular de alta
  de petición y consulta de informe · generador de datos sintéticos en Python · `docker compose` con
  backend + PostgreSQL + web · CI con filtrado por `paths:` y validación FHIR.
- **Fuera del hito 1** (hitos 2 y 3, esbozados al final): motor HL7 v2, Kafka + outbox, servidor de
  terminología con `ConceptMap` del catálogo, Keycloak/SMART, app Flutter, `SubscriptionTopic`,
  reflejas, notificador EDO, Bulk Data `$export`, `AuditEvent`.
- **Fuera del proyecto entero:** ISO 15189 como requisito —solo como justificación de diseño (D17)—,
  conexión al MPA de Diraya (D8), HCDSNS/Nodo SNS, Receta XXI, CMBD/RAE-CMBD y ENS (§4.6).

## Decisiones tomadas

### D1–D20 — cerradas en el diseño, **no se reabren**

| # | Decisión | Por qué (una línea) |
|---|---|---|
| D1 | **FHIR R5 (5.0.0)** | Una simulación es el caso legítimo de R5; el coste (sin US Core ni IPS, sin Synthea, sin servidores públicos) está contabilizado en §2 |
| D2 | **Laboratorio clínico** de clínica privada | Único dominio que puntúa alto en acotable, riqueza FHIR, v2, clientes variados y terminología real (§3) |
| D3 | **Dominio propio + HAPI como proyección** | FHIR es formato de borde: persistir recursos FHIR como entidades pierde los invariantes del negocio (§9, §10) |
| D4 | **FHIR por aplicaciones, v2 por sistemas** | Un navegador no habla MLLP; mezclar los dos contratos en una puerta hace el mapeo inauditable (§7) |
| D5 | **El motor escribe contra la propia API FHIR** | Un solo camino de escritura, con las mismas validaciones, invariantes y auditoría que cualquier cliente |
| D6 | **Sevilla, privado puro sin concierto** | Fija el marco legal aplicable: EDO sí, ENS no, NUHSA a menudo ausente (§4) |
| D7 | **SNOMED CT ed. Española + SNS · LOINC · UCUM** | Los `display` en inglés en un informe español son un error de producto, no un detalle |
| D8 | **EDO a Salud Pública; sin Diraya** | El contrato del MPA no es público: simularlo da falso realismo; la vía EDO es real, obligatoria y documentada (§4.5) |
| D9 | **3 extensiones estándar + 1 propia** | Verificado contra el paquete canónico: casi todo lo que parecía necesitar extensión ya tiene elemento estándar en R5 (§6.1) |
| D10 | **Monorepo único con la IG dentro** | El contrato es compartido y cambia a la vez: un commit atómico que pasa o rompe CI de golpe, en vez de un baile de versiones (§13) |
| D11 | **Motor Spring propio con HAPI HL7v2** | Mismo *toolchain* que el backend y canales como código por construcción; lo que ahorra Mirth no es lo que enseña (§7) |
| D12 | **HL7 V2.5.1 (2007)** | Diff **medido**, no recordado: mismos 151 segmentos y 344 tablas que V2.5, dominado por erratas corregidas — elegirla no cuesta nada |
| D13 | **Flutter para la app del ciudadano** | El objetivo son clientes multiplataforma y en España iOS es ~la mitad del mercado |
| D14 | **HAPI con subconjuntos curados** | La lección es el *binding* y el contrato `$expand`/`$translate`, no operar Snowstorm; y el servidor es intercambiable cambiando una URL (§5) |
| D15 | **Generador propio en Python** | Synthea apilaría dos problemas difíciles (R4→R5 y relocalizar a España) y lo difícil son los resultados verosímiles, no la demografía |
| D16 | **Sin `pattern` en identificadores ajenos** | El laboratorio no los emite: validar su formato solo produce falsos rechazos, y la estructura cambia por Real Decreto (§4.1) |
| D17 | **ISO 15189 fuera de alcance** | Es acreditación **voluntaria**; la obligación real es la autorización sanitaria del Decreto 69/2008 |
| D18 | **Nombre: HispaLIS** | *Hispalis* (Sevilla romana) + **LIS**; ancla el proyecto sin topónimo obvio y evita el choque visual con `LaboratorioYT` |
| D19 | **URIs bajo `https://aojeda006.github.io/HispaLIS/fhir`** | España no tiene juego oficial consolidado: se definen propias, se publican y **se documenta que son propias** (§4.8) |
| D20 | **`CLAUDE.md` raíz + uno por subproyecto** | Un solo raíz con todos los imports sería enorme y contradiría *"30 líneas útiles valen más que 300 que nadie lee"* (§13.2) |

### Decisiones triviales resueltas por el orquestador al preparar el repo

> Reversibles y con default obvio; se anotan aquí en vez de preguntarse (regla de la puerta de
> clarificación).

- **2026-08-03 — Maven como herramienta de construcción de `backend/` e `integracion/`.** Es el
  default del ecosistema Spring/HAPI y lo que asumen los workflows y `AGENTS.md`. El `.gitignore`
  cubre también Gradle por si se cambia; si se cambia, hay que actualizar CI y `AGENTS.md` en el mismo
  commit.
- **2026-08-03 — Un workflow de CI por componente**, todos con `paths:`, más una **guarda de
  auto-omisión** mientras el componente no tenga descriptor de construcción: así la CI no arranca en
  rojo con el repo vacío. Retirar la guarda al andamiar cada componente.
- **2026-08-03 — Los `principios/` se importan en el `CLAUDE.md` raíz**, no en cada componente: los
  `convenciones.md` de Java, TypeScript, Dart y Python los declaran todos como requisito, y el raíz
  siempre está cargado. Los componentes solo importan su stack (§13.2).
- **2026-08-03 — Se añade `interoperabilidad/espana/convenciones.md` a los imports del raíz**, además
  de lo listado en §13.2: la mitad del diseño (identificadores, CIP-SNS/CIP-AUT, tarjeta sanitaria) es
  exactamente su contenido.
- **2026-08-03 — `interoperabilidad/smart-on-fhir/` y `bulk-data/` no se importan todavía.** Se añaden
  a `backend/CLAUDE.md` al empezar el hito 2 y el hito 3 respectivamente; importarlos ahora es
  contexto que nadie usa.
- **2026-08-03 — La consulta a la IG de ÚNICAS (§4.8) no se pudo hacer al preparar el encargo:** la
  política de red del entorno del orquestador bloquea `unicas-fhir.sanidad.gob.es` (403 en el túnel).
  Queda como **ítem 0 del checklist**, antes de escribir el FSH de `PacienteLabES`.
  **Resuelto el 2026-08-03 desde el entorno local** — ver la decisión D21.

### D21 — `system` de identificador: dos adoptados de ÚNICAS, seis propios (ítem 0)

**Fecha:** 2026-08-03. Consultado el **paquete de definiciones** (`package.tgz`, 125 recursos), no el
sitio renderizado. ÚNICAS v0.0.11 (Ministerio de Sanidad) resulta ser también **FHIR R5**.

**Qué publica ÚNICAS, verificado uno a uno:**

| Comprobación | Resultado |
|---|---|
| ¿`NamingSystem` de identificadores? | **No.** Cero en el paquete |
| ¿*Slicing* de `Patient.identifier`? | **No.** Sin `patternIdentifier` ni `fixedUri` |
| `Patient.identifier.system` | `1..1`, con `short` = *«OID registro según el tipo de documento»* |
| **DNI** | `urn:oid:1.3.6.1.4.1.19126.3` — 72 ocurrencias, en ejemplos y en el `example` del elemento |
| **CIP-SNS** | `urn:oid:2.16.724.4.40` — 75 ocurrencias, ídem |
| **CIP-AUT de Andalucía (NUHSA)** | **No lo define.** La rama `2.16.724.4.21.5.*` es de catálogos clínicos, no de identificadores |
| `ValueSet/TiposDocumentosIdentificacionPersona` | **Sí, normativo.** SNOMED CT ext. española del SNS |

**Decisión:** se **adoptan** los dos OID que usa el Ministerio (DNI/NIE y CIP-SNS) y se mantienen
**propios** los otros seis, que ÚNICAS no define. Es un caso intermedio —los usa de facto pero no los
publica como canónicos— y se resuelve por el espíritu de §4.8 (*«adoptarlas en vez de inventar»*):
coincidir con la autoridad nacional en los dos que ella sí usa no cuesta nada, y lo contrario sería
que HispaLIS inventase para el DNI una URI que contradice al Ministerio.

**Ganancia no prevista:** el `ValueSet` de tipos de documento da códigos SNOMED **oficiales del SNS**
para tipar los *slices* de `identifier` —CIP-SNS `1551000122105`, CIP-AUT `1571000122102`, DNI
`22851000122109`— en vez de inventarlos. Confirma además que modelar el NUHSA como CIP autonómico
(§4.1) coincide con cómo lo modela el Ministerio.

**La tabla definitiva vive en `ig/input/fsh/aliases.fsh`**, que es donde la consume el FSH: ningún
`.fsh` escribe una URI a mano.

### Decisiones triviales resueltas al andamiar (ítem 1)

- **2026-08-03 — Spring Boot 3.5.16, no 4.1.0.** HAPI FHIR 8.x va sobre Spring Framework 6 y Jakarta
  EE 10; Spring Boot 4.x salta a Spring Framework 7 y Jakarta EE 11. Coger la última rompería el
  servidor JPA que sostiene la proyección. Revisar al actualizar HAPI.
- **2026-08-03 — Maven por *wrapper* en modo `only-script`.** No hace falta Maven instalado y **no
  entra ningún `.jar` en el repositorio**.
- **2026-08-03 — Spotless enganchado a la fase `verify`**, no como orden suelta: el formato se exige
  con el mismo comando que corre los tests.
- **2026-08-03 — La web usa el ejecutor por defecto de Angular 22: vitest sobre jsdom, no Karma.**
  Angular ya no genera Karma. Obliga a **Node 24** (`engines: ^22.22.3 || ^24.15.0 || >=26`) y a
  quitar `--browsers=ChromeHeadless` del workflow, que era sintaxis de Karma.
- **2026-08-03 — `.gitattributes` con `eol=lf`.** Se desarrolla en Windows y la CI corre en Linux:
  sin esto, `mvnw` se commitea con CRLF y el runner falla con `bad interpreter`.
- **2026-08-03 — La validación de ejemplos de la CI se activa en cuanto la IG tiene perfiles.**
  Mientras solo esté andamiada no hay nada que validar. El criterio C2 queda intacto: con perfiles
  presentes y sin ejemplos, el build se detiene.

### Decisiones triviales resueltas al escribir los perfiles (ítem 2)

- **2026-08-03 — El NHC son ocho dígitos** (invariante `hlis-nhc-1`). El diseño exige que el NHC
  propio sea el único identificador con formato validado (§4.1) pero no fija cuál. Se elige lo más
  simple defendible: un secuencial de ocho dígitos con ceros a la izquierda. Lo emite el laboratorio,
  así que validarlo no puede rechazar a un paciente real. **El generador (ítem 13) y el backend deben
  emitir este mismo formato.**
- **2026-08-03 — La extensión `codigo-ine` se adelanta del ítem 3 al 2.** `PacienteLabES` y
  `LaboratorioOrg` la referencian sobre `address`: sin ella los perfiles no compilan. El ítem 3
  conserva el resto de su alcance (`CodeSystem`, `ConceptMap` y los tres `ValueSet`).
- **2026-08-03 — Los *bindings* de terminología se dejan para el ítem 3.** Los perfiles fijan
  estructura y cardinalidades; atar `code`, `type` o `condition` a un `ValueSet` que aún no existe
  haría fallar la compilación. Afecta también a `identifier.type`, que se codificará con los códigos
  SNOMED del SNS hallados en ÚNICAS (D21) cuando exista la terminología.
- **2026-08-03 — `FacultativoLab.identifier` NO se divide en *slices*.** El diseño (§4.3) pide «un
  *slice* por colegio emisor», pero un discriminador por `system` exige un valor **fijo** por *slice*
  y el `system` del colegiado es paramétrico (`…/sid/colegiado/{colegio}`): habría que enumerar los
  52 colegios provinciales de médicos más los de farmacéuticos, biólogos y químicos. El colegio se
  identifica con `identifier.assigner`, que es procesable y no cierra la lista. Ver *Notas / riesgos*.

### Decisiones tomadas al escribir los ejemplos (ítem 4)

- **2026-08-03 — Nunca se fija a mano el `display` de un código de terminología externa.** El
  validador oficial aplica la **variante lingüística según el locale**: `$LOINC#11502-2` con
  `display = "Laboratory report"` —que es literalmente el `LONG_COMMON_NAME` de la tabla LOINC 2.82—
  **falla** en un equipo con locale español, porque `tx.fhir.org` responde con el nombre en
  castellano. El mismo recurso pasaría en la CI y fallaría en local. Registrado como
  `docs/adr/adr-0009-display-de-terminologia-externa.md`. Los `display` propios del catálogo local se
  quedan, porque de ese CodeSystem somos la autoridad.
- **2026-08-03 — Los ejemplos se validan en local antes de subir.** El validador oficial cazó los dos
  errores de arriba en un ciclo de dos minutos; descubrirlos en la CI habría costado dos ciclos de
  ocho. Requiere descargar `validator_cli.jar`, que no se versiona.
- **2026-08-03 — Quedan 18 avisos `dom-6`** («*a resource should have narrative*») al validar
  `fsh-generated/`. No son un defecto: la narrativa la genera el IG Publisher después, y lo que valida
  la CI es la entrada. Son avisos, no errores, y no detienen el build.

### Decisiones tomadas al cerrar el circuito (ítem 9)

- **2026-08-05 — La conformidad la afirma el validador oficial, no un test propio.** `ci-backend`
  compila el FSH con SUSHI —**no** construye la IG entera, que es cosa de `ci-ig` y cuesta minutos— y
  pasa el validador de HL7 sobre lo que el circuito produce. Por eso su `paths:` vigila ahora
  `ig/input/fsh/**` entero.
- **2026-08-05 — Se valida lo que el servidor devuelve, no lo que el cliente envió.** Lo que tiene
  que ser conforme es la **proyección que el laboratorio publica**; validar la entrada solo diría que
  el cliente escribe bien.
- **2026-08-05 — `Organization` y `Practitioner` entran por el proveedor estándar de HAPI.** Son
  datos maestros del laboratorio, no agregados con invariantes propios: §10 solo lista petición,
  espécimen, resultado, informe y notificación EDO. Modelarlos como agregados sería inventar
  complejidad que el negocio no pide. Siguen entrando por la API FHIR, que es lo que exige el
  invariante 3 del proyecto.
- **2026-08-05 — La petición se modela como LÍNEAS que comparten número, no como una petición con
  líneas dentro.** Es lo que permite que cada prueba del mismo volante avance a su ritmo: unas se
  informan hoy y otras tardan tres días. `numero_de_peticion` **no es único** a propósito.

### Decisiones tomadas con el invariante del espécimen (ítem 8)

- **2026-08-05 — El id lógico FHIR de un recurso es el UUID de su agregado.** Una referencia
  `Specimen/<uuid>` que llega por la API resuelve al dominio **sin tabla de correspondencias**, que
  es lo que permite comprobar el invariante sin leer la proyección — y lo que hará trivial el
  reconciliador del hito 2. Obliga a escribir la proyección con `dao.update(...)` y no `create(...)`,
  que es como HAPI admite un id asignado por el cliente. Verificado: sigue devolviendo `201` y
  `ETag W/"1"`.
- **2026-08-05 — Tercer tipo de error de dominio: `ReglaDeNegocioIncumplida` → 422.** `409` es chocar
  con algo que ya existe (un NHC repetido); esto es **una acción que no procede** por el estado de
  las cosas. `422 Unprocessable Entity` es el código que la propia especificación de FHIR reserva
  para cuando *«el recurso propuesto viola las reglas de negocio del servidor»*.
- **2026-08-05 — La comprobación del invariante va en la fábrica del agregado, no en el caso de uso.**
  Un método que devolviera un booleano se puede ignorar; `exigirQuePuedeProducirResultados()` no. La
  diferencia entre un invariante y una recomendación es que no exista un camino que se la salte.
- **2026-08-05 — El motivo del rechazo se guarda como texto en el dominio, no como código.** El
  núcleo no es un servidor de terminología: lo que necesita es poder decir *por qué* se rechazó. La
  codificación vive en la proyección, que es donde se consume.

### Decisiones tomadas al abrir el dominio (ítem 7)

- **2026-08-05 — El dominio persiste con SQL explícito, no con Spring Data JPA.** *Desviación
  consciente de la convención de Spring del proyecto.* El `EntityManagerFactory` es de HAPI
  (ADR-0011): meter nuestras entidades dentro obligaría a reproducir a mano la lista de paquetes que
  HAPI escanea, que es interna suya y puede crecer en una versión menor —y lo que falte desaparece
  en silencio—. A cambio el agregado queda **libre de anotaciones de persistencia**, que es lo que
  Clean Architecture pide de un núcleo. La convención sigue valiendo donde el EMF sea nuestro.
- **2026-08-05 — La transacción única se prueba por el lado del fallo, no por el camino feliz.** Un
  test que solo dé de alta y lea pasa igual con una transacción que con dos. El que la prueba es el
  del NHC duplicado: si el rechazo del dominio dejara un `Patient` detrás, habría dos transacciones.
  Todo depende de `JpaTransactionManager.setDataSource(...)`, que no da ningún aviso al faltar.
- **2026-08-05 — El proveedor de `Patient` hereda del de HAPI y solo sustituye la creación.** La
  lectura, la búsqueda, `_history` y el `ETag`/`If-Match` vienen de ahí, y son los criterios 8, 10 y
  11. Se registra sustituyendo al de HAPI —dos proveedores del mismo recurso es error de arranque— y
  hay que enlazarlo a su DAO a mano, porque es un bean de Spring y la fábrica de HAPI no lo toca.
- **2026-08-05 — El NHC lo emite el laboratorio y su unicidad se declara en la base de datos.** Una
  comprobación previa en Java no sobrevive a dos altas concurrentes; el índice único no se equivoca.
- **2026-08-05 — Flyway gobierna `dominio`; HAPI gobierna lo suyo.** Conviven un Flyway y un
  `hbm2ddl.auto` sin pisarse porque cada uno manda en su esquema. Añadido
  `flyway-database-postgresql`, que es lo que faltaba en el ítem 6.

### Decisiones tomadas al abrir el backend (ítem 6)

- **2026-08-03 — Los tests del backend corren contra PostgreSQL embebido (`io.zonky.test`), no
  Testcontainers ni H2.** *Decidido por el usuario tras plantearle las tres opciones.* HAPI JPA
  necesita una base de datos real para arrancar, y **en el equipo de desarrollo no hay Docker
  instalado**, así que Testcontainers dejaría los tests sin poder ejecutarse en local —solo en CI, a
  ocho minutos por ciclo—. El embebido arranca un binario real de PostgreSQL en proceso: mismo motor
  y mismo dialecto que producción, sin Docker. H2 se descartó porque su esquema y su dialecto no son
  los de producción y esconderían hasta el ítem 15 cualquier fallo específico de PostgreSQL.
  **Pendiente que afecta al ítem 15:** `docker compose up` (C12) sí necesita Docker; hay que
  instalarlo antes de llegar ahí.
- **2026-08-03 — Empotrar HAPI JPA cuesta siete obstáculos y ninguno falla al compilar.** Seis
  `@Configuration` que importar en vez de una, beans que el `starter` define por ti, el dialecto que
  hay que declarar explícito, `allow-circular-references` por un ciclo de HAPI, Hibernate Search y
  Elasticsearch que hay que apagar, y **Spring Boot degradando `commons-lang3` por debajo de lo que
  HAPI necesita** —conflicto que solo estalla al servir la primera petición—. Todo en
  `docs/adr/adr-0011-empotrar-hapi-jpa-en-una-aplicacion-propia.md`.
- **2026-08-03 — La lista de perfiles se duplica en el backend, pero con red.** El
  `CapabilityStatement` debe declarar los perfiles y el backend no puede leer la guía en ejecución,
  así que `PerfilesDeLaGuia` los repite. Un test cruza esa lista contra los `.fsh` —la fuente, no lo
  generado, para no depender de que SUSHI haya corrido— y falla si divergen. Por eso `ci-backend`
  vigila también `ig/input/fsh/profiles/**`: sin esa ruta, añadir un perfil a la guía no dispararía
  el test que detecta justamente eso.
- **2026-08-03 — Flyway queda apagado hasta el ítem 7.** Llega transitivo desde HAPI y se
  autoconfigura solo, abortando el arranque porque le falta `flyway-database-postgresql`. No hay
  nada que migrar todavía: el esquema de la proyección lo gobierna HAPI y el del dominio aún no
  existe. **El ítem 7 lo enciende**, con su primera migración y con ese módulo.

### Decisiones tomadas al publicar la guía (ítem 5)

- **2026-08-03 — El idioma de la guía se declara explícitamente; si no, el publisher la da por
  inglesa.** La guía está escrita entera en castellano y se publicó etiquetada `<html lang="en">`,
  bajo `/en/` y con los rótulos de la plantilla en inglés, **con toda la cadena de construcción en
  verde**: ninguna herramienta puede detectar que el texto está en un idioma distinto del declarado.
  Se declara ahora en los dos sitios que hacen cosas distintas —`i18n-default-lang` (renderizado) y
  `ImplementationGuide.language` (recurso, y fuente de `resource-language-policy: all-ig`)— más
  `jurisdiction: ES`. Registrado como
  `docs/adr/adr-0010-el-idioma-de-una-ig-se-declara-o-se-asume-ingles.md`.
  **La comprobación es sobre el sitio desplegado, no sobre el build.**
- **2026-08-03 — La salida del publisher se reparte por carpeta de idioma y la raíz es un *stub* de
  JavaScript.** Es su diseño, no algo desactivable: las páginas cuelgan de `/es/` y
  `https://aojeda006.github.io/HispaLIS/` redirige por JS. Un cliente sin JavaScript no obtiene nada
  en la raíz. Afecta a cómo se enlaza la guía desde fuera.
- **2026-08-03 — El `id` de un `Instance:` sale del nombre del bloque, que es PascalCase.** El
  `ConceptMap` declaraba `url = …/ConceptMap/catalogo-a-loinc` pero se publicaba como
  `ConceptMap-CatalogoALoinc.html`: dos nombres para el mismo artefacto. Se fija el `id` explícito.
  Vale para cualquier artefacto de conformidad escrito como `Instance:` —`ConceptMap`,
  `CapabilityStatement`, `NamingSystem`—, que es donde SUSHI no puede derivarlo de un `Id:`.

### Decisiones tomadas al escribir la terminología (ítem 3)

- **2026-08-03 — La unidad decide el término LOINC, no el nombre.** El laboratorio informa la glucosa
  en `mg/dL`, así que el término correcto es `2345-7` (*masa/volumen*) y **no** `14749-6`
  (*moles/volumen*), que es el que sale al buscar «glucosa» por texto. Igual con creatinina, urea y
  colesterol. Y **urea (`3091-6`) no es nitrógeno ureico (`3094-0`)**: son magnitudes distintas con un
  factor de 2,14 entre ellas. Este es el error de mapeo LOINC más frecuente y estuvo a punto de
  colarse aquí.
- **2026-08-03 — No todas las correspondencias son equivalencias.** Cinco de las 21 se declaran
  `source-is-broader-than-target`, porque el término LOINC fija un método que el código local no fija.
  Forzar `equivalent` para que quedase uniforme sería inventar una precisión que el catálogo no tiene.
- **2026-08-03 — La unidad UCUM vive en el `CodeSystem`, como propiedad.** El generador (ítem 13) y el
  backend necesitan la unidad de cada prueba; sin la propiedad se construirían una tabla paralela, que
  es justo lo que prohíbe el invariante 4 del proyecto.
- **2026-08-03 — Los `display` de LOINC van en inglés, sin alterar.** Su licencia prohíbe cambiar el
  contenido de sus campos, y la **variante lingüística española de LOINC 2.82 es parcial**: traduce
  los ejes (`Glucosa`, `Concentración de masa`, `Suero o Plasma`) pero deja vacío el nombre largo. El
  español que ve el usuario es el `display` del catálogo local, que sí es nuestro. Los conceptos
  SNOMED se enumeran **sin `display`** por el mismo motivo: lo resuelve el servidor.
- **2026-08-03 — `identifier.type` con códigos de v2-0203, no con los SNOMED del SNS de D21.** Los
  tres códigos de la extensión española (`1551000122105`, `1571000122102`, `22851000122109`)
  **no resuelven en `tx.fhir.org`**, que solo sirve la edición internacional: usarlos rompería la
  validación en CI. Se usan los de THO, que sí se validan — y `JHN` (*jurisdictional health number*)
  describe el CIP autonómico mejor de lo esperado. **Los SNOMED del SNS se recuperan en el hito 2**,
  al montar el servidor de terminología con la Edición Española (D14); un `CodeableConcept` admite
  las dos codificaciones a la vez.
- **2026-08-03 — Los motivos de rechazo combinan dos sistemas de THO**, porque ninguno basta:
  `RejectionCriterion` aporta volumen insuficiente y contenedor roto, y `specimenCondition` —al que R5
  ata `Specimen.condition`— aporta contaminada y autolizada. Ninguno inventado.
- **2026-08-03 — El `ConceptMap` se escribe explícito, sin `RuleSet`.** Los parámetros de un `RuleSet`
  de FSH **no admiten paréntesis**, ni escapados, y los nombres oficiales de LOINC los llevan
  (`Thyroxine (T4) free…`). Como no se puede alterar el nombre publicado por LOINC, la tabla va
  entera. Un `ConceptMap` es datos, no lógica repetida: escribirlo explícito no es duplicación.

## Estado actual

**Ítems 0 a 10 cerrados (2026-08-05).** La guía de implementación está terminada y **publicada en
`https://aojeda006.github.io/HispaLIS/`** (las páginas cuelgan de `/es/`; la raíz redirige por
JavaScript), y el backend ya tiene su primer circuito de escritura completo: un `POST /fhir/Patient`
entra por la API, pasa por el núcleo de dominio y sale publicado como proyección, todo en una sola
transacción, y el primer invariante de negocio puro ya rechaza lo que no debe.

| Componente | Estado | Verificado con |
|---|---|---|
| `ig/` | 9 perfiles, extensión `codigo-ine`, `CodeSystem` de 21 pruebas, `ConceptMap` a LOINC, 4 `ValueSet` y 18 ejemplos — **publicada** | `npx fsh-sushi .` → **0 errores, 0 warnings**; en CI, IG Publisher y validador oficial **en verde**; sitio desplegado comprobado (19 enlaces de la portada, `lang="es"`, los tres avisos) |
| `backend/` | Servidor JPA empotrado · **los cinco agregados del hito 1** sobre el esquema `dominio` con Flyway · circuito completo `Patient` → `ServiceRequest` → `Specimen` → `Observation` → `DiagnosticReport` · concurrencia optimista con `If-Match` → `412` | `./mvnw verify` → **BUILD SUCCESS, 32 tests**; validador oficial sobre lo que publica el circuito → **0 errores** |
| `web-profesional/` | Angular 22.1 + vitest + angular-eslint | `npm run lint`, `npm test` (**3 tests**), `npm run build` |
| `simuladores/` | Paquete `generador` con su CLI, ruff y pytest | `ruff check`/`format`, `pytest` → **7 tests** |
| `integracion/`, `app-ciudadano/` | **Sin andamiar a propósito** (hito 2) | conservan su guarda de auto-omisión |

**Siguiente: ítem 11** — búsqueda y paginación. Se predijo que el 10 y el 11 saldrían baratos «porque
vienen heredados de HAPI y solo hay que probarlos», y el 10 **desmintió la mitad**: probar el `PUT`
destapó que el `update` heredado escribía la proyección y dejaba el dominio atrás. La lección se
aplica al 11: el criterio no es que el `Bundle` llegue, sino que la paginación se recorra siguiendo
`Bundle.link[relation=next]` sobre datos que el dominio reconozca como suyos.

> **Estado de la CI.** Subido a `origin/main` por **SSH** (el PAT de HTTPS no tiene *scope* `workflow`
> y GitHub rechaza el push de `.github/workflows/`). Comprobado ya en ejecuciones reales:
>
> - **El filtrado por ruta funciona**, con evidencia en los dos sentidos: un push que tocó `ig/**` y
>   `backend/**` disparó exactamente esos dos workflows y ninguno de los otros cuatro.
> - **`backend` en verde**, tras corregir el bit de ejecución de `mvnw`.
> - **`ig`: los dos jobs pasan.** El IG Publisher construye la guía —Ruby + Jekyll y plantilla `fhir2`
>   incluidos—, la comprobación de «un ejemplo por perfil» pasa, el **validador oficial de HL7 valida
>   los 18 ejemplos** contra sus perfiles, y el job de publicación despliega a Pages.
> - **La salvaguarda de C2 se ha visto fallar de verdad**: con los nueve perfiles escritos y sin
>   ejemplos, la CI detuvo el build. No es una comprobación teórica.
> - **`web-profesional`, `simuladores`, `integracion` y `app-ciudadano` siguen sin ejecutarse nunca**:
>   no se ha tocado su ruta desde la primera subida.

---

## Checklist — Hito 1

> Un ítem = una unidad de trabajo pequeña, con criterio de aceptación verificable.
> `[ ]` pendiente · `[x]` hecho (cumple criterio + verificado + commiteado).
> La etiqueta **(C*n*)** enlaza con el criterio de aceptación *n* de §14 del diseño.

### Preparación

- [x] **0 — Consultar la IG española de ÚNICAS y fijar los `system` definitivos.**
  *Hecho el 2026-08-03* — ver **D21**. Adoptados los OID del Ministerio para **DNI/NIE**
  (`urn:oid:1.3.6.1.4.1.19126.3`) y **CIP-SNS** (`urn:oid:2.16.724.4.40`); los otros seis siguen
  siendo propios porque ÚNICAS no los define. Tabla definitiva en `ig/input/fsh/aliases.fsh`.
  *Criterio:* consultada `https://unicas-fhir.sanidad.gob.es/` (paquete de definiciones, no el sitio
  renderizado); para **DNI/NIE** y **CIP-SNS**, si ÚNICAS publica URI canónica, **se adopta la suya**;
  si no, se mantiene la propia de §4.8. La tabla de `system` queda escrita en `ig/` y el resultado
  —adoptado o no, y por qué— anotado aquí en *Decisiones*. **Bloquea al ítem 2**: es el único punto
  del proyecto con riesgo real de retrabajo y toca hacerlo **antes** del FSH de `PacienteLabES`.

- [x] **1 — Andamiar el monorepo y dejar la CI en verde.**
  *Hecho el 2026-08-03.* Andamiados `ig/`, `backend/`, `web-profesional/` y `simuladores/`, con sus
  guardas de auto-omisión retiradas; `integracion/` y `app-ciudadano/` conservan la suya (hito 2).
  Los **37 imports** de los siete `CLAUDE.md` verificados uno a uno. Verificado en local:
  `npx fsh-sushi .` (0 errores, 0 warnings), `./mvnw verify` (3 tests), `npm run lint` + `npm test`
  (3 tests) + `npm run build`, y `ruff` + `pytest` (7 tests).
  **No verificado:** que los workflows corran de verdad. Subido a `origin/main` el 2026-08-03: los
  seis quedan **registrados y `active`** pero **sin ejecutar ninguna vez**, porque un *push* que crea
  la rama no tiene diff contra el que evaluar los `paths:`. Comprobado hasta aquí: YAML válido,
  `paths:` disjuntos y los workflows reconocidos por GitHub.
  *Criterio:* existen `ig/sushi-config.yaml`, `backend/pom.xml`, `web-profesional/package.json` y
  `simuladores/pyproject.toml`; cada workflow de `.github/workflows/` **corre y pasa** al tocar su
  ruta y **no corre** al tocar otra (comprobado en el historial de Actions); las guardas de
  auto-omisión retiradas en los componentes ya andamiados; `README.md` y `AGENTS.md` con los comandos
  reales. **Verifica también los imports:** cada ruta `@../BibliotecaDocumentacion/...` y
  `@../../BibliotecaDocumentacion/...` de los siete `CLAUDE.md` resuelve a un fichero existente.

### La guía de implementación

- [x] **2 — Los 9 perfiles en FSH, compilando. (C1)**
  *Hecho el 2026-08-03.* Los nueve perfiles de §6.5 más la extensión `codigo-ine` (adelantada del
  ítem 3, ver *Decisiones*) y tres `RuleSet` compartidos. Tres invariantes propias: `hlis-nhc-1`
  (formato del NHC), `hlis-esp-1` (un espécimen rechazado documenta el motivo) y `hlis-cob-1`
  (`insurance` lleva aseguradora, `self-pay` no). Verificado con `npx fsh-sushi .` → **0 errores,
  0 warnings, 9 perfiles + 1 extensión**, y con el **IG Publisher**, que recorrió limpias las fases
  de *snapshot*, validación de conformidad y generación de artefactos.
  **No verificado:** el renderizado final. El publisher muere en Jekyll, que no está instalado en
  este equipo, y la ruta local tiene un espacio (ADR-0007), así que `ig/output/` **no se ha llegado a
  producir en local**: se comprobó copiando la IG a una ruta limpia. Queda pendiente de la CI.
  *Criterio:* `sushi .` termina **sin errores ni warnings nuevos** y el **IG Publisher** genera
  `ig/output/`; existen `PacienteLabES`, `PeticionLab`, `EspecimenLab`, `ResultadoLab`, `InformeLab`,
  `LaboratorioOrg`, `FacultativoLab`, `CoberturaLab` y `NotificacionEDO` (§6.5).
  *Trampas:* `ServiceRequest.code` es **`CodeableReference`**; las extensiones de apellidos se declaran
  sobre **`HumanName.family`**, no sobre `HumanName`; `Coverage.kind` es **`1..1`**.

- [x] **3 — Terminología y extensión propia.**
  *Hecho el 2026-08-03.* `CodeSystem/catalogo-pruebas` con **21 pruebas** y la unidad UCUM como
  propiedad; `ConceptMap/catalogo-a-loinc` con las 21 correspondencias; y cuatro `ValueSet`
  (`pruebas-del-catalogo`, `tipos-muestra`, `motivos-rechazo-muestra`, `catalogo-edo`). Atados los
  *bindings* que el ítem 2 dejó sueltos y añadidos los `identifier.type`. La extensión `codigo-ine`
  se hizo en el ítem 2. Verificado con `npx fsh-sushi .` → **0 errores, 0 warnings**.
  **Todos los códigos verificados contra fuente primaria:** los LOINC contra la tabla Core de
  LOINC 2.82 archivada en la biblioteca (existen, `ACTIVE`, sin copyright de terceros); los SNOMED
  contra `tx.fhir.org`; los de HL7 contra el paquete `hl7.terminology.r5#7.3.0`.
  *Criterio:* `CodeSystem` del catálogo local, `ConceptMap` catálogo → LOINC, `ValueSet` de tipos de
  muestra, motivos de rechazo y catálogo EDO, y la extensión propia `codigo-ine` — todos compilan y
  se publican en la IG. `hl7.fhir.uv.extensions@5.3.0` declarado como dependencia en `sushi-config.yaml`.

- [x] **4 — Ejemplos que validan contra su perfil, en CI. (C2)**
  *Hecho el 2026-08-03.* **18 ejemplos** en tres escenarios (`analitica`, `rechazo`, `edo`), con los
  nueve perfiles cubiertos. `MUÑOZ`, `ÁLVAREZ` y `PEÑA` presentes, y «Muñoz de la Torre» como
  apellido que rompe cualquier heurístico de partir por el espacio. Cubren además la refleja con
  `triggeredBy`, el rechazo de muestra con su motivo, y `self-pay` frente a `insurance`.
  *Desviación registrada:* los ejemplos van en **`ig/input/fsh/examples/`** como instancias FSH, no
  como JSON a mano en `ig/input/examples/` —que se ha eliminado—. Es lo que manda la convención de
  perfilado (*«toda guía publica ejemplos: `Instance:` con `Usage: #example`»*) y lo que permite que
  SUSHI los compruebe. El workflow se adaptó en consecuencia.
  *Criterio:* al menos un ejemplo por perfil en `ig/input/examples/`; el **validador oficial** corre en
  el workflow contra `hl7.fhir.r5.core@5.0.0` y **falla el build** si un ejemplo no valida contra su
  perfil. Probado en rojo: se rompe un ejemplo a propósito y la CI lo detiene.

- [x] **5 — La IG publicada en GitHub Pages.**
  *Hecho el 2026-08-03.* La guía está en vivo en `https://aojeda006.github.io/HispaLIS/`. Requirió una
  acción manual del usuario —habilitar Pages en *Settings → Pages → Source: «GitHub Actions»*—, que no
  se puede automatizar: la API de Pages exige un token con scope `repo` y el `GITHUB_TOKEN` no lo
  tiene. Se intentó con `actions/configure-pages@v5` y `enablement: true` y falla en cuatro segundos,
  así que el job comprueba el estado y **falla con la instrucción concreta** en vez de morir con un
  error de la acción.
  **Verificado sobre el sitio desplegado, no sobre el build:** los dos jobs en verde; 9 perfiles,
  1 extensión, 4 `ValueSet`, 1 `CodeSystem`, 1 `ConceptMap` y los ejemplos publicados y navegables;
  los **19 enlaces de la portada resuelven**; y los tres avisos obligatorios están presentes.
  *Nota sobre las URIs canónicas:* Pages sirve el repositorio en `https://aojeda006.github.io/HispaLIS/`
  y la base canónica es `…/HispaLIS/fhir` (D19), así que **las URIs canónicas no resuelven a la guía
  publicada** —incluido el enlace a `history.html` de la cabecera del publisher, que da 404—. No es un
  defecto: una URI canónica en FHIR es un identificador, no necesariamente una URL descargable, y la
  guía documenta cuáles son. Hacerlas resolver obligaría a publicar la salida bajo `fhir/`, que
  contradice el criterio de este ítem; queda como propuesta, no como pendiente.
  *Criterio:* el workflow despliega `ig/output/` y la IG es navegable en
  `https://aojeda006.github.io/HispaLIS/`. En la portada consta que es una **simulación con datos
  sintéticos**, que las URIs canónicas son **propias y no oficiales**, y que **ISO 15189 está fuera de
  alcance** (D17).

### El backend

- [x] **6 — `CapabilityStatement` correcto. (C3)**
  *Hecho el 2026-08-03.* Servidor JPA de HAPI **empotrado** en la aplicación (no el
  `hapi-fhir-jpaserver-starter`, que es una aplicación aparte y rompería la transacción única de D3).
  `GET /fhir/metadata` responde `200` con `fhirVersion 5.0.0` y declara los nueve perfiles, cada uno
  bajo el recurso que perfila. **Cuatro tests**, más los tres del andamiaje, contra la aplicación
  entera y un PostgreSQL real: `./mvnw verify` → **BUILD SUCCESS, 7 tests**, Spotless incluido.
  *Desviación del valor por defecto de HAPI:* HAPI rellena `supportedProfile` con **todos** los
  perfiles que conoce —el núcleo de R5 al completo: `lipidprofile`, `clinicaldocument`,
  `cqllibrary`…—. Se vacía y se declaran solo los de la guía: lo otro es afirmar que el servidor
  soporta perfiles que no conoce ni valida, y justo en el documento del que un cliente se fía.
  *Criterio:* `GET /fhir/metadata` devuelve `200` con `fhirVersion` = **`5.0.0`** y declara los
  perfiles soportados. Test automatizado.

- [x] **7 — Read-your-writes en una sola transacción, y el primer invariante de negocio puro ya rechaza lo que no debe. (C4)**
  *Hecho el 2026-08-05.* Aparece el **núcleo de dominio**: agregado `Paciente` con `Nhc` y
  `NombrePersona` como objetos de valor, su puerto `RepositorioDePacientes`, y el esquema `dominio`
  gobernado por Flyway. El camino de escritura es el de §9 — el recurso se traduce a un alta, el
  agregado valida al construirse, se guarda el dominio y **la proyección se genera desde él**, todo
  en un `@Transactional`. `./mvnw verify` → **16 tests**.
  *Lo que sostiene la transacción única:* `JpaTransactionManager.setDataSource(...)`. Sin esa línea
  el SQL del dominio pediría su propia conexión, **todo compilaría y el camino feliz pasaría igual**.
  Por eso el test la prueba **por el lado del fallo**: dos altas con el mismo NHC, y el rechazo no
  puede dejar un `Patient` huérfano. Detalle y las otras cuatro trampas en
  `docs/adr/adr-0012-una-sola-transaccion-entre-dominio-y-proyeccion.md`.
  *Criterio:* `POST /fhir/Patient` devuelve **`201`** + `Location` + `ETag` **`W/"1"`**, y un `GET`
  **inmediato** al `Location` devuelve el recurso. **Test automatizado**, no comprobación manual.
  Dominio y proyección HAPI JPA se escriben en **un solo `@Transactional`** (§9).

- [x] **8 — El invariante del espécimen rechazado, por TDD. (C6)**
  *Hecho el 2026-08-05.* **El rojo está en el historial**: `a283f1f` es el test fallando
  (`expected 422, but was 201` — HAPI acepta encantado un resultado de una muestra rechazada) y
  `1ba49e9` lo pone en verde. Aparecen los agregados `Especimen` y `Resultado`.
  El invariante vive en la **fábrica de `Resultado`**, no en quien la llama: no existe un camino que
  informe un resultado sin pasar por la comprobación. `InformarResultado` **carga la muestra del
  dominio** en vez de creerse lo que diga el recurso recibido, que puede referenciar una muestra
  rechazada sin mencionar su estado.
  *Dos tests que prueban cosas distintas:* el de integración comprueba que la API responde **422**
  con el motivo dentro; el unitario corre **sin Spring, sin HTTP y sin base de datos**, y es lo que
  demuestra que el invariante está en el núcleo — si hiciera falta levantar la API para probarlo,
  estaría en la puerta. `./mvnw verify` → **26 tests**.
  *Criterio:* un `Specimen` con `status = unsatisfactory` **no** puede producir un `Observation`;
  el intento devuelve el error correcto en `OperationOutcome`. **Test escrito en rojo primero** — debe
  verse en el historial de commits. El invariante vive en el **núcleo de dominio**, no en el
  `ResourceProvider`.

- [x] **9 — El circuito completo por API. (C5)**
  *Hecho el 2026-08-05.* Aparecen `Peticion` e `Informe`, con lo que **el dominio del hito 1 queda
  completo**. El test recorre el circuito usando el `Location` de cada paso —si un eslabón no publica
  lo que dice, el siguiente no encuentra a qué apuntar— y vuelca los cinco recursos, que la CI valida
  con el **validador oficial** contra los perfiles de la guía. `./mvnw verify` → **28 tests**;
  validador en local → **0 errores** sobre los seis recursos.
  *Por qué el validador y no solo el test:* que el circuito funcione y que lo que produce sea
  conforme son dos cosas distintas, y la segunda solo la puede afirmar el validador de la
  especificación. Se valida **lo que el servidor devuelve**, no lo que el cliente envió: lo que tiene
  que ser conforme es la proyección que el laboratorio publica.
  *Criterio:* test de integración que crea `Patient` → `ServiceRequest` → `Specimen` → `Observation`
  → `DiagnosticReport`, **todos conformes a su perfil** (validados con `$validate` o con el validador
  oficial en CI).

- [x] **10 — Concurrencia optimista. (C7)** — *hecho el 2026-08-05.*
  `ConcurrenciaOptimistaTest`, cuatro casos: con la versión vigente, `200` y `ETag: W/"2"`; con una
  obsoleta, **`412`** y la corrección del primero intacta; el `PUT` llega a `dominio.paciente`
  (comprobado leyendo la tabla, no la proyección); y el NHC no se puede cambiar → `422`.
  *Lo que no venía heredado:* se esperaba que el `update` de HAPI bastara, y **no bastaba**. Escribía
  la proyección FHIR y dejaba el dominio atrás, en silencio y sin un solo error — la forma más barata
  de que las dos mitades se separen sin que nadie se entere. `Patient` gana su caso de uso
  `ActualizarPaciente` (corrige la filiación en el núcleo y proyecta en la misma transacción) y los
  otros cuatro recursos **rechazan el `PUT` explícitamente** (`EscrituraSoloPorAlta`) hasta que su
  modificación tenga reglas de negocio definidas: entre un fallo visible y una corrupción callada, el
  fallo visible.
  *Y un 409 que debía ser 412:* HAPI responde `409` a cualquier choque de versión. Para un choque
  descubierto sin que nadie preguntara es correcto, pero cuando el cliente **sí preguntó** —mandó
  `If-Match`— la especificación es explícita: ha fallado una precondición, y son `412`. La diferencia
  le importa al cliente, que con un `412` sabe que tiene que releer y reintentar.
  *Criterio:* `PUT` con `If-Match` de una versión obsoleta devuelve **`412`**; con la versión vigente,
  `200` y `versionId` incrementado. Test automatizado.

- [ ] **11 — Búsqueda y paginación. (C8)**
  *Criterio:* `GET /fhir/Observation?patient=…&code=…` devuelve un `Bundle` paginado y el test
  recorre las páginas **siguiendo `Bundle.link[relation=next]`**, nunca construyendo la URL a mano.

- [ ] **12 — Errores en `OperationOutcome`. (C9)**
  *Criterio:* recurso mal formado → `400`; no encontrado → `404`; conflicto de versión → `412`;
  violación de invariante → el código que corresponda; **siempre** con cuerpo `OperationOutcome` y
  **nunca** un `200` con el error dentro. Test por cada caso.

### Los clientes y el arranque

- [ ] **13 — Generador de datos sintéticos. (C11)**
  *Criterio:* `python -m generador --seed 42` produce pacientes con **apellidos dobles** (incluidos
  casos como `"de la Torre Gómez"`), **DNI/NIE con dígito de control válido** y **NUHSA con formato
  `AN` + 10 dígitos**; **`MUÑOZ`, `ÁLVAREZ` y `PEÑA` entre los casos**; una parte de los pacientes
  **sin NUHSA ni CIP-SNS** (el caso real de un privado). Consume el **mismo** `CodeSystem`/`ConceptMap`
  que la IG, no una lista paralela. Salida **reproducible** con la misma semilla, y todos los recursos
  generados **validan** contra su perfil.

- [ ] **14 — Web profesional en Angular. (C10)**
  *Criterio:* alta de petición y consulta de informe funcionando **contra la API FHIR real** (sin
  *mocks*); los errores se muestran a partir del `OperationOutcome`; los apellidos se muestran sin
  partir por el espacio; el valor se presenta siempre con **unidad y rango de referencia**.

- [ ] **15 — `docker compose up` levanta el circuito. (C12)**
  *Criterio:* `docker compose -f infra/compose/docker-compose.yml up` arranca **backend + PostgreSQL +
  web** y el circuito del ítem 9 funciona de extremo a extremo contra esa pila, partiendo de un repo
  recién clonado y siguiendo solo el `README.md`.

### Cierre del hito

- [ ] **16 — Hito 1 cerrado.**
  *Criterio:* los 12 criterios de §14 verificados, CI en verde en los seis workflows, `PLAN.md` y
  `README.md` coherentes con la realidad del repo, y los aprendizajes transversales del hito anotados
  como ADR nuevos (no se toca la biblioteca a mitad de proyecto).

---

## Hito 2 — la interoperabilidad de verdad (esbozo)

Se detalla al cerrar el hito 1; no se adelanta trabajo.

- Puente **V2.5.1**: `ADT^A01`/`A08`, `OML^O21`, `ORU^R01`, con **charset español** (`MSH-18`) y
  `MUÑOZ`/`ÁLVAREZ`/`PEÑA` como casos obligatorios. ⚠️ Cruzar la **tabla 0354** entre V2.5 y V2.5.1
  antes de generar código: su contenido difiere.
- Motor con el **mensaje original guardado íntegro**, DLQ y **reproceso idempotente** (lo que se pierde
  al no usar Mirth). Deduplicación por `MSH-10` **antes** de escribir.
- **Kafka** con hechos clínicos (`lab.peticiones.v1`, `lab.especimenes.v1`, `lab.resultados.v1`,
  `lab.informes.v1`), clave de partición = paciente, Schema Registry con compatibilidad hacia atrás y
  **outbox transaccional**. Hechos con referencias, **nunca PHI**.
- **Servidor de terminología** con el `ConceptMap` del catálogo local y los contratos `$expand`,
  `$validate-code`, `$translate`.
- **Keycloak** con SMART on FHIR (scopes `patient/`, `user/`, `system/`, contexto de lanzamiento,
  `.well-known/smart-configuration`).
- **App del ciudadano** en Flutter con SMART standalone + PKCE.
- Imports a añadir entonces: `interoperabilidad/smart-on-fhir/convenciones.md` en `backend/CLAUDE.md`.

## Hito 3 — lo que solo existe en R5, lo masivo y lo legal (esbozo)

- `SubscriptionTopic` + `Subscription` con el tópico `resultado-validado`.
- `Observation.triggeredBy` para las **reflejas** (TSH alterado → T4 libre), repeticiones y re-ejecuciones.
- **Notificador EDO** a SVEA/Redalerta: resultado validado cuyo código está en el catálogo EDO ⇒
  notificación obligatoria (`Task`). Obligación legal real, también para privados.
- **Bulk Data** `$export` + `Group` para vigilancia epidemiológica, vía SMART Backend Services.
- `AuditEvent` y `Provenance` completos (justificación de trazabilidad de D17).
- Imports a añadir entonces: `interoperabilidad/bulk-data/convenciones.md`.

---

## Notas / riesgos

- ~~**URIs canónicas propias.** Único punto con riesgo real de **retrabajo**~~ — **cerrado** por D21
  (ítem 0). Quedan seis `system` propios, documentados como tales en la portada de la IG.
- **El IG Publisher tiene cuatro trampas de *toolchain***, resueltas y registradas en
  `docs/adr/adr-0007-trampas-del-ig-publisher.md`: `ig.ini` a mano y **sin comentarios** (uno solo
  aborta la construcción con un mensaje que culpa a la ausencia del fichero), plantilla
  `fhir2.base.template` (la anterior ya no se considera segura y el publisher dejará de admitirla),
  **Jekyll instalado aparte** en la CI, y la negativa a construir **si hay un espacio en la ruta** —
  que afecta al desarrollo local en este equipo, porque el repo cuelga de `PROYECTOS Y REPOS`.
- **Acoplamiento de transacción con HAPI.** Escribir en el esquema de HAPI dentro de la transacción del
  dominio funciona (mismo *datasource*) pero ata a sus DAOs — registrado en `adr-0002`. Vigilar en cada
  actualización de HAPI.
- **Doble escritura del mismo hecho.** Dominio y proyección pueden divergir por un bug de mapeo. Hace
  falta un **reconciliador** que recorra el dominio y regenere la proyección, como **vía de recuperación
  oficial**, no como script de emergencia. Se planifica en el hito 2.
- **Se desarrolla en Windows y se construye en Linux**, y hay **dos** atributos de fichero que solo
  fallan en el runner. El de los finales de línea se previó (`.gitattributes`); el del **bit de
  ejecución no**, y tumbó la CI del backend en su primera ejecución: `backend/mvnw` estaba
  commiteado como `100644` porque NTFS no sostiene el atributo. Corregido con
  `git update-index --chmod=+x` y registrado en `docs/adr/adr-0008-windows-desarrolla-linux-construye.md`.
  **Al andamiar cualquier componente cuya CI ejecute un script del repositorio, comprobar el modo en
  el índice en el mismo commit.**
- **Corrección factual a §2.1 y §4.3 del diseño** (no reabre ninguna decisión):
  - **R5 elimina `Organization.telecom` y `Organization.address`**, sustituidos por `contact`
    (`ExtendedContactDetail`). Faltaba en la tabla de diferencias R4→R5; se ha añadido a las tres
    copias (§2.1, `ig/CLAUDE.md`, `backend/CLAUDE.md`). Verificado contra el paquete canónico al
    compilar `LaboratorioOrg`.
  - **§4.3 pide un *slice* por colegio emisor en `Practitioner.identifier` y eso no es realizable:**
    el discriminador por `system` exige un valor fijo por *slice* y ese `system` es paramétrico. Se
    modela con `identifier.assigner` (ver *Decisiones*, ítem 2).
- **El resultado se publica sin `effective[x]` ni `performer`.** El validador oficial lo avisa —son
  *warnings*, no errores, y el circuito es conforme— pero un resultado sin fecha de medición y sin
  quién lo hizo está clínicamente incompleto, y los dos elementos son `Must Support` en el perfil.
  Añadirlos toca el agregado `Resultado` y una migración. **Se cierra antes del ítem 14**, que es
  donde la web tiene que mostrarlos.
- **El invariante completo del informe está a medias.** §10 pide que solo se emita *con todas las
  líneas de la petición resueltas*; ahora se exige que no esté vacío y que no mezcle pacientes.
  Cerrar la versión completa necesita cruzar las líneas de la petición con sus resultados, y el
  enlace ya existe (`Resultado.peticionId`). Pendiente para el cierre del hito.
- **La IG propia es trabajo real:** nueve perfiles más terminología, sin US Core ni IPS de donde tirar.
  Es el ítem que más fácilmente se subestima.
- **Sin la red de seguridad de Mirth** (D11): almacén de mensajes, reintentos y consola de reproceso
  hay que construirlos (hito 2).
- **Simular normativa real tiene un límite.** El catálogo EDO y el formato de Redalerta se modelan de
  forma **verosímil, no fiel**, y así queda escrito en la IG.
- **CI de monorepo multi-*toolchain*:** filtrado por `paths:` desde el primer día, o cada cambio en
  Flutter recompila el backend.
- **No verificado contra fuente primaria** (§17, nada de esto bloquea el hito 1): estructura interna del
  CIP-SNS (irrelevante por D16), especificación MLLP (sin impacto en código: lo implementa HAPI), y la
  **tabla 0354** de V2.5.1 (cruzar en el hito 2, ambas versiones están archivadas en la biblioteca).
- **Aportaciones pendientes a la biblioteca** al terminar el proyecto (§17.2): las capas 2 y 3 de la
  trampa documental de MLLP —que el documento normativo es un estándar de **V3** y que está **retirado
  desde mayo de 2025 sin sustituto**— van a `interoperabilidad/hl7-v2/`.
