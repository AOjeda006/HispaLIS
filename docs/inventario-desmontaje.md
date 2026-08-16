# Inventario del desmontaje — qué se va con los `CLAUDE.md` y el `PLAN.md`

> **Para qué existe esta lista.** Los nueve ficheros de abajo desaparecen del repositorio: los ocho
> `CLAUDE.md`, `AGENTS.md`, `docs/PLAN.md` y el `.claude/settings.json` dejan de tener sentido en
> cuanto el proyecto deja de desarrollarse con un agente. Antes de borrar nada hay que saber **qué
> hay dentro que no está en ningún otro sitio**, y dónde ha quedado.
>
> La regla que gobierna el desmontaje: **no se borra nada que no esté antes en
> [`memoria-tecnica.md`](memoria-tecnica.md)** (y en el PDF que se genera de él). Esta tabla es la
> comprobación de esa regla, fichero por fichero.
>
> **Precisión de recuento:** el encargo hablaba de *nueve* `CLAUDE.md`. Son **ocho** — raíz más
> siete componentes (`ig/`, `backend/`, `integracion/`, `web-profesional/`, `app-ciudadano/`,
> `simuladores/`, `terminologia/`). El diseño (§13.2) preveía seis por componente y `terminologia/`
> se añadió al montar el servidor, así que el número que circulaba en el encargo venía de sumar mal,
> no de que falte un fichero.

---

## Lo que NO se va, y por eso no está en esta lista

Sobreviven en el repositorio y no hace falta rescatar nada de ellos:

| Fichero | Qué es |
|---|---|
| `README.md` | La puerta de entrada: qué es, cómo se levanta, tabla de comandos, CI |
| `docs/diseno.md` | El documento de diseño, autosuficiente. Fuente de verdad del **porqué** |
| `docs/adr/` (44 ficheros) | Las decisiones de arquitectura, una por fichero |
| `docs/destilacion.md` | El dossier de qué aporta cada ADR a la biblioteca de convenciones |
| `infra/keycloak/README.md`, `.env.example` | Configuración documentada in situ |
| El historial de git | 200 y pico commits firmados, con su mensaje |

La **biblioteca de convenciones** (`BibliotecaDocumentacion`, repositorio hermano) tampoco se toca:
es donde viven los principios, los estilos por *stack* y el flujo de git que los `CLAUDE.md`
importaban. Nada de eso hay que rescatar aquí, porque nunca vivió aquí.

---

## 1. `CLAUDE.md` (raíz)

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| Qué es HispaLIS: simulación de un SIL privado en Sevilla sobre FHIR R5 | §1 |
| Stack completo por capas | §5.1 y §5 (componente a componente) |
| Objetivo y no-objetivos del encargo; qué queda fuera del proyecto entero | §2.1, §2.2 |
| **Los nueve invariantes del proyecto** | §4.1 (íntegros, uno a uno) |
| La advertencia R5 no es R4 y el puntero a la tabla | §6.5 |
| URIs canónicas propias bajo `.../HispaLIS/fhir` y el deber de documentarlas como propias (D19) | §6.2, §12 |
| Identificadores españoles sin `pattern` (D16); el NUHSA nunca `1..1` | §6.3 |
| Apellidos dobles: `family` completo y extensiones **sobre el elemento `family`** | §6.3 |
| `MUÑOZ`, `ÁLVAREZ` y `PEÑA` como casos de prueba obligatorios | §6.3, §9.3 |
| Extensiones solo cuando no exista elemento estándar; una sola propia | §6.4 |
| Todo en español, con los términos técnicos estándar en inglés | §4.1 (invariante 9) |
| Que los aprendizajes transversales van a ADR y no a la biblioteca a mitad de proyecto | §10.2 |
| *Puerta de clarificación, política de commits, protocolo de resumabilidad, imports de la biblioteca* | **No pasa**: es contrato de agente. Ver *Lo que se va y no se echa de menos* |

## 2. `backend/CLAUDE.md`

El fichero más denso de los ocho (23 KB). Es el que más rescate necesitaba.

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| **La tabla R5 ≠ R4**, 17 filas, verificada contra `hl7.fhir.r5.core@5.0.0` | §6.5 (versión completa: unión de la del backend, la de `ig/` y la de `diseno.md` §2.1) |
| El fork estructural: escritura por comando, lectura por DAO, una sola transacción | §3.2 y figura 2 |
| Read-your-writes como norma, `201` + `Location` + `ETag`, y por qué no es rendimiento | §3.3 |
| El reconciliador como vía de recuperación oficial, y que detecta las dos direcciones | §3.4, §5.2 |
| El bus: clave de partición = paciente, entrega al menos una vez, dedup por `hechoId`, Kafka no alimenta la lectura, nunca PHI en el bus | §5.2, §4.1 (invariante 6) |
| Notificaciones: el criterio vive en el `SubscriptionTopic`, la copia en `resources/conformidad/` y la puerta de `ci-ig` que impide que se bifurquen | §6.6, §11.2 |
| `id-only` cerrado en dos sitios; el secreto se firma con HMAC y no va en el recurso; el corte es el estado | §6.6, §7.5 |
| Terminología como servicio aparte: el puerto, nada de `Map<String,String>`, `display` español, solo LOINC `equivalent`, `422` frente a `400`, degradación si el servidor cae | §6.6 |
| La regla del crítico y la de la EDO: el agregado recibe el puerto y pregunta él; firmado no es validado; un cualitativo se guarda codificado | §5.2, §4.2 |
| La declaración EDO: dos fases, sin acuse no hay declaración, cuatro respuestas con tipo sellado, el plazo es de la enfermedad | §5.2 y figura 6 |
| La exportación y la traza: la cohorte se forma sola, los dos ámbitos, seudonimización por lista blanca, `400` ante parámetro no soportado, caducidad y barrendero, la traza se escribe después de contestar, `entity.query` a `0..0` | §7.4, §7.6 |
| **Los cinco invariantes de negocio que FHIR no puede expresar** (tabla) | §4.2 |
| Reglas de la API: `metadata`, `412` con `If-Match`, paginación por `Bundle.link`, `OperationOutcome`, interceptores, el gateway no habla FHIR | §6.1 |
| Las dos capas de seguridad y por qué no son intercambiables | §7.1 y figura 4 |
| `AutorizacionSmart` con `DENY` por defecto; un scope que no se entiende no concede nada | §7.2 |
| El consentimiento como la mitad que no se delega; `403` frente a omisión silenciosa; el compartimento en un solo sitio | §7.3 |
| `aud` obligatorio; descubrimiento perezoso | §7.2 |
| `securityMatcher("/fhir/**")` no casa y deja la API abierta sin un error | §11.3 |
| La trampa del `@SpringBootTest` propio y el guardián que la vigila | §11.4 |
| HAPI 8.10 no trae `$status` ni `$events`; no implementa `dependsOn` en `$translate`; `$translate` en R5 manda `sourceCode` | §11.2 |
| Un `SearchParameter` en `draft` no se indexa | §11.2 |
| Comandos del componente | §8.5 |

## 3. `ig/CLAUDE.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| La tabla R5 ≠ R4 (misma que arriba, con matices propios del FSH) | §6.5 |
| Versión fijada `hl7.fhir.r5.core@5.0.0` y dependencia `hl7.fhir.uv.extensions@5.3.0` | §6.2 |
| Base canónica y de dónde cuelgan los `Identifier.system` | §6.2, §6.3 |
| La fuente de verdad son los `.fsh`; lo generado no se edita ni se commitea | §6.2 |
| Perfilar restringiendo lo mínimo; el *slicing* jerárquico CIP-SNS / CIP-AUT / NHC | §6.3 |
| Nada de `required` sobre conjuntos que no están cerrados | §6.2 |
| Todo ejemplo valida contra su perfil en CI con el validador oficial | §6.2, §9.1 |
| Que en la guía queda escrito que es una simulación, que las URIs son propias y que ISO 15189 está fuera | §1, §12 |
| **El idioma se declara o el publisher asume inglés** (`language`, `jurisdiction`, `i18n-default-lang`, `resource-language-policy`) | §11.1 |
| El `id` de un `Instance:` sale del nombre del bloque, en PascalCase | §11.1 |
| Un `ValueSet` se ata al `CodeableReference`, no a su `.concept` | §11.1 |
| `0..0` es una regla de verdad: prohibir en el perfil, no en un `if` | §6.2, §7.6 |
| Las reglas del laboratorio viven en propiedades de `catalogo-pruebas` | §6.6 |
| `Sistema#CODIGO` no lleva `display`, y nada lo avisa | §11.1 |
| SUSHI compila, el validador conforma | §11.1 |
| **Las cuatro trampas del IG Publisher** y la negativa a construir con un espacio en la ruta | §11.1 |
| Cómo construir la guía en local dentro de la imagen oficial, y cuánto tarda | §8.4 |
| La línea base de `qa.html` y por qué «cuántos errores» solo significa algo comparado con ella | §9.1, §11.1 |
| Inventario de artefactos producidos | §6.2 |

## 4. `integracion/CLAUDE.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| Qué es y qué no: servicio propio con HAPI HL7v2, **no es Mirth**; canales como código | §5.3 |
| Versión fijada V2.5.1 y la salvedad de la tabla 0354 | §6.7 |
| Los dos planos que no se mezclan (tabla) | §3.1 |
| Reglas del canal: original íntegro, dedup por `MSH-10`, DLQ y reproceso idempotente, charset en `MSH-18`, estructura `origen → filtro → transformador → destino` | §5.3 |
| Cómo se identifica el motor: SMART Backend Services, clave por variable de entorno, JWKS en `/motor/jwks.json`, las cuatro cosas de la norma que se incumplen con facilidad, los cinco *scopes* que pide | §7.5 |
| Que `IdentidadDePrueba` verifica la aserción de verdad | §7.5 |
| El catálogo se pregunta: `OBR-4`, `OBX-3`, `SPM-4`; la vuelta del mapa solo donde hay equivalencia; lo que no se traduce va a la bandeja | §6.6 |
| La vuelta de `$translate` con `targetCode` y la caída a `reverse=true` | §11.2 |
| Contratos entrantes y salientes (tabla de mensajes) | §6.7 |
| **MLLP: la trampa documental de tres capas** y que el impacto en código es ninguno | §6.7 |

## 5. `web-profesional/CLAUDE.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| Qué construye y con qué lanzamiento (SMART EHR launch) | §5.4 |
| Habla R5 y solo R5; paginación por `Bundle.link`; errores en `OperationOutcome` en español | §5.4 |
| Nunca PHI en la URL, ni en logs de navegador, ni en analítica | §4.1 (invariante 6), §7.6 |
| Los `display` se muestran como llegan; apellidos sin partir; charset obligatorio | §6.3, §9.3 |
| Cliente público con PKCE `S256` y sin `client_secret` | §7.5 |
| Accesibilidad clínica: unidad junto al valor, rango de referencia visible | §5.4 |
| Las cinco cosas no opcionales del lanzamiento: nada se cablea, `iss` contra lista, `state` de 256 bits, `user/*.rs` no basta para el alta, el testigo solo a las llamadas del laboratorio | §7.5 |
| La sesión en `sessionStorage` y no en cookie, con el motivo | §7.5 |
| La guarda de ruta no es control de acceso | §7.5 |
| Se busca con `POST [tipo]/_search` y no con `GET` | §7.6 |
| El catálogo no se escribe ni se congela: se pide con `$expand` | §6.6 |
| `proxy.conf.json` con `xfwd: true` y por qué no es decorativo | §11.3 |
| Los `system` en `sistemas.ts`, cruzados con `aliases.fsh` por un test | §11.3 |

## 6. `app-ciudadano/CLAUDE.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| Flutter (D13) y el argumento: clientes multiplataforma, iOS es media España | §5.5, §10.1 |
| SMART standalone + PKCE, cliente público, *scopes* `patient/*.rs` | §7.5 |
| Nunca `client_secret`; los testigos al almacén seguro de la plataforma | §7.5 |
| Un *scope* concedido no garantiza los datos | §4.1 (invariante 3 y §7.3) |
| Caché local mínima y cifrada, borrada al cerrar sesión | §5.5 |
| Apellidos, charset y texto de usuario en español | §6.3, §9.3 |
| Un resultado sin contexto asusta: unidad, rango y estado de validación siempre visibles | §5.5 |

## 7. `simuladores/CLAUDE.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| Qué hay en cada carpeta y de qué hito es (tabla) | §5.6 |
| El generador resuelve la terminología contra el mismo servidor que todos; sin servidor no genera | §5.6, §4.1 (invariante 4) |
| Lo difícil son los resultados verosímiles, no la demografía | §5.6, §10.1 (D15) |
| Localización real: apellidos dobles, DNI/NIE con dígito de control, NUHSA `AN` + 10, códigos INE, y los tres apellidos de charset | §5.6, §9.3 |
| El NUHSA no es universal: parte del corpus sale sin él | §6.3 |
| Semilla parametrizable: sin reproducibilidad no sirve de arnés | §5.6 |
| Reglas del receptor de notificaciones: sin clave no arranca, exige `id-only`, detecta huecos de `eventNumber`, contesta el código que corresponde | §5.6 |
| Reglas del SVEA simulado: verosímil no fiel, **exige que no llegue filiación**, deduplica por el id del `Task`, sin plazo `422`, lo que llega tarde se registra, los cuatro modos provocables | §5.6, §12 |
| Los simuladores v2: `MSH-10` repetible a propósito | §5.6 |

## 8. `terminologia/CLAUDE.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| Qué hay y qué no puede haber: ni una *release* licenciada dentro del repositorio | §5.7, §12 |
| D14 en una frase: cuatro operaciones estándar y ni una propietaria; el servidor es intercambiable | §6.6, §10.1 |
| La tabla de licencias: LOINC 2.82, THO 7.3.0, SNOMED CT Edición Española | §6.6, §13.1 |
| **SNOMED no se descarga, se compone**: tres productos a versiones ancladas, y el cargador lee todos los ficheros de cada patrón | §13.1 |
| Declarar siempre la versión exacta del *release*; la de SNOMED se deduce del propio *release* | §6.6 |
| El subconjunto curado se deduce de la guía, no se escribe; `content: fragment` | §6.6 |
| Las siete trampas medidas contra HAPI 8.10: imagen *distroless* y el `healthcheck`, `$expand` y el índice de texto completo, `count=0`, el dialecto, `$lookup` de LOINC con `version`, `targetCode`, `match.equivalence` | §11.2 |

## 9. `AGENTS.md`

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| La tabla de comandos por componente (build, tests, lint, arranque) | §8.5 |
| Spotless con `palantir-java-format` enganchado a `verify`; Maven por *wrapper* en modo `only-script` | §8.5, §11.4 |
| Los tests de Angular corren con **vitest sobre jsdom**, no Karma; Node 24 | §8.5 |
| SUSHI por `npx`; `ig.ini` se mantiene a mano y está versionado | §11.1 |
| Un workflow por componente, todos con `paths:` | §8.6, §9.5 |
| **El bit de ejecución no lo gobierna `.gitattributes`**: todo guion que la CI invoque va como `100755` | §11.4 |
| *Ciclo de trabajo, definición de «hecho», cómo retomar tras `/compact`, reglas de oro, modo git* | **No pasa**: contrato de agente |

## 10. `docs/PLAN.md`

El fichero más grande (346 KB, 3 726 líneas) y el que más mezcla registro de trabajo con conocimiento.

| Qué contiene que merece sobrevivir | Dónde ha quedado |
|---|---|
| La **nota de entrega**: qué es, qué está terminado, qué está bloqueado y quién lo desbloquea | §1, §2.4, §13 |
| Qué es demostración y no debe confundirse con producción (la lista completa) | §12 |
| Las decisiones **D21** (`system` de identificador: dos adoptados de ÚNICAS, seis propios), **D22** (la puerta transaccional sigue cerrada) y **D23** (quién exporta, sobre qué, qué sale y qué pasa con el fichero) | §10.1, con su razonamiento |
| Los tres hitos, sus fechas y sus criterios de aceptación | §2.3, §2.4 |
| Los criterios del hito 3 uno a uno con su prueba concreta | §2.4 |
| **La transcripción del circuito de extremo a extremo** contra la pila levantada y con la seguridad puesta | §8.3 |
| Los **siete fallos** que destapó recorrerlo en vivo y que 290 tests no veían | §11.5 |
| Los **cuatro fallos del README** que solo se vieron ejecutándolo | §11.5 |
| La ronda de testing: fuzzing, propiedades, cobertura leída por los ceros, reconciliador con volumen | §9.2 |
| Los **cinco «funciona en mi máquina»** del clon limpio | §11.5 |
| **Los números finales**: tests por componente, tiempos del circuito, tiempos de cada puerta, cobertura, estado de los siete workflows | §9 entero |
| Las dos trampas de entorno medidas (Keycloak y `varchar(255)`; `private_key_jwt` exige `iat`) | §11.2 |
| Los dos avisos de entorno: WSL2 y la mitad de la RAM; el repositorio de Confluent antes que Central | §11.4 |
| **Lo que queda abierto**, las dieciséis entradas | §13, íntegras |
| *Notas / riesgos* (municipio ausente, NDJSON en disco local, SUSHI no comprueba invariantes, EDO de instancia única, la dependencia entre validar y la terminología, el facultativo duplicado) | §11 y §13 |
| *El checklist de los 52 ítems, ítem por ítem, con su commit* | **Resumido, no copiado.** Motivo: es el registro de ejecución, no conocimiento. Lo que cada ítem demostró está en §2.4 y en los criterios uno a uno; el rastro de cada uno vive en el historial de git, que no se borra |
| *Las «decisiones triviales resueltas al…» de cada tanda* | **Absorbidas.** Las que siguen teniendo consecuencia están en §10 y §11; las que solo justificaban un valor por defecto ya reversible se van con el plan |

## 11. `.claude/settings.json`

| Qué contiene | Veredicto |
|---|---|
| Un *hook* de arranque que fija `user.name`/`user.email` y activa la firma SSH si existe el secreto | **No pasa como contenido técnico.** Es maquinaria del entorno de desarrollo con agente |
| La convención que el *hook* implementa: todo commit firmado, con la identidad del usuario y **sin ningún trailer ajeno** | §14, una línea. El resto vive en `git-workflow.md` de la biblioteca |

---

## Lo que se va con ellos, y no se echa de menos

No entra en la memoria técnica **a propósito**, y este es el motivo:

1. **El contrato de trabajo del agente** — ciclo de turno, definición de «hecho», la puerta de
   clarificación, cómo retomar tras un `/compact`, el modo git del encargo. Describe cómo se
   construyó, no qué se construyó. Quien reciba el repositorio no va a trabajar así.
2. **Los `@import` a `BibliotecaDocumentacion`** — son punteros a un repositorio que sigue existiendo
   y que se mantiene aparte. Copiarlos aquí duplicaría una fuente de verdad que ya tiene la suya.
3. **La instrucción de no reabrir D1–D23** — es una orden a un agente. El **contenido** de las
   decisiones sí pasa (§10.1); la prohibición de reabrirlas, no: quien mantenga esto puede
   reabrir lo que quiera, sabiendo lo que le costó a la anterior.
4. **El protocolo de resumabilidad** (el estado vive en disco, marca el checklist, commitea) — muere
   con el `PLAN.md` que lo sostenía.
5. **El aviso de que la firma se comprueba antes del primer commit** — es operativa del entorno.

---

**Comprobación final.** Todas las filas de las once tablas de arriba tienen destino en
`memoria-tecnica.md`, salvo las dos marcadas explícitamente («resumido, no copiado» y «absorbidas»)
y las cinco de esta última sección, cada una con su motivo escrito.
