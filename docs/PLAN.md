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

## Estado actual

**Repo recién inicializado con los artefactos de encargo.** No hay código todavía: `ig/`, `backend/`,
`integracion/`, `web-profesional/`, `app-ciudadano/`, `simuladores/` e `infra/` son esqueletos con su
`CLAUDE.md` y un `.gitkeep`.

**Siguiente: ítem 0 del checklist del hito 1** (consultar la IG de ÚNICAS y fijar los `system`), sin
empezar.

---

## Checklist — Hito 1

> Un ítem = una unidad de trabajo pequeña, con criterio de aceptación verificable.
> `[ ]` pendiente · `[x]` hecho (cumple criterio + verificado + commiteado).
> La etiqueta **(C*n*)** enlaza con el criterio de aceptación *n* de §14 del diseño.

### Preparación

- [ ] **0 — Consultar la IG española de ÚNICAS y fijar los `system` definitivos.**
  *Criterio:* consultada `https://unicas-fhir.sanidad.gob.es/` (paquete de definiciones, no el sitio
  renderizado); para **DNI/NIE** y **CIP-SNS**, si ÚNICAS publica URI canónica, **se adopta la suya**;
  si no, se mantiene la propia de §4.8. La tabla de `system` queda escrita en `ig/` y el resultado
  —adoptado o no, y por qué— anotado aquí en *Decisiones*. **Bloquea al ítem 2**: es el único punto
  del proyecto con riesgo real de retrabajo y toca hacerlo **antes** del FSH de `PacienteLabES`.

- [ ] **1 — Andamiar el monorepo y dejar la CI en verde.**
  *Criterio:* existen `ig/sushi-config.yaml`, `backend/pom.xml`, `web-profesional/package.json` y
  `simuladores/pyproject.toml`; cada workflow de `.github/workflows/` **corre y pasa** al tocar su
  ruta y **no corre** al tocar otra (comprobado en el historial de Actions); las guardas de
  auto-omisión retiradas en los componentes ya andamiados; `README.md` y `AGENTS.md` con los comandos
  reales. **Verifica también los imports:** cada ruta `@../BibliotecaDocumentacion/...` y
  `@../../BibliotecaDocumentacion/...` de los siete `CLAUDE.md` resuelve a un fichero existente.

### La guía de implementación

- [ ] **2 — Los 9 perfiles en FSH, compilando. (C1)**
  *Criterio:* `sushi .` termina **sin errores ni warnings nuevos** y el **IG Publisher** genera
  `ig/output/`; existen `PacienteLabES`, `PeticionLab`, `EspecimenLab`, `ResultadoLab`, `InformeLab`,
  `LaboratorioOrg`, `FacultativoLab`, `CoberturaLab` y `NotificacionEDO` (§6.5).
  *Trampas:* `ServiceRequest.code` es **`CodeableReference`**; las extensiones de apellidos se declaran
  sobre **`HumanName.family`**, no sobre `HumanName`; `Coverage.kind` es **`1..1`**.

- [ ] **3 — Terminología y extensión propia.**
  *Criterio:* `CodeSystem` del catálogo local, `ConceptMap` catálogo → LOINC, `ValueSet` de tipos de
  muestra, motivos de rechazo y catálogo EDO, y la extensión propia `codigo-ine` — todos compilan y
  se publican en la IG. `hl7.fhir.uv.extensions@5.3.0` declarado como dependencia en `sushi-config.yaml`.

- [ ] **4 — Ejemplos que validan contra su perfil, en CI. (C2)**
  *Criterio:* al menos un ejemplo por perfil en `ig/input/examples/`; el **validador oficial** corre en
  el workflow contra `hl7.fhir.r5.core@5.0.0` y **falla el build** si un ejemplo no valida contra su
  perfil. Probado en rojo: se rompe un ejemplo a propósito y la CI lo detiene.

- [ ] **5 — La IG publicada en GitHub Pages.**
  *Criterio:* el workflow despliega `ig/output/` y la IG es navegable en
  `https://aojeda006.github.io/HispaLIS/`. En la portada consta que es una **simulación con datos
  sintéticos**, que las URIs canónicas son **propias y no oficiales**, y que **ISO 15189 está fuera de
  alcance** (D17).

### El backend

- [ ] **6 — `CapabilityStatement` correcto. (C3)**
  *Criterio:* `GET /fhir/metadata` devuelve `200` con `fhirVersion` = **`5.0.0`** y declara los
  perfiles soportados. Test automatizado.

- [ ] **7 — Read-your-writes en una sola transacción. (C4)**
  *Criterio:* `POST /fhir/Patient` devuelve **`201`** + `Location` + `ETag` **`W/"1"`**, y un `GET`
  **inmediato** al `Location` devuelve el recurso. **Test automatizado**, no comprobación manual.
  Dominio y proyección HAPI JPA se escriben en **un solo `@Transactional`** (§9).

- [ ] **8 — El invariante del espécimen rechazado, por TDD. (C6)**
  *Criterio:* un `Specimen` con `status = unsatisfactory` **no** puede producir un `Observation`;
  el intento devuelve el error correcto en `OperationOutcome`. **Test escrito en rojo primero** — debe
  verse en el historial de commits. El invariante vive en el **núcleo de dominio**, no en el
  `ResourceProvider`.

- [ ] **9 — El circuito completo por API. (C5)**
  *Criterio:* test de integración que crea `Patient` → `ServiceRequest` → `Specimen` → `Observation`
  → `DiagnosticReport`, **todos conformes a su perfil** (validados con `$validate` o con el validador
  oficial en CI).

- [ ] **10 — Concurrencia optimista. (C7)**
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

- **URIs canónicas propias.** Único punto con riesgo real de **retrabajo**: resolverlo en el ítem 0,
  antes del FSH de `PacienteLabES` (§4.8).
- **Acoplamiento de transacción con HAPI.** Escribir en el esquema de HAPI dentro de la transacción del
  dominio funciona (mismo *datasource*) pero ata a sus DAOs — registrado en `adr-0002`. Vigilar en cada
  actualización de HAPI.
- **Doble escritura del mismo hecho.** Dominio y proyección pueden divergir por un bug de mapeo. Hace
  falta un **reconciliador** que recorra el dominio y regenere la proyección, como **vía de recuperación
  oficial**, no como script de emergencia. Se planifica en el hito 2.
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
