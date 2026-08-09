# CLAUDE.md — `backend/` (dominio + API FHIR R5 + proyección)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado (principios, `interoperabilidad/fhir`,
> `interoperabilidad/espana`, `api-rest` y `seguridad` ya vienen de allí: **no** se repiten aquí).

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/java/convenciones.md
@../../BibliotecaDocumentacion/stacks/spring/convenciones.md
@../../BibliotecaDocumentacion/bases-de-datos/sql/convenciones.md
@../../BibliotecaDocumentacion/fundamentos/datos-distribuidos/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/terminologia/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/smart-on-fhir/convenciones.md
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
| `Subscription.criteria` | cadena de búsqueda **dentro** de la suscripción | **no existe** → el criterio es un `SubscriptionTopic` aparte | Cambia el modelo entero, no un elemento |
| `Subscription.error` | `string` dentro del recurso | **eliminado** → `SubscriptionStatus.error` (`CodeableConcept`), por `$status` | Buscarlo y no encontrarlo invita a inventarse una extensión |
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

## Las notificaciones de `Subscription` (ítem 44)

- **El criterio NO está en el código.** Vive en el `SubscriptionTopic` que publica la guía;
  `CriterioDelTopico` lo evalúa con el emparejador en memoria de HAPI y **no tiene una sola condición
  escrita en Java**. Un criterio que el emparejador no sepa evaluar **no dispara** y se avisa: darlo
  por bueno notificaría por algo que nadie ha comprobado.
- **El backend lleva una copia del tópico** en `resources/conformidad/`, porque se construye sin la
  guía delante. Es literalmente la salida de SUSHI, y **`ci-ig` falla si divergen**. Si tocas el FSH
  del tópico, copia el fichero otra vez.
- **Se anota en la transacción, se entrega fuera.** Igual que el `outbox` de Kafka, más una razón
  propia: una llamada HTTP a un tercero dentro de una transacción la mantiene abierta lo que tarde en
  contestar alguien que no controlamos.
- **`id-only` y nada más**, cerrado en dos sitios: la `Subscription` que pide `full-resource` se
  rechaza al escribir, y en `notificacion.evento` **no hay dónde guardar el recurso**. Que el
  contenido no exista es lo que convierte la promesa en garantía.
- **El secreto compartido NO va en el recurso.** `Subscription.parameter` es donde lo mete la
  documentación habitual y es un error: el recurso se lee por la API y su historial no se borra. Se
  **firma** con HMAC-SHA256 y una clave de configuración; el recurso solo dice cuál.
- **El corte es el estado.** Agotados los intentos, la `Subscription` pasa a `error` y deja de
  acumular. Reactivar es un acto explícito de alguien.
- **⚠️ HAPI 8.10 no trae `$status` ni `$events`** (solo `$trigger-subscription`). Están en
  `ProveedorDeSuscripcion`, registrado como **proveedor suelto**: hacerlo `ProveedorPropio`
  sustituiría al proveedor de HAPI para `Subscription` y le aplicaría las puertas de los recursos con
  agregado detrás, que este no tiene.

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

## La seguridad son dos capas y no son intercambiables (ítem 35)

```
petición ──► filtro de Spring Security ──► servlet de HAPI ──► interceptores
             firma, emisor, caducidad, aud    │                 AutorizacionSmart  → ¿puede leer Observation?
             (criptografía y protocolo)       │                 ConsentimientoDelPaciente → ¿puede ver ESTA?
```

- **`AutorizacionSmart`** traduce los *scopes* a reglas de HAPI, con `PolicyEnum.DENY` por defecto.
  Un scope que no se entiende **no concede nada**: `AmbitoSmart.de()` devuelve vacío ante un sufijo
  desordenado, repetido o inventado. «Corregir» `.dus` a `.cud` le daría a quien pidió actualizar el
  permiso de borrar.
- **`ConsentimientoDelPaciente`** decide de quién son los datos, y **es la mitad que no se puede
  delegar**: el proxy no sabe qué es un compartimento y el servidor de identidad ya hizo lo suyo al
  emitir el testigo. El compartimento se pregunta al `ISearchParamRegistry`
  (`getProvidesMembershipInCompartments`), no se escribe a mano.
- **Dos formas de decir que no, y la diferencia importa.** Lectura directa → `403`. Búsqueda →
  se omite el recurso **en silencio**: contestar «hay tres que no te enseño» ya cuenta algo de quien
  no lo autorizó.
- **El compartimento vive en un solo sitio.** HAPI sabe hacerlo también con `inCompartment`, y
  ponerlo en los dos parecería defensa en profundidad: sería la misma regla en dos ficheros que hay
  que cambiar a la vez.
- **`aud` es obligatorio.** Sin validarlo, un testigo legítimo emitido para otro servidor de recursos
  del mismo *realm* valdría aquí. Hay test.
- **El descubrimiento es perezoso**: el laboratorio arranca con Keycloak caído y contesta `401`, que
  es lo correcto. Lo que no hace es no arrancar.

### ⚠️ `securityMatcher("/fhir/**")` no casa, y no avisa

La API FHIR la sirve el servlet de HAPI, no el `DispatcherServlet`. Con `spring-webmvc` en el
*classpath*, una cadena en `securityMatcher` / `requestMatchers` se convierte en un
`MvcRequestMatcher` que **nunca empareja** esas peticiones: la cadena se construye, el log dice
`Will secure Or [Mvc [pattern='/fhir/**']]` y **la API queda abierta sin un solo error**. Se usan
emparejadores explícitos (`PathPatternRequestMatcher`) y hay un test que pide sin testigo y exige
`401`. Todo el detalle en `docs/adr/adr-0020-…`.

### Los tests y el interruptor

`hispalis.seguridad.habilitada` va **encendida por defecto**. `TestDeIntegracion` la apaga, pero una
clase que declare su **propio** `@SpringBootTest` oculta el del padre entero, propiedades incluidas,
y arranca con la seguridad encendida sin emisor — el contexto no levanta. Hay que repetir la línea
(lo hace `RelayDelOutboxTest`). Quien prueba la seguridad la enciende: `SeguridadSmartTest` levanta
un servidor de identidad de verdad con `HttpServer` y firma sus propios testigos, para ejercitar el
descubrimiento, el JWKS y la validación de `aud` de producción y no una versión de test de ellos.

## Comandos

```bash
cd backend
./mvnw verify              # build + tests
./mvnw spring-boot:run     # arranque local
./mvnw -Dtest=… test       # un test concreto
```
