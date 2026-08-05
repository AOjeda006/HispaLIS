# PLAN — HispaLIS

> Plan de registro (**fuente de verdad del estado del trabajo**). Es lo que hace segura la
> resumabilidad: el agente lo lee al arrancar y tras cada `/compact`, y lo actualiza al avanzar.
> Mantenlo siempre coherente con la realidad del repo.
>
> El **porqué** de todo lo de aquí está en `docs/diseno.md` (documento de diseño **v1.1**,
> autosuficiente). Este PLAN es su bajada a ejecución: no lo dupliques, cítalo por sección
> (§4.8, §6.5…).

## Objetivo

Construir **HispaLIS**, una **simulación** de un **SIL** (Sistema de Información de Laboratorio) para
un laboratorio clínico privado de Sevilla, sobre **HL7 FHIR R5**. El resultado al terminar los tres
hitos: un sistema que atraviesa los ejes reales de interoperabilidad sanitaria —IG propia con
terminología, API FHIR conforme, puente HL7 v2, eventos, SMART on FHIR y una obligación legal
española implementada (notificación EDO)— sin degenerar en una HCE en miniatura.

**Hito 1: cerrado el 2026-08-06.** El circuito básico end-to-end (petición → espécimen → resultado →
informe), sin Kafka, sin HL7 v2 y sin Keycloak. Ya hay un proyecto FHIR presentable.

**Objetivo del encargo en curso: el hito 2** — la interoperabilidad de verdad: puente HL7 V2.5.1,
bus de eventos con outbox transaccional, servidor de terminología y SMART on FHIR. Antes del puente
van **los tres huecos de dominio** que hoy no existen y que el puente usa (ítems 17–19).

## Alcance / No-objetivos

- **Hecho (hito 1):** IG FHIR R5 con los 9 perfiles y la terminología · backend Java 21 + Spring Boot
  + HAPI FHIR R5 (dominio propio + proyección HAPI JPA en la misma transacción) · web Angular de alta
  de petición y consulta de informe · generador de datos sintéticos en Python · `docker compose` con
  backend + PostgreSQL + web · CI con filtrado por `paths:` y validación FHIR.
- **Dentro (hito 2):** anulación de línea, validación facultativa con `Provenance` y esquema `outbox`
  · motor de integración HL7 V2.5.1 sobre MLLP/TLS con almacén de mensajes, DLQ y reproceso
  idempotente · Kafka + Schema Registry alimentado por el outbox · reconciliador dominio → proyección
  · servidor de terminología con `$expand`/`$validate-code`/`$translate` · Keycloak con SMART on FHIR
  · app del ciudadano en Flutter con SMART standalone + PKCE.
- **Fuera del hito 2** (hito 3, esbozado al final): `SubscriptionTopic`/`Subscription`, notificador
  EDO, Bulk Data `$export` + `Group`, `AuditEvent` completo.
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

### D22 — la puerta transaccional sigue cerrada; la atomicidad la pone el reproceso

**Fecha:** 2026-08-06. **Decidido por el usuario** tras plantearle las tres opciones. Es la decisión
que bloqueaba el hito 2 y por eso se toma **antes** de escribir la primera línea del motor.

**El problema.** `ADR-0014` cerró el procesador de transacciones de HAPI para los recursos con
agregado, y está bien cerrado: ese camino llama a las DAO directamente y **no pasa por el núcleo**.
Pero **D5** dice que el motor de integración escribe contra la propia API FHIR como cliente
`system/`, y §11 dice que un `OML^O21` produce **`ServiceRequest` + `Specimen`** — un par que quiere
escribirse junto, porque si el segundo falla queda un volante sin muestra. Con la puerta cerrada, el
motor no tiene hoy forma de escribir ese par atómicamente.

**Las tres salidas planteadas:**

| | Salida | Coste | Lo que se paga |
|---|---|---|---|
| (a) | La transacción **pasa por el núcleo**: el interceptor deja de rechazar y entrega el bundle a un servicio de aplicación que lo descompone en comandos de dominio dentro de un solo `@Transactional` | Alto | Resolver referencias internas `urn:uuid:`, decidir el orden de aplicación y mapear cada error a su `Bundle.entry.response` |
| **(b)** | **El motor escribe recurso a recurso** y la atomicidad vive en el motor: mensaje guardado íntegro, DLQ ante el fallo y **reproceso idempotente** que vuelve a aplicarlo entero | **Cero trabajo nuevo** | El servidor sigue rechazando un verbo estándar de FHIR, y entre el fallo y el reproceso puede haber un `ServiceRequest` sin `Specimen` visible por la API |
| (c) | Una **operación propia** `$aceptar-peticion` que reciba el par y lo escriba por el núcleo en una transacción | Medio | Inventa contrato donde el estándar ya tiene uno, y abre la puerta de las operaciones `$…` que `ADR-0014` dejó anotada como pendiente de revisar |

**Elegida: (b).** El reproceso idempotente hay que construirlo igualmente —§7 lo exige y es lo que se
pierde al no usar Mirth (D11)—, así que (b) no añade trabajo; y la ventana de huérfano es
**exactamente el fallo que la DLQ existe para cerrar**, no un agujero sin dueño. (a) es la salida más
conforme y queda **anotada como objetivo**, no como ítem de este hito: paga por adelantado un
mecanismo genérico —resolución de `urn:uuid:`, orden de aplicación, mapeo de errores por entrada—
para un único caso de uso conocido.

**Lo que vale en las tres, y no se negocia:** **la puerta no se abre sin que lo que entre por ella
pase por el núcleo.** Que un `Bundle transaction` escriba saltándose el dominio no vuelve a ser
aceptable bajo ninguna de las tres opciones. Si algún día se implementa (a), el interceptor de
`ADR-0014` no se retira: se sustituye por el desvío al servicio de aplicación.

**Consecuencias operativas de haber elegido (b):**

- El **almacén de mensajes, la DLQ y el reproceso pasan a ser prerrequisito del `OML^O21`**, no una
  red de seguridad que se añade después. Se ordenan así en el checklist (ítems 22 y 25 antes del 26).
- **El reproceso tiene que ser idempotente de verdad**, y eso se prueba: reaplicar el mismo mensaje
  dos veces no puede producir dos volantes. Es lo que sostiene la atomicidad, así que su test es un
  test de la decisión, no de una utilidad.
- **La ventana de huérfano se documenta en la IG**, no se esconde: un `ServiceRequest` sin `Specimen`
  es un estado transitorio legítimo del sistema mientras el reproceso no ha corrido.

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

### Decisiones tomadas al cerrar el hito (ítem 16)

- **2026-08-06 — Un volante con una muestra rechazada no se puede informar, y se asume.** El
  invariante completo de §10 bloquea la emisión mientras quede una línea sin resultado, y una muestra
  rechazada no produce ninguno (C6). La salida sería **anular la línea**, que es lo que hace un
  laboratorio de verdad, y el dominio todavía no sabe: `Peticion` no tiene estado. Se deja bloqueado a
  propósito —esperar la nueva extracción es lo que corresponde clínicamente— y la anulación
  (`ServiceRequest.status = revoked`) se planifica en el hito 2. Bloquear es el lado seguro: el
  peligro que persigue el invariante es emitir de más, no de menos.
- **2026-08-06 — Los rangos de referencia dejan de ser una tabla.** No basta con sacarlos a un fichero
  común y seguir sembrando la tabla desde él: una tabla cuyo único escritor es una migración es un
  fichero de configuración con pasos de más. El puerto pasa a llamarse `CatalogoDeRangosDeReferencia`
  —no es un repositorio, porque nada del sistema escribe ahí— y `V6` borra la tabla. Las dos garantías
  que daba PostgreSQL (límites ordenados, un solo rango por prueba y sexo) se comprueban al leer, en
  los dos lenguajes: validar la propia entrada no es duplicar lógica.
- **2026-08-06 — El fichero compartido vive en el árbol del backend, no en una carpeta neutra.** El
  laboratorio es la autoridad sobre sus propios rangos y el generador es un consumidor, igual que la
  guía publica el catálogo y el generador lo lee. Una carpeta neutra dejaría el fichero sin dueño y su
  ruta fuera del `paths:` de cualquier *workflow*. Con esta decisión hay que **declarar el
  acoplamiento en la CI**: `ci-simuladores` vigila también
  `backend/src/main/resources/laboratorio/**`, porque un fichero compartido cuyo cambio no ejecuta
  ninguna prueba es peor que dos copias.
- **2026-08-06 — Los rangos numéricos del fichero se escriben con la precisión con la que se informa
  la prueba.** Al unificar las dos copias había que elegir entre `4`–`11` (lo que decía Python) y
  `4.0`–`11.0` (lo que decía el SQL). LEU, HB y HTO se informan con un decimal, así que el rango
  publicado también lo lleva: un rango con menos precisión que el valor que acota se lee como si el
  laboratorio midiera peor de lo que mide.
- **2026-08-06 — Las decenas de millón de los contadores de test se han agotado.** Cada clase de test
  de integración arranca en su propia decena para no chocar de NHC, y las nueve están usadas. El NHC
  son **exactamente ocho dígitos**, así que seguir la serie con `100_000_000` lo rechaza el propio
  dominio — que es como se descubrió. La clase nueva arranca en `95_000_000`; a partir de aquí hay que
  repartir dentro de las decenas, no detrás de ellas.

### Decisiones tomadas al escribir la web profesional (ítem 14)

- **2026-08-05 — La web busca con `POST [tipo]/_search`, no con `GET [tipo]?…`.** Los criterios
  llevan el número de historia del paciente, y una URL con eso dentro se queda en cuatro sitios de
  los que no se borra. El servidor admite las dos formas —el criterio C8 pide la de `GET` y sigue
  probada—; la web usa la que no expone el dato.
- **2026-08-05 — El backend respeta las cabeceras `X-Forwarded-*` (`ApacheProxyAddressStrategy`).**
  Firmaba el enlace de la página siguiente con la dirección por la que le llegó la petición, que
  detrás de un proxy no es la del cliente. El cliente no puede corregirlo porque para él esa URL es
  opaca, así que el fallo aparecería **solo al pasar de la primera página**.
- **2026-08-05 — El backend arranca en local sin Docker con el PostgreSQL de los tests**
  (`./mvnw spring-boot:run -Parranque-local`). Registrado como
  `docs/adr/adr-0013-arrancar-en-local-sin-docker-con-el-postgres-de-los-tests.md`, con la trampa
  del `useTestClasspath` que no añade las clases de `src/test`. Esperar al `docker compose` del ítem
  15 para ejercitar la web era el plan y estaba mal: habría dejado toda la interfaz escrita y sin
  probar hasta el final del hito.

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
  ~~**Pendiente que afecta al ítem 15:** `docker compose up` (C12) sí necesita Docker; hay que
  instalarlo antes de llegar ahí.~~ — **resuelto el 2026-08-05:** Docker instalado **dentro de la
  distro WSL2** que ya existía en el equipo (`docker.io` + `docker-compose-v2` de Ubuntu 26.04), sin
  Docker Desktop. Menos invasivo —ni UAC, ni licencia, ni primer arranque manual de una GUI— y da el
  mismo `docker compose`. Los puertos publicados se ven en `localhost` desde Windows. **Los tests
  siguen con el PostgreSQL embebido:** que ahora haya Docker no cambia la decisión, porque lo que la
  motivó —que un ciclo de test no dependa de levantar contenedores— sigue valiendo.
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

### De dónde se parte — hito 1 cerrado

**HITO 1 CERRADO (2026-08-06). Los 17 ítems, del 0 al 16.** Los 12 criterios de aceptación de §14
están verificados —los diez que se pueden automatizar, con test; los otros dos, contra la pila del
`compose`—, los seis *workflows* de CI han corrido y están en verde, y las dos deudas que quedaban
anotadas están saldadas. La guía de implementación está **publicada en
`https://aojeda006.github.io/HispaLIS/`** (las páginas cuelgan de `/es/`; la raíz redirige por
JavaScript), el circuito completo funciona de extremo a extremo desde un clon recién hecho, y los
cinco invariantes de §10 que el hito 1 alcanza viven en el núcleo de dominio, con sus tests.

| Componente | Estado | Verificado con |
|---|---|---|
| `ig/` | 9 perfiles, extensión `codigo-ine`, `CodeSystem` de 21 pruebas, `ConceptMap` a LOINC, 4 `ValueSet` y 18 ejemplos — **publicada** | `npx fsh-sushi .` → **0 errores, 0 warnings**; en CI, IG Publisher y validador oficial **en verde**; sitio desplegado comprobado (19 enlaces de la portada, `lang="es"`, los tres avisos) |
| `backend/` | Servidor JPA empotrado · **los cinco agregados del hito 1** sobre el esquema `dominio` con Flyway · circuito completo `Patient` → `ServiceRequest` → `Specimen` → `Observation` → `DiagnosticReport` · concurrencia optimista con `If-Match` → `412` · búsqueda filtrada y paginada por `Bundle.link` · los siete caminos de error, cada uno con su código y su `OperationOutcome` · el resultado conserva cuándo se midió, quién lo hizo y entre qué cifras es normal · **búsqueda por `POST _search` sin datos del paciente en la URL**, enlace de paginación válido detrás de un proxy, **la transacción ya no se salta el núcleo** y **el informe no sale con el volante a medias** | `./mvnw verify` → **BUILD SUCCESS, 73 tests**; validador oficial sobre lo que publica el circuito → **0 errores** |
| `web-profesional/` | Angular 22.1 + vitest + angular-eslint · capa de presentación FHIR · **cliente HTTP** (búsqueda por `POST _search`, paginación por el enlace del servidor, errores traducidos del `OperationOutcome`) · **alta de petición y consulta de informe** con sus ViewModels | `npm run lint`, `npm test` (**66 tests**), `npm run build`; API recorrida en vivo por el proxy, primero con `-Parranque-local` y después contra el `compose` |
| `simuladores/` | **Generador de datos sintéticos completo**: terminología leída de la guía, **rangos de referencia leídos del fichero que publica el laboratorio**, identificadores españoles con dígito de control, paneles correlacionados, reflejas y muestras rechazadas | `ruff check`/`format`, `pytest` → **77 tests**; validador oficial sobre el corpus generado → **0 errores** |
| `infra/` | **`compose` del hito 1**: PostgreSQL 14 + backend + web tras nginx, encadenados por *healthcheck* | `docker compose … up` desde una copia limpia del árbol commiteado, y el circuito recorrido de extremo a extremo contra la pila |
| `integracion/`, `app-ciudadano/` | **Sin andamiar a propósito** — se andamian en los ítems 20 y 38 | conservan su guarda de auto-omisión, y es lo único que su CI ejercita |

> **La CI está en verde en los seis workflows** y el filtrado por ruta está comprobado en los dos
> sentidos. `integracion` y `app-ciudadano` **solo han corrido a mano** (`workflow_dispatch`) y lo que
> ejercitan es su **guarda de auto-omisión**: sin `pom.xml` ni `pubspec.yaml` avisan y no construyen
> nada. **Retirar esa guarda es lo primero al andamiarlos** (ítems 20 y 38). Se empuja a `origin/main`
> por **SSH**: el PAT de HTTPS no tiene *scope* `workflow` y GitHub rechaza el push de
> `.github/workflows/`.

### Dónde estamos ahora — hito 2, ítem 17

El hito 2 está **planificado y sin empezar**: 25 ítems, del 17 al 41, con la sección de
*Prerrequisitos operativos* justo antes del checklist. La decisión que lo bloqueaba ya está tomada
—**D22**, la puerta transaccional sigue cerrada y la atomicidad la pone el reproceso idempotente— y
`docs/diseno.md` está en **v1.1**, con las dos correcciones factuales del hito 1 marcadas como
añadidos.

**El primer ítem no completado es el 17 — anulación de línea de petición**, y no es casualidad que
sea ese: al completar el invariante del informe, el hito 1 dejó un volante con una muestra rechazada
**bloqueado para siempre**, porque `Peticion` no tiene estado. Los tres huecos de dominio (17, 18 y
19) van **antes del puente** porque el puente los usa: el `OML^O21` necesita poder anular, el
`ORU^R01` saliente cuelga del resultado validado, y el bus no puede publicar nada sin el `outbox`.

**Nada de esto se ha adelantado.** `integracion/` y `app-ciudadano/` siguen sin andamiar, y el
`compose` sigue siendo el de tres servicios.

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

- [x] **11 — Búsqueda y paginación. (C8)** — *hecho el 2026-08-05.*
  `BusquedaPaginadaTest`: doce glucemias de un paciente, pedidas de cinco en cinco, se recorren en
  **tres páginas siguiendo `Bundle.link[relation=next]`** sin duplicados ni faltantes, y el `total`
  que declara el servidor coincide. Alrededor hay ruido —creatininas del mismo paciente y glucemias
  de otro— que el filtro tiene que descartar: **si `patient` o `code` no filtrasen, el total sería 21
  o 17 y el primer test caería**, así que ese ruido es lo que impide que la prueba se apruebe sola.
  *Este sí vino heredado*, y el proveedor de paginación contra base de datos ya estaba cableado
  desde el ítem 6. Lo que había que decidir es qué se prueba: no que el `Bundle` llegue, sino que la
  URL de la página siguiente se trate como **opaca**. Lleva el identificador de la búsqueda cacheada,
  no un desplazamiento calculable; un cliente que se invente `&_getpagesoffset=…` funciona hasta que
  el servidor cambia de estrategia y entonces se salta resultados en silencio.
  *Y una comprobación que no pide el criterio:* el número de resultados paginados se contrasta con
  `SELECT count(*) FROM dominio.resultado`. La búsqueda se sirve de la proyección —es su sitio, §9,
  cero mapeo en lectura—, pero la proyección solo vale lo que valga su acuerdo con el núcleo. Es la
  lección del ítem 10 aplicada aquí: lo que se separa en silencio hay que atarlo con un test.
  *Criterio:* `GET /fhir/Observation?patient=…&code=…` devuelve un `Bundle` paginado y el test
  recorre las páginas **siguiendo `Bundle.link[relation=next]`**, nunca construyendo la URL a mano.

- [x] **12 — Errores en `OperationOutcome`. (C9)** — *hecho el 2026-08-05.*
  `ErroresEnOperationOutcomeTest`, siete casos por la API: JSON truncado → `400`; paciente sin NHC
  → `400`; recurso inexistente → `404`; NHC repetido → `409`; `If-Match` de una versión que no es la
  vigente → `412`; informe vacío → `422`; y `PUT` sobre una muestra ya registrada → `422`.
  *Cada caso comprueba tres cosas, no una:* el código exacto, que el cuerpo sea un
  `OperationOutcome` con `severity = error`, y que el `Content-Type` sea `application/fhir+json`
  —un error en texto plano obliga al cliente a leerlo con los ojos—. Y antes que nada, **que no sea
  `2xx`**: parece redundante teniendo el código exacto justo debajo, pero es exactamente el fallo que
  persigue el criterio, porque un `200` con errores dentro tiene toda la apariencia de haber
  funcionado.
  *El séptimo caso no lo pedía el criterio:* es el rechazo del `PUT` que introdujo el ítem 10 en los
  cuatro recursos sin reglas de modificación. Era el único camino de escritura que no recorría ningún
  test, y si la traducción de errores no lo alcanzase saldría un `500` sin que nadie se enterara.
  *Criterio:* recurso mal formado → `400`; no encontrado → `404`; conflicto de versión → `412`;
  violación de invariante → el código que corresponda; **siempre** con cuerpo `OperationOutcome` y
  **nunca** un `200` con el error dentro. Test por cada caso.

### Los clientes y el arranque

- [x] **13 — Generador de datos sintéticos. (C11)** — *hecho el 2026-08-05.*
  Siete módulos en `simuladores/generador/` y 63 tests nuevos (7 → 70). `python -m generador --seed 42`
  produce 100 pacientes, ~126 episodios y ~1.000 recursos; **el validador oficial de HL7 los da por
  conformes con 0 errores**, y la CI de simuladores lo comprueba en cada empujón.
  *La terminología se lee, no se copia (D15).* Consume los mismos `CodeSystem-catalogo-pruebas` y
  `ConceptMap-catalogo-a-loinc` que publica la guía. Como no están versionados —los produce SUSHI—,
  **el generador se niega a arrancar si faltan** y dice qué ejecutar: arrancar con un catálogo a
  medias produce un corpus que parece bueno y no lo es. Dos tests cruzan los códigos de los paneles
  y de los rangos contra el catálogo, y los tipos de muestra contra el `ValueSet`: es la puerta por
  la que se colaría una lista paralela, y queda cerrada por comprobación y no por buena voluntad.
  *Los casos obligatorios se garantizan, no se esperan.* De un generador aleatorio no se obtiene una
  garantía sino una probabilidad, así que los primeros pacientes de toda ejecución son los casos que
  rompen sistemas: `MUÑOZ`, `ÁLVAREZ` y `PEÑA` para el charset, y «de la Torre Gómez» y «Fernández
  de Córdoba Ruiz» para el heurístico de partir por el espacio — el test comprueba que el primer
  apellido de «de la Torre Gómez» es «de la Torre» y no «de».
  *Y donde de verdad se falla:* la letra del NIE se calcula sustituyendo la inicial por su dígito
  (`X`→0, `Y`→1, `Z`→2). Quien se salte ese paso acierta con las `X` y falla con dos tercios del
  corpus, así que hay un test que exige que salgan las tres iniciales y que las tres validen.
  *Resultados que se comportan como resultados:* se piden por paneles y no de uno en uno, el
  hematocrito cuadra con la hemoglobina (regla de los tres), el rango de referencia depende del sexo
  en la serie roja, una TSH alta dispara una T4 libre enlazada con `Observation.triggeredBy` —nuevo
  en R5—, y una de cada diez muestras llega rechazada y no produce resultados, para que el corpus
  ejercite el invariante C6 y no solo el camino feliz.
  *La reproducibilidad la fijan tres parámetros, no uno:* semilla, pacientes y **fecha**, porque la
  actividad se reparte hacia atrás desde ese día. Un test compara dos volcados completos y otro
  —el control negativo— comprueba que con otra semilla la salida cambia: sin él, un generador que
  devolviera siempre lo mismo aprobaría el primero con matrícula de honor.
  *Criterio:* `python -m generador --seed 42` produce pacientes con **apellidos dobles** (incluidos
  casos como `"de la Torre Gómez"`), **DNI/NIE con dígito de control válido** y **NUHSA con formato
  `AN` + 10 dígitos**; **`MUÑOZ`, `ÁLVAREZ` y `PEÑA` entre los casos**; una parte de los pacientes
  **sin NUHSA ni CIP-SNS** (el caso real de un privado). Consume el **mismo** `CodeSystem`/`ConceptMap`
  que la IG, no una lista paralela. Salida **reproducible** con la misma semilla, y todos los recursos
  generados **validan** contra su perfil.

- [x] **14 — Web profesional en Angular. (C10)** — *hecho el 2026-08-05.*
  **Hecho:** los dos prerrequisitos del backend, la **capa de presentación**, el **cliente HTTP** y
  **las dos pantallas**, con sus ViewModels. `web-profesional`: **66 tests**, lint y build en verde;
  cada pantalla es un *chunk* aparte. La web **no tiene datos propios**: todo sale de la API o del
  catálogo de la guía — no hay ningún *mock* en el código de la aplicación.
  *La capa de presentación* (`src/app/fhir/`): tipos R5 mínimos, `mensajeDeError` a partir del
  `OperationOutcome`, y `apellidos` / `valorConUnidad` / `rangoDeReferencia`. El mensaje de error
  sale del `OperationOutcome` y no de una tabla de códigos HTTP —el servidor ya explicó qué pasó en
  términos del negocio—, pero solo si lo recibido **tiene forma** de `OperationOutcome`, porque un
  proxy de empresa devuelve HTML con la cabecera de la petición original. Los apellidos se muestran
  enteros y su descomposición sale de las extensiones. Y el rango que se enseña es el del **sexo**
  del paciente, elegido aquí porque la proyección no lo conoce: si el sexo no consta no se elige
  ninguno, que enseñar el de hombre a un paciente sin sexo registrado es inventarse un dato clínico.
  *El cliente HTTP y las tres decisiones que lleva dentro:*
  **(1) se busca con `POST [tipo]/_search`, no con `GET [tipo]?…`.** Los criterios llevan el número
  de historia, y una URL con eso dentro se queda en la barra del navegador, en su historial, en el
  log del proxy y en la traza del servidor — los cuatro sitios que el invariante 6 del proyecto
  prohíbe, y de los que no se borra. FHIR previó el caso y admite los criterios en el cuerpo; el
  backend gana `BusquedaSinPhiEnLaUrlTest`, que comprueba que lo acepta **y que el enlace de
  paginación que devuelve tampoco reintroduce el identificador**.
  **(2) la página siguiente se pide con la URL del servidor, tal cual**, y el alta devuelve lo que el
  servidor publicó y no lo que se le mandó: si contesta sin cuerpo, se lee del `Location`, que es el
  *read-your-writes* del §9 ejercitado de verdad.
  **(3) el catálogo de pruebas se lee, no se escribe (D15).** La pantalla de alta ofrece las pruebas
  del **mismo** `CodeSystem` que publica la guía, traído por `scripts/traer-terminologia.mjs`; hasta
  el `system` sale del fichero. Y los `Identifier.system`, que sí hay que repetir porque un navegador
  no lee FSH, los cruza un test contra `aliases.fsh`.
  *Y una trampa que no se ve hasta la segunda página:* el servidor firma
  `Bundle.link[relation=next]` con la dirección por la que le llegó la petición, así que detrás de un
  proxy —que es como lo alcanza el navegador, siempre— apuntaría a una máquina que el navegador no
  resuelve. El cliente **no puede corregirlo**, porque para él esa URL es opaca. Se cierra por los
  dos lados: `ApacheProxyAddressStrategy` en el backend y `"xfwd": true` en `proxy.conf.json`.
  *Y sí se ha ejercitado contra el servidor de verdad*, que era lo que parecía imposible sin Docker:
  `./mvnw spring-boot:run -Parranque-local` levanta el backend con el **mismo PostgreSQL embebido de
  los tests**, y con `npm start` delante se recorre la API por el proxy tal y como la recorre la web.
  Comprobado así: alta de paciente (`201` + `Location`), NHC repetido (`409`), búsqueda por
  `POST _search` con el NHC en el cuerpo, **`MUÑOZ de la Torre ÁLVAREZ` intacto de ida y vuelta**,
  las dos líneas de petición con `code.concept` de R5, el informe con `_sort=-issued`, los rangos de
  referencia —uno común para la glucosa y **dos por sexo** para la hemoglobina, que es justo la
  elección que hace la pantalla—, el error sin NHC como `400` con su `OperationOutcome` en
  `application/fhir+json`, y **el enlace `next` devuelto como `http://localhost:4200/fhir?…`**, que
  es la comprobación que de verdad importaba: el navegador puede seguirlo.
  Repetido después **contra la pila del `compose`** (ítem 15), que es la que pide el criterio.
  **Lo único no verificado es la interfaz pulsada en un navegador**, que aquí no hay forma de
  automatizar: las plantillas las compila y comprueba el build de Angular, los ViewModels tienen sus
  tests, y todo lo que las pantallas piden a la API está ejercitado en vivo por el mismo camino que
  recorre el navegador.
  *Criterio:* alta de petición y consulta de informe funcionando **contra la API FHIR real** (sin
  *mocks*); los errores se muestran a partir del `OperationOutcome`; los apellidos se muestran sin
  partir por el espacio; el valor se presenta siempre con **unidad y rango de referencia**.

- [x] **15 — `docker compose up` levanta el circuito. (C12)** — *hecho el 2026-08-05.*
  Tres servicios encadenados por *healthcheck*: PostgreSQL, el backend y la web tras un nginx que
  sirve la SPA y hace de proxy de `/fhir`. `docker compose … up` deja la pila en pie y el circuito
  entero recorrido contra ella —paciente, facultativo, las dos líneas de petición, muestra,
  resultados e informe—, con `MUÑOZ de la Torre ÁLVAREZ` intacto, los rangos por sexo y el error sin
  NHC como `400` en `OperationOutcome`.
  *La terminología se compila dentro de la imagen.* `ig/fsh-generated/` está en el `.dockerignore`
  **a propósito**, aunque exista en la máquina de quien construye: la imagen de la web ejecuta SUSHI
  ella misma, y así construir desde un clon recién hecho y construir aquí dan lo mismo. Sin eso, el
  build funcionaría en este equipo y fallaría en el de cualquier otro, que es el fallo que este
  criterio existe para cazar.
  *Y las dos cabeceras de nginx que no son decorativas:* `X-Forwarded-Host` y `X-Forwarded-Proto`.
  Sin ellas el enlace de la página siguiente saldría apuntando a `http://backend:8080/fhir?…`, un
  nombre que solo resuelve dentro de la red del compose. Comprobado que sale como
  `http://localhost:4200/fhir?…` y que la segunda página se alcanza.
  *PostgreSQL 14 y no la última:* es la versión con la que corren los tests, y las dos tienen que
  moverse juntas. Con versiones distintas, una migración que use algo de PG15 pasaría en el compose
  y fallaría en los tests — ya ocurrió una vez, con `NULLS NOT DISTINCT`.
  **En Windows no hace falta Docker Desktop:** con WSL2 basta `docker.io` + `docker-compose-v2`
  dentro de la distro. Anotado en el `README.md`.
  *Criterio:* `docker compose -f infra/compose/docker-compose.yml up` arranca **backend + PostgreSQL +
  web** y el circuito del ítem 9 funciona de extremo a extremo contra esa pila, partiendo de un repo
  recién clonado y siguiendo solo el `README.md`.

### Cierre del hito

- [x] **16 — Hito 1 cerrado.** — *hecho el 2026-08-06.*
  **Las dos deudas, saldadas.** El invariante completo del informe de §10 (rojo en `3a9bd7a`, verde en
  `cca8424`) y los rangos de referencia en un fichero de datos común. Las dos están contadas con
  detalle en *Notas / riesgos*, tachadas.
  **Los 12 criterios, verificados uno a uno.** Diez tienen test automatizado; los doce se recorrieron
  además **contra la pila del `compose`**, con un guion de 25 comprobaciones que entra por
  `http://localhost:4200/fhir` —el mismo camino que el navegador, nginx incluido— y que termina
  consultando la base de datos del contenedor para que lo comprobado no dependa de lo que diga la API:

  | | Criterio | Con qué se afirma |
  |---|---|---|
  | C1 | La IG compila y el publisher la genera | `npx fsh-sushi .` → **0 errores, 0 warnings** (9 perfiles, 1 extensión, 4 `ValueSet`, 1 `CodeSystem`, 19 instancias); el IG Publisher, en `ci-ig` |
  | C2 | El validador oficial corre en CI y ningún ejemplo se libra | `ci-ig` valida los 18 ejemplos contra `hl7.fhir.r5.core@5.0.0`; **probado en rojo** rompiendo uno |
  | C3 | `metadata` con `fhirVersion 5.0.0` y sus perfiles | `ConformidadFhirTest` + en vivo: R5 y los 9 perfiles |
  | C4 | Read-your-writes | `AltaDePacienteTest` + en vivo: `201`, `Location`, `ETag W/"1"` y `GET` inmediato |
  | C5 | El circuito completo, conforme | `CircuitoCompletoTest` + validador oficial sobre lo que publica + en vivo, con dos líneas de volante |
  | C6 | Muestra rechazada ⇒ sin resultado | `EspecimenRechazadoTest` y `EspecimenTest` (rojo en `a283f1f`) + en vivo → `422` |
  | C7 | `If-Match` obsoleto → `412` | `ConcurrenciaOptimistaTest` + en vivo: vigente `200`+`W/"2"`, obsoleto `412` |
  | C8 | Búsqueda y paginación por `Bundle.link` | `BusquedaPaginadaTest` + en vivo: 8 resultados de 3 en 3, `next` como `http://localhost:4200/fhir?…` y segunda página alcanzada |
  | C9 | Errores en `OperationOutcome` | `ErroresEnOperationOutcomeTest` (7 casos) + en vivo: `400`, `404`, `409`, `412`, `422`, todos con `OperationOutcome` en `application/fhir+json` |
  | C10 | La web contra la API real | 66 tests de la web; y en vivo, las llamadas que hacen sus dos pantallas, tal y como las hacen |
  | C11 | Generador con apellidos dobles, DNI/NIE y NUHSA | 77 tests + validador oficial sobre el corpus, en `ci-simuladores` |
  | C12 | `docker compose up` levanta el circuito | Pila levantada y recorrida; comprobado **desde una copia limpia del árbol commiteado** |

  **Lo que no está verificado, dicho en claro:** la interfaz **pulsada en un navegador**. Aquí no hay
  forma de automatizarlo. Las plantillas las compila el build, los ViewModels tienen tests, y todo lo
  que las pantallas piden a la API está ejercitado en vivo por el mismo camino que recorre el
  navegador — pero el clic no.
  **Cuatro ADR nuevos** con lo aprendido que sirve fuera de este proyecto: `adr-0014` (un framework
  que también escribe tiene varias puertas), `adr-0015` (los datos de configuración no van en las
  migraciones), `adr-0016` (un identificador de paciente no viaja en la URL) y `adr-0017` (los enlaces
  los firma el servidor, y tras un proxy los firma mal). **La biblioteca no se toca a mitad de
  proyecto**; las aportaciones a `interoperabilidad/hl7-v2/` siguen anotadas para el final (§17.2).
  *Criterio:* los 12 criterios de §14 verificados, CI en verde en los seis workflows, `PLAN.md` y
  `README.md` coherentes con la realidad del repo, y los aprendizajes transversales del hito anotados
  como ADR nuevos (no se toca la biblioteca a mitad de proyecto).

---

## Prerrequisitos operativos del hito 2

> Lo que se sabe que va a doler, escrito antes de tropezar. **No son ítems**: son condiciones que hay
> que cumplir dentro del ítem que las toca.

- **Al andamiar `integracion/` y `app-ciudadano/`, retirar su guarda de auto-omisión en el mismo
  commit**, y **comprobar el bit de ejecución en el índice** de todo script del repositorio que
  ejecute la CI: `integracion/mvnw` y, si el andamiaje de Flutter lo trae,
  `app-ciudadano/android/gradlew`. Es literalmente la trampa de `adr-0008`: NTFS no sostiene el
  atributo, el fichero se commitea como `100644` y el runner de Linux muere con `Permission denied`.
  Se corrige con `git update-index --chmod=+x <ruta>` **antes** del primer empujón, no después de ver
  la CI en rojo. Y un componente andamiado con la guarda puesta es **peor** que uno sin andamiar: la
  CI pasa en verde sin construir nada.
- **Imports de `CLAUDE.md` que faltan**, cada uno cuando llegue su ítem y no antes (importarlos ahora
  es contexto que nadie usa):
  - `interoperabilidad/smart-on-fhir/convenciones.md` y `herramientas/autenticacion.md` en
    **`backend/CLAUDE.md`** al empezar Keycloak (ítem 34). **`app-ciudadano/CLAUDE.md` necesita el
    primero también** — hoy solo importa Dart, Flutter, almacenamiento local, MVVM y UX.
  - `interoperabilidad/terminologia/convenciones.md` en **`backend/CLAUDE.md`** al montar el servidor
    de terminología (ítem 32).
  - **`integracion/CLAUDE.md` ya está completo** —verificado—: importa `hl7-v2`, `integracion`,
    `datos-distribuidos`, Java y Spring. No hay que tocarlo al andamiar.
- ⚠️ **Cruzar la tabla 0354 entre V2.5 y V2.5.1 ANTES de generar código** (ítem 21). Su contenido
  difiere entre las dos versiones y **las dos están archivadas en `_fuente/` de la biblioteca**, así
  que el cruce se hace en local y no se asume equivalencia. Es lo único de §17 que este hito convierte
  en bloqueante.
- **SNOMED CT Edición Española no se redistribuye y no entra en el repositorio.** Requiere registro
  ante el Ministerio de Sanidad (§5). El servidor de terminología se carga con lo archivado en la
  biblioteca (LOINC 2.82, THO 7.3.0) más subconjuntos curados que se traen fuera de banda.
- **El `compose` pasa de tres servicios a ocho** (PostgreSQL, backend, web, motor, Kafka, Schema
  Registry, terminología, Keycloak). Lo que hoy funciona encadenado por *healthcheck* con tres se
  vuelve frágil con ocho, y la máquina de desarrollo tiene un límite. Si no cabe todo a la vez, se
  reparte en **perfiles de compose**; no se quita el *healthcheck*.
- **Verificar contra el `compose` en WSL exige una sola sesión** (ver *Notas / riesgos*): levantar en
  una invocación de `wsl` y comprobar en otra hace que conteste un contenedor de la sesión anterior.
  Con ocho servicios el riesgo crece, no baja.
- **Las decenas de millón de los contadores de NHC de los tests están agotadas** (ver *Decisiones*,
  ítem 16). Toda clase de test de integración nueva tiene que repartirse **dentro** de una decena, no
  detrás: el NHC son exactamente ocho dígitos y `100_000_000` lo rechaza el propio dominio.

---

## Checklist — Hito 2

> Mismo estándar que el hito 1: un ítem = una unidad de trabajo pequeña con **criterio de aceptación
> verificable**, ordenados para que cada uno deje algo demostrable.
> `[ ]` pendiente · `[x]` hecho (cumple criterio + verificado + commiteado).

### Los huecos de dominio — van antes del puente, porque el puente los usa

- [ ] **17 — Anulación de línea de petición.**
  `Peticion` no tiene estado, así que desde que el ítem 16 completó el invariante del informe, **un
  volante con una muestra rechazada queda bloqueado para siempre**. La salida ya está identificada:
  anular la línea, que es lo que hace un laboratorio de verdad.
  *Criterio:* `Peticion` gana estado (`activa | anulada`) con motivo y fecha; el caso de uso
  `AnularLinea` lo cambia **por el núcleo** y la proyección publica `ServiceRequest.status = revoked`
  en la misma transacción. **Test en rojo primero:** volante con dos líneas, una muestra rechazada,
  la línea anulada, y el informe de la otra **se emite** — hoy devuelve `422`. Una línea que **ya
  tiene resultado** no se puede anular → `422` con su `OperationOutcome`. Una línea anulada **no
  cuenta** para el invariante del informe y **no admite** un espécimen nuevo.
  *Trampa:* anular no es borrar. El `ServiceRequest` revocado se sigue publicando y se sigue leyendo;
  lo único que cambia es que deja de bloquear. Borrar la línea dejaría el volante sin rastro de lo
  que se pidió, que es justo lo que el peticionario necesita ver.

- [ ] **18 — Validación facultativa del resultado, con su `Provenance`.**
  *Criterio:* `Resultado` gana estado (`preliminar | validado`) y el caso de uso `ValidarResultado`,
  que exige un facultativo y sella la fecha; la proyección publica `Observation.status`
  `preliminary` → `final` **y un `Provenance`** con `.target` → `Observation`, `.agent.who` →
  `Practitioner` y `.recorded`, escrito en la misma transacción — §6.1 lo mapea así, sin extensión.
  **El informe solo publica resultados validados:** test en rojo con un resultado preliminar dentro
  del alcance → `422`. Validar dos veces el mismo resultado → `422`; corregir uno ya validado es una
  versión nueva, no una revalidación.
  *Por qué aquí y no en el hito 3:* de «resultado validado» cuelgan el `ORU^R01` saliente de este
  hito (ítem 28) y **todo** el notificador EDO del hito 3. Si no entra aquí, el hito 3 empieza
  retocando el núcleo, que es el peor sitio donde empezar un hito.
  *Consecuencia que hay que asumir:* el circuito del hito 1 gana un paso. `CircuitoCompletoTest` y el
  guion de verificación del `compose` se actualizan **en el mismo commit**, o el informe deja de
  emitirse.
  *Nota:* `Provenance` no está entre los nueve perfiles de §6.5 y **no se le escribe uno** salvo que
  aparezca una restricción de negocio que justifique el décimo; §6.1 lo lista como recurso aparte.

- [ ] **19 — Esquema `outbox`, escrito en la misma transacción.**
  *Criterio:* migración que crea el esquema `outbox` y su tabla de hechos (`id`, `tipo`,
  `clave_de_particion`, `carga`, `creado_en`, `publicado_en`); las escrituras del dominio dejan su
  hecho **dentro del mismo `@Transactional`** de §9. Test **por el lado del fallo**, como el del ítem
  7: un alta que el dominio rechaza **no deja fila en `outbox`** — uno del camino feliz pasaría igual
  con dos transacciones.
  *Y lo que de verdad hay que comprobar:* que **el hecho no lleva PHI**. Test que recorre la carga de
  cada tipo de hecho y falla si aparece un NHC, un nombre, un DNI o un NUHSA. El invariante 6 del
  proyecto prohíbe PHI en el bus, y **el sitio donde se incumple es aquí**, construyendo la carga —
  no en Kafka.

### El motor de integración

- [ ] **20 — Andamiar `integracion/` y dejar su CI construyendo de verdad.**
  *Criterio:* `integracion/pom.xml` con Spring Boot 3.5.16 (la misma que el backend, por lo que dice
  la decisión del ítem 1) y HAPI HL7v2 (`ca.uhn.hl7v2`), Spotless enganchado a `verify`, y **la
  guarda de auto-omisión de `ci-integracion.yml` retirada en el mismo commit**. `integracion/mvnw`
  con bit de ejecución en el índice (`git ls-files -s integracion/mvnw` → `100755`). El workflow
  corre **por `push`**, no por `workflow_dispatch`, y **construye**: un `::notice::` de omisión en el
  log es un fallo del ítem.

- [ ] **21 — ⚠️ Cruzar la tabla 0354 entre V2.5 y V2.5.1.**
  *Criterio:* documento corto en `docs/` —ADR si el hallazgo sirve fuera del proyecto— con el **diff
  medido** de la tabla 0354 entre las dos versiones archivadas en `_fuente/`, y la lista de las
  estructuras que este proyecto usa (`ADT_A01`, `ADT_A08`, `OML_O21`, `ORU_R01`) con su código en
  **V2.5.1**, que es la versión que fija D12. Se hace **antes** del primer parser: es una
  comprobación de un rato que evita generar código contra la tabla equivocada.

- [ ] **22 — Almacén de mensajes: el original íntegro y la deduplicación.**
  *Criterio:* todo mensaje que entra se guarda **antes de tocarlo**, tal y como llegó, con metadatos
  indexables (emisor `MSH-3`/`MSH-4`, tipo `MSH-9`, control `MSH-10`, versión `MSH-12`, fecha, estado
  de proceso). Reenviar el mismo mensaje **no produce una segunda escritura**, y la deduplicación
  ocurre **en el motor y antes** de llamar a la API FHIR.
  *La trampa que decide el diseño de la clave:* **`MSH-10` solo es único por emisor.** La clave es
  `MSH-3` + `MSH-4` + `MSH-10`, no `MSH-10` a secas: con dos analizadores que reinician su contador,
  `MSH-10` solo descarta mensajes buenos **en silencio**, que es peor que duplicarlos.

- [ ] **23 — Listener MLLP sobre TLS, con acuses y charset.**
  *Criterio:* el motor escucha MLLP (`0x0B` … `0x1C 0x0D`, que implementa HAPI — nunca se escribe a
  mano) **sobre TLS**, y responde `ACK` con el `MSA-1` que toca: `AA` al aceptar, `AE` ante error de
  aplicación, `AR` al rechazar. Un mensaje que no se puede procesar **produce `AE`/`AR`, nunca
  silencio**: un emisor v2 sin acuse o reintenta indefinidamente o lo da por entregado, y las dos
  cosas son peores que un rechazo. El charset se toma de **`MSH-18`** y se respeta: `MUÑOZ`,
  `ÁLVAREZ` y `PEÑA` **de ida y vuelta**, en `8859/1` y en `UNICODE UTF-8`, son casos obligatorios.
  *Trampa:* leer como UTF-8 un mensaje `8859/1` **no lanza ninguna excepción** — produce `MU?OZ` y
  sigue. El test tiene que comparar la cadena, no comprobar que no hubo error.

- [ ] **24 — `ADT^A01` / `A08` → `Patient`.**
  *Criterio:* un `ADT^A01` da de alta al paciente **por la API FHIR** y un `A08` corrige su
  filiación; los dos aterrizan en `dominio.paciente`, comprobado con SQL y no leyendo la proyección
  —la lección del ítem 10—. Los apellidos llegan **enteros** desde `PID-5` y no se parten por el
  espacio. Un `A08` de un paciente que no existe **no lo crea**: se rechaza con `AE` y queda en el
  almacén con su motivo.
  *Se elige el primero a propósito:* es el único mapeo de un solo recurso, así que estrena el canal
  sin arrastrar el problema de atomicidad de D22.

- [ ] **25 — DLQ y reproceso idempotente.**
  *Criterio:* un mensaje cuyo proceso falla va a la **DLQ** con el error y el original intactos; el
  punto de reproceso lo vuelve a aplicar **entero** y, aplicado dos veces, **no produce dos altas** —
  test explícito, reprocesando el mismo mensaje y contando con `SELECT count(*)` sobre el dominio. La
  DLQ es consultable y el reproceso es una operación del motor, no un script suelto (D11: es lo que
  se pierde al no usar Mirth).
  *Es lo que sostiene D22*, así que va **antes** del `OML^O21`: la atomicidad del par
  `ServiceRequest` + `Specimen` la pone este ítem, no el siguiente.

- [ ] **26 — `OML^O21` → `ServiceRequest` + `Specimen`, recurso a recurso (D22).**
  *Criterio:* un `OML^O21` con varias pruebas produce las **líneas del volante** —que comparten
  `requisition` y avanzan por separado— y su `Specimen`, escritos **uno a uno** contra la API FHIR.
  **Test del fallo intermedio:** si la escritura del `Specimen` falla, el mensaje acaba en la DLQ y el
  reproceso deja el estado completo **sin duplicar** el `ServiceRequest` que sí se había escrito. La
  ventana de huérfano se comprueba y se documenta: es un estado transitorio legítimo, no un fallo.
  *Y el `CapabilityStatement` deja de prometer lo que no cumple:* comprobar qué declara hoy
  `rest.interaction`; si dice `transaction` —que es el valor por defecto de HAPI— el documento del
  que se fía un cliente está prometiendo un verbo que el interceptor de `ADR-0014` rechaza. Con D22
  tomada, eso pasa a ser parte del contrato publicado.
  *Y el número de volante:* aquí lo trae `ORC-4`, así que el apaño de la web —`P<fecha>-<sufijo al
  azar>`, ver *Notas / riesgos*— **no aplica a este camino**: si el HIS emite el número, manda el suyo.

- [ ] **27 — `ORU^R01` entrante → `Observation`.**
  *Criterio:* un `ORU^R01` del analizador produce resultados en el dominio con **valor, unidad UCUM y
  `effective[x]`** desde `OBX-5`/`OBX-6`/`OBX-14`, y respeta el tipo declarado en **`OBX-2`** (`NM`
  numérico, `ST` texto, `CE` codificado) en vez de dar por hecho que es un número. Un `ORU^R01` sobre
  una **muestra rechazada** se rechaza con `AE` y **no crea el resultado**: el invariante C6 vive en
  el núcleo y el motor entra por la API, así que lo tiene que ver igual que la web. Los resultados
  entran como **preliminares** (ítem 18) — el analizador mide, no valida.
  *Trampa:* `OBX-11` no es `Observation.status` sin traducir, y **el catálogo del analizador no es el
  catálogo del laboratorio**. El puente entre los dos es un `ConceptMap` servido por el servidor de
  terminología (ítem 33), nunca una tabla escrita a mano dentro del motor — invariante 4.

- [ ] **28 — `ORU^R01` saliente al HIS cuando el informe se valida.**
  *Criterio:* al emitirse un informe, el motor construye y envía un `ORU^R01` al HIS con los
  resultados validados, y **el envío se dispara desde el hecho del `outbox`**, no desde un `if`
  dentro del caso de uso: con el HIS caído el hecho sigue ahí y se reintenta. Charset y acuse del
  lado emisor comprobados con los tres apellidos de siempre.

### El bus de eventos

- [ ] **29 — Kafka y Schema Registry en el `compose`, con los cuatro tópicos.**
  *Criterio:* `lab.peticiones.v1`, `lab.especimenes.v1`, `lab.resultados.v1` y `lab.informes.v1`
  creados, con su esquema **registrado** y la compatibilidad fijada **hacia atrás** (§11). La
  compatibilidad se **prueba**, no se declara: registrar una versión que la rompe tiene que ser
  **rechazada por el registro**, y hay un test que lo comprueba.

- [ ] **30 — El relay publica el `outbox` en Kafka.**
  *Criterio:* el relay drena la tabla del ítem 19 y publica con **clave de partición = paciente**, de
  modo que los hechos de un mismo paciente **mantienen el orden**; test con dos pacientes
  intercalados. La entrega es **al menos una vez**, así que el consumidor de prueba es idempotente y
  eso se prueba reentregando. **Ningún hecho lleva PHI:** solo referencias
  (`{ pacienteId, peticionId, observationRef }`), y el test del ítem 19 se ejecuta también sobre lo
  que sale del *topic*.
  *Y el caso que de verdad importa:* con el broker **parado**, la escritura FHIR sigue devolviendo
  `201` y el hecho queda en el `outbox`; al arrancar el broker se publica. Si un `POST` falla porque
  Kafka está caído, el outbox no está haciendo su trabajo — que es exactamente para lo que está.

### Recuperación

- [ ] **31 — Reconciliador dominio → proyección, como vía oficial.**
  *Criterio:* una operación que recorre el dominio y **regenera la proyección**, con su test: se
  corrompe la proyección a propósito —se borra un `Observation` y se altera otro— y al reconciliar,
  dominio y proyección vuelven a coincidir. Detecta **las dos** divergencias, no una: lo que falta en
  la proyección **y lo que sobra en ella sin agregado detrás**, que es la forma que tenía el
  incidente del `Bundle transaction`. Es idempotente: reconciliar dos veces no cambia nada. §15 lo
  pide como **vía de recuperación oficial**, no como script de emergencia, así que va con su prueba y
  su documentación.
  *La decisión que hay que tomar y escribir:* reescribir por la DAO **incrementa `versionId`** y deja
  obsoletos los `ETag` de todos los clientes. O se preservan las versiones, o se acepta el salto y se
  documenta — una vía oficial que rompe la concurrencia optimista sin avisar no es una vía oficial.

### Terminología

- [ ] **32 — Servidor de terminología en el `compose` (D14).**
  *Criterio:* servidor de terminología de HAPI como **servicio aparte**, cargado con LOINC 2.82 y THO
  7.3.0 (archivados en la biblioteca), subconjuntos curados de SNOMED español y **el `CodeSystem` y
  el `ConceptMap` del catálogo local**. El backend lo consulta **por URL configurable**, y cambiarla
  por la de otro servidor es el único cambio necesario: es el argumento con el que se tomó D14 y hay
  que poder demostrarlo, no solo afirmarlo. Ningún fichero de terminología licenciada en el repo.

- [ ] **33 — Los tres contratos, y la web deja de empaquetar el catálogo.**
  *Criterio:* `$expand`, `$validate-code` y `$translate` funcionando, con test por cada uno:
  `$expand` del `ValueSet` de pruebas del catálogo, `$validate-code` **rechazando** un código que no
  está, y `$translate` devolviendo el LOINC de un código local por el `ConceptMap` publicado —
  incluidas las cinco correspondencias que **no** son equivalencia (`source-is-broader-than-target`),
  que son las que un mapeo ingenuo aplana. La web profesional pide el catálogo con `$expand` en vez
  de congelarlo en el build, lo que **cierra la deuda** anotada en *Notas / riesgos*. Y se recuperan
  los **códigos SNOMED del SNS en `identifier.type`** (`1551000122105`, `1571000122102`,
  `22851000122109`) que el ítem 3 tuvo que dejar fuera porque `tx.fhir.org` no los sirve.
  *Trampa:* los parámetros de `$translate` **cambian de nombre en R5**, en la misma línea que el
  renombrado de `ConceptMap` que registra §2.1 ➕. Verificar contra el paquete canónico antes de
  escribir el cliente: cualquier ejemplo de R4 que se copie va a fallar.

### Seguridad

- [ ] **34 — Keycloak en el `compose` y `.well-known/smart-configuration`.**
  *Criterio:* Keycloak levantado con su *realm* **como código** —no configurado a mano en la
  consola—, y el backend publicando `/.well-known/smart-configuration` con los *endpoints* de
  autorización y token, las `capabilities` que soporta y los métodos de autenticación de cliente. El
  `CapabilityStatement` declara la seguridad con sus `oauth-uris`. Comprobado **desde fuera**, con
  `curl`, no leyendo la configuración.
  *Trampa registrada por adelantado:* **Keycloak no habla SMART de fábrica.** El contexto de
  lanzamiento —el `patient` que acompaña al token— no es OIDC estándar y necesita un *mapper*; y el
  parámetro `aud`, que SMART exige que apunte a la base FHIR, Keycloak no lo valida por su cuenta.
  Las dos cosas hay que construirlas y probarlas.

- [ ] **35 — Los scopes SMART gobiernan de verdad.**
  *Criterio:* los tres tipos de §7 se aplican: `user/*.rs` para la web del profesional, `patient/*.rs`
  **acotado al paciente del contexto**, y `system/*.r` para los clientes no humanos. **Test por el
  lado de la negativa**, que es el único que prueba algo: un token `patient/` de un paciente **no**
  alcanza los resultados de otro → `403` con su `OperationOutcome`, y un token de solo lectura no
  escribe. Sin token, la API deja de responder a lo que hoy responde.

- [ ] **36 — El motor se autentica como cliente `system/` — D5, cerrada del todo.**
  *Criterio:* el motor obtiene su token por **SMART Backend Services** (`client_credentials` con JWT
  firmado) y todas sus escrituras llevan `Authorization`.
  *Deuda declarada dentro del hito:* hasta este ítem **el motor escribe sin token**. Está dicho aquí
  para que no se olvide — D5 no está cumplida del todo mientras este ítem siga abierto.

- [ ] **37 — La web profesional pasa a EHR launch.**
  *Criterio:* la web deja de hablar con la API a pecho descubierto y arranca por el flujo de
  lanzamiento SMART, con su token y su contexto; la sesión caduca y se renueva **sin que el usuario
  pierda lo que estaba haciendo**. Los tests de la web siguen en verde y el `compose` sigue
  levantando el circuito entero.

### La app del ciudadano

- [ ] **38 — Andamiar `app-ciudadano/`.**
  *Criterio:* `app-ciudadano/pubspec.yaml` con el proyecto Flutter, `flutter analyze` y `flutter test`
  en verde, **guarda de auto-omisión de `ci-app-ciudadano.yml` retirada en el mismo commit**, y el
  bit de ejecución comprobado en cualquier script que la CI ejecute (`android/gradlew` si el
  andamiaje lo trae). Igual que el ítem 20: un `::notice::` de omisión en el log es un fallo del ítem.

- [ ] **39 — SMART standalone + PKCE.**
  *Criterio:* el ciudadano se autentica desde la app —cliente **público**, sin secreto, PKCE con
  `S256`— y obtiene un token con contexto de paciente. El token **no se guarda en claro**:
  almacenamiento seguro de la plataforma. Probado contra el Keycloak del `compose`, no contra un doble.
  *Trampa de entorno:* en el emulador de Android `localhost` es el emulador, no la máquina —el host
  es `10.0.2.2`—, así que un `redirect_uri` o un `aud` apuntando a `localhost` falla ahí y funciona
  en todas partes. Y el retorno de la autorización necesita esquema propio o *app link* declarado en
  la plataforma.

- [ ] **40 — La pantalla de informes del ciudadano, contra la API real.**
  *Criterio:* el paciente ve **sus** informes, con los resultados presentados con **unidad y rango de
  referencia** y los apellidos enteros —`MUÑOZ`, `ÁLVAREZ` y `PEÑA` legibles en el dispositivo—. Sin
  *mocks* en el código de la aplicación: todo sale de la API. Y **solo los suyos**: el mismo test
  negativo del ítem 35, ahora desde el cliente.

### Cierre del hito

- [ ] **41 — Hito 2 cerrado.**
  *Criterio:* el circuito v2 recorrido de extremo a extremo contra el `compose` —`ADT` → `OML` →
  `ORU` → validación facultativa → informe → `ORU` saliente—, con los hechos apareciendo en Kafka y
  **sin PHI en ellos**; los ocho servicios levantados con un solo comando; **los seis workflows en
  verde y los seis construyendo de verdad**, sin auto-omisiones; `PLAN.md`, `README.md` y
  `docs/diseno.md` coherentes con la realidad del repo; y los aprendizajes transversales anotados
  como **ADR nuevos** — la biblioteca no se toca a mitad de proyecto. Repasar además, uno a uno: que
  **D5 esté cumplida** (ítem 36), que la puerta de `ADR-0014` siga cerrada y que el invariante 6
  —nunca PHI en el bus— tenga su test.

---

## Hito 3 — lo que solo existe en R5, lo masivo y lo legal (esbozo)

- `SubscriptionTopic` + `Subscription` con el tópico `resultado-validado`.
- `Observation.triggeredBy` para las **reflejas** (TSH alterado → T4 libre), repeticiones y re-ejecuciones.
- **Notificador EDO** a SVEA/Redalerta: resultado validado cuyo código está en el catálogo EDO ⇒
  notificación obligatoria (`Task`). Obligación legal real, también para privados.
- **Bulk Data** `$export` + `Group` para vigilancia epidemiológica, vía SMART Backend Services.
- `AuditEvent` completo (justificación de trazabilidad de D17). El `Provenance` de **quién validó el
  resultado** ya no espera aquí: entra en el hito 2, ítem 18, porque de él cuelga el notificador EDO.
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
  oficial**, no como script de emergencia. **Planificado: ítem 31**, con su prueba y con la decisión
  pendiente de qué hacer con el `versionId` al reescribir.
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
- ~~**El resultado se publica sin `effective[x]` ni `performer`**~~ — **cerrado el 2026-08-05**, antes
  del ítem 14 como estaba previsto. Objeto de valor `Medicion` en el agregado (los dos datos viajan
  juntos porque describen el mismo hecho), migración `V4`, y el circuito los publica. Siguen siendo
  **opcionales**, que es lo que dice el perfil —rechazarlos sería que el servidor contradijera a su
  propia guía—, pero **no se inventan**: rellenar la fecha con la hora de registro coloca un
  resultado de ayer entre los de hoy. Una fecha en el **futuro** sí se rechaza con `400`: es un
  analizador con el reloj mal puesto, y su efecto es que el resultado se lee como el más reciente.
- ~~**El resultado se publica sin `referenceRange`**~~ — **cerrado el 2026-08-05**, el otro
  prerrequisito del ítem 14. Tabla `dominio.rango_de_referencia` sembrada en la migración `V5` y
  publicada por la proyección. Van en la base de datos y **no en la guía** a propósito: los códigos
  de prueba son terminología compartida (D15), pero los rangos dependen del método y del analizador
  de cada laboratorio — dos laboratorios que usan el mismo `CREA` publican rangos distintos sin
  contradecirse.
- ~~**Los rangos de referencia están escritos dos veces**~~ — **cerrado el 2026-08-06** (ítem 16).
  Los dos leen ahora `backend/src/main/resources/laboratorio/rangos-de-referencia.json`, y la tabla
  `dominio.rango_de_referencia` **desaparece** (`V6`): sembrarlos con un `INSERT` en una migración es
  justamente lo que los convirtió en esquema, y a partir de ahí copiarlos en Python fue el camino
  corto. Registrado como `docs/adr/adr-0015-los-datos-de-configuracion-no-van-en-las-migraciones.md`.
  **Ya habían divergido, y en silencio:** una copia escribía los límites como enteros y la otra con
  un decimal, así que el mismo rango salía publicado como `4`–`11` o como `4.0`–`11.0` según quién lo
  escribiera. Con LEU, HB y HTO informadas a un decimal, la forma correcta era la segunda. Comprobado
  que el resto del corpus **no cambia ni un valor** con la misma semilla. El test nuevo del backend
  vale más que los dos que sustituye: cruza cada rango contra el FSH del catálogo y exige que esté
  **en la misma unidad** en la que el laboratorio emite esa prueba — una glucosa de 92 mg/dL contra un
  rango en mmol/L sale marcada como altísima y las dos cifras son correctas.
- ~~**Un `Bundle` de tipo `transaction` se salta el dominio.**~~ — **cerrado el 2026-08-05**, y el
  rojo está en el historial: la transacción devolvía `201 Created` con `Patient/1001`, un id numérico
  de HAPI que no es el UUID de ningún agregado, sin NHC validado y sin fila en `dominio.paciente`. La
  causa es que el procesador de transacciones de HAPI escribe **llamando a las DAO directamente** y
  no pasa por los `ResourceProvider` propios, así que era una segunda puerta de escritura abierta
  contra el invariante 3. La cierra `EscrituraSoloPorElNucleo`, un interceptor sobre
  `STORAGE_TRANSACTION_PROCESSING`. Dos detalles que costarían un rato: los recursos protegidos se
  **deducen de los proveedores propios registrados**, no de una lista escrita a mano, así que dar de
  alta un proveedor nuevo lo protege solo; y el interceptor va en el `IInterceptorService` del
  almacenamiento y **no** en el del `RestfulServer` —los puntos `STORAGE_*` los dispara la capa JPA—,
  porque registrarlo en el sitio equivocado no da ningún error: simplemente no se llama nunca.
  Una transacción de solo datos maestros (`Organization`, `Practitioner`) **se sigue admitiendo**:
  no tienen agregado y no se salta nada.
- **El número que agrupa las líneas de la petición lo inventa el cliente.** La API lo exige dentro
  del recurso (`ServiceRequest.requisition`) y el servidor no lo emite, así que la web genera
  `P<fecha>-<sufijo al azar>`. En un SIL real lo daría un contador del laboratorio; aquí el sufijo
  solo hace improbable —no imposible— que dos mostradores mezclen dos volantes en uno. **El camino v2
  no lo usa** (ítem 26): allí el número viene en `ORC-4` y manda el del HIS. Si aparece un emisor de
  números propio, el apaño de la web se retira.
- **El catálogo de pruebas llega al navegador empaquetado en el build.** Es el mismo `CodeSystem` de
  la guía (D15), no una lista paralela, pero se congela al construir la web: añadir una prueba al
  catálogo obliga a reconstruirla. En el hito 2, con el servidor de terminología, pasa a pedirse con
  `$expand` y el problema desaparece; hasta entonces es lo más cercano a la fuente que puede hacer un
  cliente que no tiene servidor de terminología al que preguntar. **Se cierra en el ítem 33.**
- ~~**El invariante completo del informe está a medias.**~~ — **cerrado el 2026-08-06** (ítem 16), y
  el rojo está en el historial (`3a9bd7a`): un volante con glucosa y creatinina, solo la glucosa
  informada, y el `DiagnosticReport` con esa sola glucosa devolvía `201`. Es el más dañino de los tres
  casos y el único que no se ve: trae resultados, correctos y del paciente correcto, así que el
  peticionario lo lee como la respuesta a lo que pidió y **deja de esperar lo que falta**.
  El alcance se reconstruye en dos saltos, y el primero es el que importa: de las líneas que citan los
  resultados se sube **al número de volante**, y de ahí se bajan *todas* sus líneas. Sin ese rodeo solo
  se verían las que ya tienen resultado —las que nunca bloquean nada—, el invariante quedaría siempre
  satisfecho y no habría forma de notarlo. Se busca por número **y paciente**, porque
  `numero_de_peticion` no es único a propósito y hoy lo genera el cliente.
  **Consecuencia asumida:** con una muestra rechazada el volante no se puede informar hasta la nueva
  extracción. Es lo que corresponde clínicamente; la salida rápida sería **anular la línea**, y eso es
  hito 2 (`ServiceRequest.status = revoked`).
- **Verificar contra el `compose` en WSL exige una sola sesión, y si no, el diagnóstico miente.** WSL
  apaga la distro cuando no queda ninguna sesión abierta, y con ella se van los contenedores: si se
  levanta la pila en una invocación de `wsl` y se comprueba en otra, lo que contesta puede ser un
  contenedor **de la sesión anterior** —imagen vieja y base de datos distinta— escuchando en el mismo
  puerto. Pasó al verificar el ítem 16: el invariante nuevo del informe pareció no funcionar contra la
  pila, y funcionaba; lo que había detrás del puerto era el backend del día anterior. La comprobación
  que lo destapó fue mirar **qué imagen sirve el contenedor y qué hay en su base de datos**, no releer
  el código. Desde entonces el guion de verificación levanta, comprueba y consulta la base **dentro de
  la misma sesión**, y empieza imprimiendo qué contenedores había en pie antes de empezar.
- **Los códigos INE de municipio del generador son una lista corta escrita a mano** (ocho municipios
  de la provincia 41), no el registro del INE. La provincia sí está verificada —41, Sevilla, la
  misma que usa el ejemplo de la guía— y el `codigo-ine` no se valida contra ningún `ValueSet`, así
  que un municipio equivocado no rompe nada ni lo detecta el validador. Es deuda consciente y de
  poco calado: si algún día el código INE se ata a un conjunto de valores, hay que traer el registro
  completo en vez de ampliar la lista.
- **La reproducibilidad del generador depende de la versión de Faker.** Sus corpus cambian entre
  versiones mayores, así que la dependencia está acotada (`faker>=40,<41`). Subir de mayor cambiará
  la salida con la misma semilla; no es un fallo, pero hay que saberlo antes de investigar por qué
  un volcado ya no coincide.
- **La IG propia es trabajo real:** nueve perfiles más terminología, sin US Core ni IPS de donde tirar.
  Es el ítem que más fácilmente se subestima.
- **Sin la red de seguridad de Mirth** (D11): almacén de mensajes, reintentos y consola de reproceso
  hay que construirlos — **ítems 22 y 25**, y con D22 tomada dejan de ser una red de seguridad
  opcional: son lo que sostiene la atomicidad del `OML^O21`.
- **El `CapabilityStatement` puede estar prometiendo un verbo que el servidor rechaza.** El ítem 16
  cerró el `Bundle transaction` para los recursos con agregado, pero `ConformidadHispaLis` solo recorta
  `supportedProfile`: **no toca `rest.interaction`**, que HAPI rellena por su cuenta. Hay que mirar qué
  declara hoy `GET /fhir/metadata`; si dice `transaction`, el único documento del que un cliente se
  fía está anunciando algo que el interceptor de `ADR-0014` deniega. No se rehace aquí —el hito 1 está
  cerrado— y se comprueba y corrige en el **ítem 26**, que es donde D22 aterriza.
- **Simular normativa real tiene un límite.** El catálogo EDO y el formato de Redalerta se modelan de
  forma **verosímil, no fiel**, y así queda escrito en la IG.
- **CI de monorepo multi-*toolchain*:** filtrado por `paths:` desde el primer día, o cada cambio en
  Flutter recompila el backend.
- **No verificado contra fuente primaria** (§17, nada de esto bloquea el hito 1): estructura interna del
  CIP-SNS (irrelevante por D16), especificación MLLP (sin impacto en código: lo implementa HAPI), y la
  **tabla 0354** de V2.5.1 — que deja de ser una anotación y pasa a ser **bloqueante: ítem 21**, antes
  de generar código. Las dos versiones están archivadas, así que el cruce se hace en local.
- **Aportaciones pendientes a la biblioteca** al terminar el proyecto (§17.2): las capas 2 y 3 de la
  trampa documental de MLLP —que el documento normativo es un estándar de **V3** y que está **retirado
  desde mayo de 2025 sin sustituto**— van a `interoperabilidad/hl7-v2/`.
