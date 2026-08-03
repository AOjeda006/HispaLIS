# CLAUDE.md — `backend/` (dominio + API FHIR R5 + proyección)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado (principios, `interoperabilidad/fhir`,
> `interoperabilidad/espana`, `api-rest` y `seguridad` ya vienen de allí: **no** se repiten aquí).

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/java/convenciones.md
@../../BibliotecaDocumentacion/stacks/spring/convenciones.md
@../../BibliotecaDocumentacion/bases-de-datos/sql/convenciones.md
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
  transacción del dominio ata a sus DAOs. Hace falta un **reconciliador** que recorra el dominio y
  regenere la proyección — vía de recuperación **oficial**, no script de emergencia.

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
