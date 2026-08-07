# CLAUDE.md — `backend/` (dominio + API FHIR R5 + proyección)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado (principios, `interoperabilidad/fhir`,
> `interoperabilidad/espana`, `api-rest` y `seguridad` ya vienen de allí: **no** se repiten aquí).

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/java/convenciones.md
@../../BibliotecaDocumentacion/stacks/spring/convenciones.md
@../../BibliotecaDocumentacion/bases-de-datos/sql/convenciones.md
@../../BibliotecaDocumentacion/fundamentos/datos-distribuidos/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/terminologia/convenciones.md
@../../BibliotecaDocumentacion/patrones/repository-y-dto.md
@../../BibliotecaDocumentacion/patrones/inyeccion-dependencias.md
@../../BibliotecaDocumentacion/herramientas/docker.md

---

## ⚠️ R5 no es R4 — la tabla que hay que mirar antes de escribir el primer mapeo

Verificado contra el paquete canónico `hl7.fhir.r5.core@5.0.0`. **Cualquier ejemplo, tutorial,
respuesta de IA o librería basada en R4 que se copie sin mirar va a fallar aquí:**

| Elemento | R4 | **R5** | Impacto |
|---|---|---|---|
| `ServiceRequest.code` | `CodeableConcept` | **`CodeableReference`** | Cambia el JSON de *toda* petición |
| `ServiceRequest.reason` | `reasonCode` + `reasonReference` | **`reason` `0..*` `CodeableReference`** | Dos elementos fusionados en uno |
| `Coverage.kind` | no existe | **`1..1` obligatorio** (`insurance \| self-pay \| other`) | Un `Coverage` R4 **no valida** en R5 |
| `Coverage.subscriberId` | `string` | **`0..*` `Identifier`** | Cambio de tipo y cardinalidad |
| `Observation.triggeredBy` | no existe | **`0..*`** | El gancho de las pruebas reflejas |
| `Observation.bodyStructure` | no existe | `0..1 Reference` | |
| `DiagnosticReport.composition` | no existe | `0..1 Reference` | |
| `Specimen.combined` / `.role` / `.feature` | no existen | nuevos | |
| `Organization.telecom` / `.address` | existen | **eliminados** → `contact` (`ExtendedContactDetail`) | Un `Organization` de R4 **no valida** en R5 |
| `ConceptMap.source[x]` / `.target[x]` · `element.target.equivalence` | así se llaman | **`sourceScope[x]`/`targetScope[x]`** y **`relationship`**, con códigos distintos | Un `ConceptMap` de R4 **no valida** en R5 |
| **Extensiones** | dentro del núcleo | **paquete aparte** `hl7.fhir.uv.extensions` | Otra dependencia que declarar |

> Usa **siempre** el modelo `org.hl7.fhir.r5` de HAPI. Si un import trae `…model.r4…`, está mal.

---

## Arquitectura — el fork estructural (D3, §9 del diseño)

```
ESCRITURA  cliente ──POST/PUT FHIR──► ResourceProvider de escritura
                                        │ traduce el recurso a un COMANDO
                                        ▼
                              NÚCLEO DE DOMINIO (invariantes de negocio)
                                        │
                          ┌── UNA sola transacción PostgreSQL ──┐
                          ▼               ▼                     ▼
                   esquema `dominio`  esquema `fhir`         `outbox`
                   fuente de verdad   (HAPI JPA, vía          hechos a
                                       IFhirResourceDao)      publicar
LECTURA    cliente ──GET / search──► DAOs HAPI JPA ──► esquema `fhir`  (cero mapeo en lectura)
```

- **FHIR es formato de borde, no el modelo de dominio.** No persistas recursos FHIR como entidades:
  tienen opcionalidad enorme, `[0..*]` por todas partes y ninguno de los invariantes del negocio.
- **La proyección FHIR se escribe síncrona, en la misma transacción** que el dominio — mismo
  PostgreSQL, esquemas distintos, un solo `@Transactional`. La proyección llama a las **DAOs de
  HAPI** para que se pueblen los índices de búsqueda. **Kafka no alimenta el modelo de lectura.**
- **Read-your-writes es norma, no rendimiento:** `201 Created` + `Location` + `ETag` (`W/"1"`), y un
  `GET` inmediato al `Location` devuelve el recurso. Si la proyección fuese asíncrona, el `GET` daría
  `404` y estaríamos incumpliendo FHIR REST. Hay test automatizado que lo prueba.
- **Riesgo asumido y registrado** (`docs/adr/adr-0002-…`): escribir en el esquema de HAPI dentro de la
  transacción del dominio ata a sus DAOs. Por eso existe el **reconciliador**
  (`aplicacion/reconciliacion/`, `POST /fhir/$reconciliar`): recorre el dominio, regenera la
  proyección y detecta **las dos** direcciones —lo que falta y lo que sobra sin agregado detrás—. Es
  vía de recuperación **oficial**, no script de emergencia, y por defecto solo revisa.

## El bus de salida (§11)

- **El relay del `outbox` publica en Kafka** con **clave de partición = paciente** y esquema Avro
  versionado en el registro. Vive en `infraestructura/bus/` y no toca el dominio.
- **Entrega al menos una vez.** No se intenta más: exactamente-una-vez exigiría transacción
  distribuida. Todo consumidor deduplica por `hechoId`.
- **Kafka NO alimenta el modelo de lectura.** Si una lectura de la API llega a depender de que un
  consumidor haya procesado algo, se ha roto *read-your-writes*.
- **Nunca PHI en el bus**, ni siquiera de paso: un hecho lleva `pacienteId` interno y referencias. Lo
  garantizan el agregado `Hecho` al construirlo y el esquema Avro al declararlo — un tópico replicado
  es lo más difícil de borrar que hay el día que alguien ejerza el derecho de supresión.

## La terminología es un servicio aparte (D14)

El laboratorio **no es un servidor de terminología** y no publica `ValueSet/$expand` ni
`$validate-code`: pregunta. El puerto es `fhir/terminologia/Terminologia` y su implementación habla
`$lookup`, `$validate-code` y `$translate` contra `hispalis.terminologia.servidor` — **una URL, y
nada más**. No hay tipo de servidor ni operación propietaria que configurar: apuntar a Snowstorm es
cambiar esa línea.

- **Nada de `Map<String,String>`.** El puerto no tiene un método que devuelva «el catálogo entero» a
  propósito: con uno, lo primero que haría alguien es cachearlo al arrancar, y eso es la lista
  paralela que prohíbe el invariante 4. Se pregunta por un código a la vez.
- **Los `display` de un informe español van en español (D7).** El nombre del catálogo local manda y
  va en `CodeableConcept.text`; el `display` del LOINC llega en inglés y **se copia sin tocarlo**,
  porque su licencia prohíbe alterar el campo (ADR-0009).
- **Solo se publica el LOINC declarado `equivalent`.** Donde el `ConceptMap` dice
  `source-is-broader-than-target`, publicarlo como si fuera lo mismo afirmaría un método que el
  laboratorio no ha declarado.
- **Una prueba fuera del catálogo es `422`, no `400`:** el recurso está bien formado; lo que pasa es
  que el laboratorio no oferta ese análisis. Es regla de negocio.
- **Si el servidor no está, el laboratorio sigue:** código sin nombre y validación que no rechaza,
  con un aviso por caída. El nombre es presentación, el código es el dato — un servidor de
  terminología caído no puede impedir que se registre un resultado.
- **⚠️ `$translate` en R5** manda `sourceCode`/`targetSystem`, no `code`/`targetsystem`. HAPI acepta
  los dos, así que copiar un ejemplo de R4 *funciona aquí* y falla contra un servidor estricto. A la
  vuelta pasa lo contrario: HAPI aún devuelve `match.equivalence` de R4, así que se leen las dos.

## Invariantes de negocio que FHIR no puede expresar (§10)

| Agregado | Invariante |
|---|---|
| Petición | No se cierra con líneas pendientes |
| Espécimen | **Rechazado (`unsatisfactory`) ⇒ no puede producir resultado** |
| Resultado | Crítico ⇒ doble validación y notificación obligatoria |
| Informe | Solo se emite con todas las líneas resueltas |
| Notificación EDO | Resultado EDO validado ⇒ notificación obligatoria |

**Todos se prueban por TDD, en rojo primero.** Viven en el núcleo, no en el `ResourceProvider`.

## Reglas de la API

- `GET /fhir/metadata` declara `fhirVersion 5.0.0` y los perfiles soportados.
- **Concurrencia optimista obligatoria:** `PUT` con `If-Match` de versión obsoleta → **`412`**.
- **Búsqueda y paginación** por `SearchParameter` y `Bundle.link[relation=next]`. Nunca construyas
  la URL de la página siguiente a mano.
- **Errores en `OperationOutcome`** con el código HTTP correcto; nunca `200` con error dentro.
- **Interceptores** de autorización, consentimiento, auditoría y `ETag`/`If-Match`. Un *scope*
  concedido no garantiza los datos: **el consentimiento se aplica aquí**, no en el gateway.
- **El gateway no habla FHIR.** No le muevas lógica FHIR: dos servidores FHIR y ninguno conforme.
- **Nunca PHI en URLs, logs ni trazas.**

## Comandos

```bash
cd backend
./mvnw verify              # build + tests
./mvnw spring-boot:run     # arranque local
./mvnw -Dtest=… test       # un test concreto
```
