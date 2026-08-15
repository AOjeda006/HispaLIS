# Dossier de destilación a la biblioteca

**Qué es esto.** El inventario de lo que HispaLIS ha aprendido y **puede salir del proyecto**: para
cada ADR, qué tiene de transversal una vez descartado lo que solo vale aquí, a qué fichero de
`BibliotecaDocumentacion` iría y con qué autoridad se sostiene. Más **dos fichas que no son de ningún
ADR porque son de varios**: aparecen como una línea suelta al final de cuatro o cinco, y solo se ven
al ponerlas juntas.

**Qué NO es.** Una edición de la biblioteca. `BibliotecaDocumentacion` es un repositorio aparte, con
sus propias reglas de curación, y su ciclo de enriquecimiento es otro encargo. Aquí solo se deja el
dossier; **nada de este repositorio escribe en aquel**.

**Cómo se reparte.** La biblioteca separa la **regla operativa** —`convenciones.md`, lo que hay que
hacer, en imperativo y sin justificar— del **porqué** —`referencia.md`, la trampa medida, la cita, el
razonamiento—. Un hallazgo casi siempre va a los dos: una línea arriba y un párrafo abajo. Cuando
aquí se dice «solo `referencia.md`» es porque el hallazgo **no genera regla nueva**: documenta un
terreno, no manda hacer nada.

**Regla de admisión.** Solo entra lo que sería cierto en otro proyecto. Que el NHC de este
laboratorio sea `1..1` es de HispaLIS; que un identificador que tú no emites no lleve `pattern` es de
cualquiera. En cada ficha, la columna **«se queda aquí»** dice qué parte se ha descartado — es la
mitad del trabajo y la que evita que la biblioteca se llene de un solo proyecto.

---

## Resumen

| | |
|---|---|
| ADR escritos en el proyecto | **42** (`adr-0001` … `adr-0042`) |
| Con aportación transversal | **42** |
| Que aportan **regla nueva** (`convenciones.md` + `referencia.md`) | **37** |
| Que aportan **solo contexto** (`referencia.md`) | **5** — `0001`, `0004`, `0006`, `0011`, `0013` |
| **Fichas que no salen de un solo ADR** | **2** — el arnés sin probar y la configuración que no ejecuta nadie |
| Destinos distintos en la biblioteca | **25** (una carpeta con su `convenciones.md` y su `referencia.md` cuenta como uno) |
| Aportaciones **arrastradas** de hitos anteriores, aún sin destilar | **2** — la trampa documental de MLLP (hito 1) y la tabla 0354 (hito 2) |

**Rutas comprobadas el 2026-08-15.** Los 25 destinos existen hoy en `BibliotecaDocumentacion`, con su
`convenciones.md` y su `referencia.md` donde son carpeta. La única ruta que este dossier citaba y no
era la correcta era la de `adr-0038`: el caso concreto es del constructor de Angular y va a
`stacks/angular/`, no a `stacks/typescript/`, que se queda con la regla general del empaquetado.

### A qué ficheros iría, y cuántos ADR alimenta cada uno

| Fichero de la biblioteca | ADR que lo alimentan |
|---|---|
| `interoperabilidad/fhir/` | 0001, 0011, 0029, 0030, 0036 |
| `interoperabilidad/terminologia/` | 0006, 0009, 0026, 0028, 0032, 0039, 0040 |
| `interoperabilidad/hl7-v2/` | 0005, 0018, 0021, 0034, 0037 |
| `interoperabilidad/smart-on-fhir/` | 0024, 0025, 0033 |
| `interoperabilidad/perfilado-fsh/` | 0007, 0010 |
| `interoperabilidad/integracion/` | 0005, 0019, 0034 |
| `interoperabilidad/espana/` | 0003, 0039, 0040 |
| `interoperabilidad/bulk-data/` | *(ninguno nuevo; ver «Lo que no aporta nada»)* |
| `stacks/spring/` | 0012, 0013, 0020, 0031 |
| `stacks/java/` | 0036 |
| `stacks/flutter/` | 0025 |
| `stacks/typescript/` | 0038 |
| `stacks/angular/` | 0038 |
| `bases-de-datos/sql/` | 0015 |
| `fundamentos/datos-distribuidos/` | 0019, 0023 |
| `fundamentos/redes/` | 0017, 0022 |
| `herramientas/seguridad.md` | 0016, 0020, 0022, 0027, 0033, **0037**, + la ficha transversal II |
| `herramientas/api-rest.md` | 0016, 0017 |
| `herramientas/autenticacion.md` | 0024, 0025 |
| `herramientas/docker.md` | 0026, 0035 |
| `herramientas/entrega-continua.md` | 0004, 0007, 0008 |
| `principios/testing.md` | 0014, 0026, 0033, 0034, 0037, 0038, 0039, 0040, **0041**, **0042**, + las dos fichas transversales (I y II) |
| `principios/manejo-errores.md` | 0031, 0036, 0037, 0039 |
| `principios/git-workflow.md` | 0008 |
| `principios/desarrollo-con-ia.md` | 0004 |
| `diseno/` | 0002, 0014, 0028, 0032, + la ficha transversal II |

`principios/testing.md` se lleva **doce de las cuarenta y cuatro entradas** de este dossier, y eso
**no** es un reparto casual: es el fichero cuyas reglas el proyecto incumplía —fuzzing,
*property-based* y cobertura leída— y el que se puso a prueba al cumplirlas. Tres de sus líneas
actuales salen de aquí **matizadas o corregidas**, no solo confirmadas; van marcadas con ⚠️.

---

## Las dos aportaciones arrastradas, que van primero

Están apuntadas como pendientes desde su hito y siguen sin destilar. Las dos son **correcciones a
material que la biblioteca ya tiene**, no añadidos: una fuente que se cita mal y una tabla que se da
por buena.

### A. La trampa documental de MLLP — dos capas (hito 1, `adr-0005`)

| | |
|---|---|
| **Qué es transversal** | Quien implementa MLLP busca el apéndice B de HL7 V2.5/V2.5.1 y lo encuentra **vacío**. El documento normativo real es *Transport Specification — MLLP, Release 2*, y es un estándar de **HL7 V3**, no de v2: la primera capa de la trampa es que el transporte de v2 está especificado fuera de v2. La segunda es peor: ese documento está **retirado desde mayo de 2025 y sin sustituto designado**, así que hoy **no hay fuente normativa vigente que citar** para el *framing* de v2. |
| **Impacto en el código** | Ninguno. El *framing* (`0x0B` … `0x1C 0x0D`) lo implementa cualquier librería seria del protocolo y nunca se escribe a mano. Lo que falta es **fuente citable**, no comportamiento. |
| **Dónde iría** | `interoperabilidad/hl7-v2/referencia.md` — es contexto puro, y de los que ahorran una tarde. Una línea en `convenciones.md`: «no busques MLLP en el apéndice B; no cites el estándar retirado como vigente; describe el *framing* y di que lo aporta la librería». |
| **Autoridad** | HL7 V2.5.1 (2007), apéndice B (vacío, comprobado). HL7 V3 *Transport Specification — MLLP, Release 2* (retirado, mayo de 2025). |
| **Se queda aquí** | Que este proyecto use HAPI HL7v2 y no Mirth. |

### B. La tabla 0354 se contradice consigo misma (hito 2, `adr-0018`)

| | |
|---|---|
| **Qué es transversal** | La tabla 0354 (*message structure*) **tiene contenido distinto entre el capítulo 2 y el apéndice A del mismo estándar**, y distinto además entre V2.5 y V2.5.1. Un mapeo que se apoye en el apéndice produce un `MSH-9-3` que el receptor rechaza, y la discusión posterior es irresoluble porque las dos partes citan el mismo documento. La regla: **antes de mapear, elegir fuente y escribirla**; manda el capítulo 2 —es la parte normativa de control y es lo que implementan las librerías— y por encima de él, la guía de interfaz acordada. |
| **Dónde iría** | `interoperabilidad/hl7-v2/convenciones.md` (la regla: fijar fuente y rechazar lo que no cuadre, en vez de reconciliar) + `referencia.md` (la contradicción, con las dos ubicaciones y las dos versiones). |
| **Autoridad** | HL7 V2.5 y V2.5.1, capítulo 2 y apéndice A, cruzados sobre los dos documentos archivados en `_fuente/` de la biblioteca. Implementación de referencia: `hapi-structures-v251`. |
| **Se queda aquí** | Los tres eventos concretos que usa HispaLIS (`ADT^A01`, `OML^O21`, `ORU^R01`) con su estructura. |

---

## Fichas, ADR por ADR

> **Leyenda de destino.** `C` = regla operativa a `convenciones.md`; `R` = porqué / trampa a
> `referencia.md`.

### ADR-0001 · FHIR R5 frente a R4

- **Transversal:** la tabla de **los nueve elementos que cambian de R4 a R5** —`ServiceRequest.code`
  a `CodeableReference`, `Subscription.criteria` que desaparece, `AuditEvent.type/subtype` que pasa a
  `category/code`, `Group.actual` que se va, `Organization.telecom` eliminado…— y la regla de
  elección: **fijar una versión y declararla en el `CapabilityStatement`**, sin conversión al vuelo.
  La razón general es que la conversión R4↔R5 no es total y una capa de conversión convierte cada
  fallo de mapeo en un fallo de tiempo de ejecución sin dueño.
- **Destino:** `interoperabilidad/fhir/referencia.md` (**R**), la tabla completa. Es material de
  consulta, no regla nueva.
- **Autoridad:** `hl7.fhir.r5.core@5.0.0`, verificado elemento a elemento contra el paquete canónico.
- **Se queda aquí:** que HispaLIS elija R5 y por qué le compensa (ítems 44 y 49 dependen de R5).

### ADR-0002 · Dominio propio con proyección en la misma transacción

- **Transversal:** el patrón entero. Un núcleo con sus invariantes como fuente de verdad y un
  servidor de lectura poblado **síncrono, en la misma transacción**, para que *read-your-writes* sea
  un invariante y no una promesa. Con sus tres corolarios: el bus **no** alimenta el modelo de
  lectura; lo que se publica sale por un **outbox transaccional**; y atarse a las DAO de un framework
  ajeno exige un **reconciliador** que detecte las dos direcciones de la divergencia —lo que falta y
  lo que sobra— como vía de recuperación oficial, no como script de emergencia.
- **Destino:** `diseno/convenciones.md` (**C**: la regla de las tres piezas) y `diseno/referencia.md`
  (**R**: por qué asíncrono rompe la conformidad REST, y por qué el reconciliador es obligatorio si
  la proyección la escribe otro).
- **Autoridad:** FHIR R5 §3.1 (semántica de `201 Created` + `Location`); patrón *transactional
  outbox*.
- **Se queda aquí:** los nombres de esquema (`dominio`, `fhir`, `outbox`) y que la proyección sea HAPI.

### ADR-0003 · Identificadores españoles sin `pattern`

- **Transversal:** **no pongas `pattern` ni regex en un identificador que tú no emites.** El emisor
  cambia el formato y tu perfil rechaza datos correctos; y un identificador ajeno es una **cadena
  opaca** con su `system`, no un formato que validar. Solo el identificador del que eres autoridad
  lleva `1..1` y validación. Corolario de modelado: **el *slicing* jerárquico** (CIP-SNS / CIP
  autonómico / historia propia) hace que el perfil sirva en otra comunidad autónoma sin rehacerlo, y
  el CIP autonómico **nunca** es obligatorio.
- **Destino:** `interoperabilidad/espana/convenciones.md` (**C**) + `referencia.md` (**R**: por qué el
  NUHSA falta tan a menudo en privada, y qué hacer antes de inventar una URI canónica — mirar ÚNICAS).
- **Autoridad:** IG española de ÚNICAS (`unicas-fhir.sanidad.gob.es`); OID del Ministerio para DNI/NIE
  (`urn:oid:1.3.6.1.4.1.19126.3`) y CIP-SNS (`urn:oid:2.16.724.4.40`).
- **Se queda aquí:** la base canónica `aojeda006.github.io/HispaLIS` y el NHC de este laboratorio.

### ADR-0004 · Monorepo con la guía dentro, y un `CLAUDE.md` por componente

- **Transversal:** dos cosas y ninguna es «usa monorepo». La primera: **la guía de implementación es
  código y se versiona con lo que la implementa**, porque el día que divergen no hay forma de saber
  cuál manda. La segunda: en un repositorio multi-stack, **la memoria del agente se estratifica** —lo
  transversal en la raíz, las convenciones de cada stack en su carpeta, y sin duplicar imports— y
  **la CI se filtra por rutas desde el primer día**, o cada cambio dispara siete construcciones.
- **Destino:** `principios/desarrollo-con-ia.md` (**R**: la estratificación de la memoria) y
  `herramientas/entrega-continua.md` (**C**: un workflow por componente, con `paths:`).
- **Autoridad:** ninguna externa; es experiencia del proyecto.
- **Se queda aquí:** el reparto concreto de carpetas de HispaLIS.

### ADR-0005 · Motor propio con HAPI HL7v2 frente a Mirth

- **Transversal:** (a) **la trampa documental de MLLP**, ficha A de arriba; (b) la regla de decisión
  entre motor de integración de caja y librería: si los canales tienen que **revisarse como código y
  desplegarse por el mismo circuito** que el resto, una consola de administración es un segundo
  camino de despliegue sin revisión; (c) la estructura de canal `origen → filtro → transformador →
  destino`, que es de cualquier motor.
- **Destino:** `interoperabilidad/hl7-v2/referencia.md` (**R**: MLLP) e
  `interoperabilidad/integracion/convenciones.md` (**C**: canales como código, estructura del canal).
- **Autoridad:** ver ficha A.
- **Se queda aquí:** la comparación concreta con Mirth y que el motor escriba contra la API FHIR.

### ADR-0006 · Servidor de terminología ligero e intercambiable

- **Transversal:** **habla con la terminología por las cuatro operaciones estándar** (`$expand`,
  `$lookup`, `$validate-code`, `$translate`) y **nada más**, y el servidor se vuelve una URL de
  configuración. Es lo que permite empezar con el más barato y cambiar a uno serio sin tocar código;
  es también lo que impide que se cuele una operación propietaria que después ata.
- **Destino:** `interoperabilidad/terminologia/referencia.md` (**R**). Regla ya existente en la
  biblioteca; esto es el respaldo empírico de que basta con las cuatro.
- **Autoridad:** FHIR R5, operaciones de `CodeSystem`/`ValueSet`/`ConceptMap`.
- **Se queda aquí:** HAPI como servidor y los subconjuntos concretos cargados.

### ADR-0007 · Las cuatro trampas del IG Publisher

- **Transversal:** las cuatro, tal cual, porque le pasan a cualquiera que construya una IG: `ig.ini`
  **no admite comentarios** (y el fallo no dice eso); la plantilla `fhir.base.template` está anunciada
  como retirada y hay que nacer en `fhir2.base.template`; el publisher **renderiza con Jekyll**, que
  no viene en los *runners* y hay que instalar explícitamente; y **un espacio en la ruta rompe la
  construcción local**.
- **Destino:** `interoperabilidad/perfilado-fsh/convenciones.md` (**C**: las cuatro como lista de
  comprobación previa) + `referencia.md` (**R**: qué error da cada una, que es lo que no se encuentra
  buscando).
- **Autoridad:** IG Publisher (HL7), medido; anuncio de retirada de `fhir.base.template`.
- **Se queda aquí:** nada. Este ADR es transversal entero.

### ADR-0008 · Se desarrolla en Windows y se construye en Linux

- **Transversal:** los dos problemas y que **se arreglan de forma distinta**. Los finales de línea son
  **configuración** (`.gitattributes` con `* text=auto eol=lf` y las excepciones `.bat`/`.cmd` en
  CRLF); el **bit de ejecución** es **contenido del índice** y no se arregla con configuración, hay
  que `git update-index --chmod=+x`. Confundirlos produce el clásico «funciona en mi máquina y la CI
  dice `Permission denied`».
- **Destino:** `principios/git-workflow.md` (**C** + **R**) y una línea en
  `herramientas/entrega-continua.md`.
- **Autoridad:** documentación de `gitattributes(5)` y `git-update-index(1)`.
- **Se queda aquí:** la lista concreta de ficheros de este repositorio.

### ADR-0009 · No se fija a mano el `display` de terminología externa

- **Transversal:** un `Coding` de un vocabulario ajeno lleva `system` y `code`; el `display` **lo
  resuelve el servidor de terminología al mostrarlo**. Copiarlo a mano crea una copia que se queda
  vieja y que además puede infringir la licencia del vocabulario. Dos excepciones justificadas: el
  `display` del `CodeSystem` **propio** (ahí eres la autoridad, no es copia) y el
  `ConceptMap…target.display`, que es documentación del mapeo.
- **Destino:** `interoperabilidad/terminologia/convenciones.md` (**C**) + `referencia.md` (**R**: la
  cláusula de LOINC que prohíbe alterar el nombre).
- **Autoridad:** licencia de uso de LOINC (Regenstrief); FHIR R5, definición de `Coding.display`.
- **Se queda aquí:** que el catálogo propio se llame `catalogo-pruebas`.

### ADR-0010 · El idioma de una guía se declara, o se asume inglés

- **Transversal:** hay que declararlo en **dos sitios que hacen cosas distintas** —`language` del
  recurso `ImplementationGuide` y `i18n-default-lang` del renderizador— más
  `resource-language-policy` y la jurisdicción. Con uno solo, la guía sale medio traducida y nadie
  encuentra el parámetro que falta.
- **Destino:** `interoperabilidad/perfilado-fsh/convenciones.md` (**C**), como bloque copiable.
- **Autoridad:** parámetros del IG Publisher; FHIR R5 `Resource.language`.
- **Se queda aquí:** `es` y `urn:iso:std:iso:3166#ES`.

### ADR-0011 · Empotrar el servidor JPA de HAPI, y las siete trampas

- **Transversal (poco, pero denso):** el patrón general es «**empotrar un framework que además es un
  servidor**»: una única clase de configuración que haga de *composition root* del borde, y la
  advertencia de que **importar la configuración con el nombre de tu versión no basta** — casi siempre
  hacen falta cinco más, y ninguna avisa.
- **Destino:** `interoperabilidad/fhir/referencia.md` (**R**), como apartado «un servidor empotrado
  dentro de una aplicación propia», con el caso medido como ejemplo. No genera regla: es un mapa de
  minas de una librería concreta, y el nombre del apartado no debe ser el de la librería.
- **Autoridad:** HAPI FHIR 8.10.1, medido.
- **Se queda aquí:** las siete trampas con su código, que son de HAPI y de esta versión.

### ADR-0012 · Una sola transacción entre dos persistencias

- **Transversal:** para que dos capas de persistencia distintas —JDBC a pelo y un JPA ajeno—
  compartan transacción, **el gestor de transacciones necesita el `DataSource`, no solo el
  `EntityManagerFactory`**. Sin esa línea son dos transacciones que parecen una, y el fallo solo
  aparece cuando la segunda revienta después de que la primera haya confirmado. Con el corolario de
  prueba: **el test que lo comprueba tiene que leer la capa que no confirmó**.
- **Destino:** `stacks/spring/convenciones.md` (**C**: la línea) + `referencia.md` (**R**: por qué no
  falla en los casos buenos).
- **Autoridad:** Javadoc de `JpaTransactionManager.setDataSource`.
- **Se queda aquí:** los nombres de los beans de este proyecto.

### ADR-0013 · Arrancar en local sin Docker con el PostgreSQL de los tests

- **Transversal:** la comodidad de desarrollo **vive en `src/test`** y **publica su configuración por
  las mismas variables que lee producción**. Si tuviera un mecanismo propio, habría un segundo camino
  de configuración que se desvía sin que nadie lo note.
- **Destino:** `stacks/spring/referencia.md` (**R**).
- **Autoridad:** ninguna externa.
- **Se queda aquí:** el perfil de Maven y la clase `ArranqueLocal`.

### ADR-0014 · Cerrar todas las puertas de escritura del framework

- **Transversal:** un framework que también escribe tiene **varias puertas** —métodos heredados,
  tipos sin reglas, transacciones/lotes, operaciones—, y hay que **enumerarlas y cerrarlas una a
  una**. Cuatro reglas: lo heredado **se prueba** antes de darlo por bueno; lo que no tiene reglas
  definidas **se rechaza**, no se permite; las puertas laterales se cierran **en la capa que las
  dispara**; y —la que más envejece bien— **la lista de lo protegido se deduce, no se escribe**: se le
  pregunta al sistema qué tipos gobierna. Enumerar a mano las puertas de un framework produce una
  lista que **nace correcta**, y la siguiente versión del framework añade puertas que la lista no
  tiene. Más el corolario de test, que es el que más vale: **el test se escribe contra el dominio, no
  contra la proyección** — leer la proyección lo habría dado por bueno.
- **Destino:** `diseno/convenciones.md` (**C**: enumerar y cerrar; deducir la lista en vez de
  escribirla) + `principios/testing.md` (**C**: contra qué capa se afirma).
- **Autoridad:** ninguna externa; es experiencia.
- **Se queda aquí:** la lista de recursos y de interceptores de HispaLIS.

### ADR-0015 · Los datos de configuración no van en las migraciones

- **Transversal:** las tres preguntas, que sirven para cualquier tabla-semilla. ¿La escribe **alguien
  del sistema** en ejecución? Si no, es configuración con pasos de más. ¿La necesita **más de un
  componente**, en más de un lenguaje? Entonces el sitio es un fichero común. ¿Es **vocabulario
  compartido** con quien habla contigo? Entonces es terminología y va a la guía.
- **Destino:** `bases-de-datos/sql/convenciones.md` (**C**: las tres preguntas) + `referencia.md`
  (**R**: por qué una migración que inserta datos es un despliegue que hay que repetir).
- **Autoridad:** ninguna externa.
- **Se queda aquí:** los rangos de referencia del laboratorio.

### ADR-0016 · Un identificador de paciente no viaja en la URL de búsqueda

- **Transversal:** un dato sensible en la **query string** acaba en el log del proxy, en el historial
  del navegador, en el `Referer` y en la analítica — sitios que no están bajo la política de retención
  de la aplicación. La solución es la forma **estándar** de búsqueda por cuerpo (`POST …/_search` en
  FHIR; su equivalente en otras APIs), y la comprobación no termina en la ida: **el enlace de la
  página siguiente que devuelve el servidor no puede reintroducir el dato en una URL**.
- **Destino:** `herramientas/seguridad.md` (**C**) + `herramientas/api-rest.md` (**C**: búsqueda por
  cuerpo y paginación que no filtra) + `referencia.md` correspondiente.
- **Autoridad:** FHIR R5 §3.2.1 (`POST [type]/_search`); OWASP ASVS, registro y protección de datos
  sensibles en URL.
- **Se queda aquí:** que el cliente sea Angular y el criterio, el NHC.

### ADR-0017 · Los enlaces los firma el servidor, y tras un proxy los firma mal

- **Transversal:** cuando el servidor construye URLs absolutas (paginación, `Location`, HATEOAS) y
  hay un proxy delante, **la dirección que el servidor cree suya no es la del cliente**. Se cierra por
  los dos lados: el proxy manda `X-Forwarded-Host`/`-Proto` y el servidor los respeta **cayendo a la
  petición cuando no llegan**, para que el mismo binario sirva con proxy y sin él. Y se prueba en los
  dos sitios: un test de servidor con las cabeceras a mano, y uno de extremo a extremo con el proxy
  puesto.
- **Destino:** `fundamentos/redes/convenciones.md` (**C**) + `herramientas/api-rest.md` (**C**).
- **Autoridad:** RFC 7239 (*Forwarded*) y el uso de facto de `X-Forwarded-*`.
- **Se queda aquí:** la configuración de nginx y del `proxy.conf.json` de Angular.

### ADR-0018 · La tabla 0354 se contradice consigo misma

Ficha B de arriba. **Aportación arrastrada del hito 2.**

### ADR-0019 · Una búsqueda cacheada convierte la idempotencia en una ilusión

- **Transversal:** si tu protocolo de deduplicación consiste en **buscar antes de escribir**, un
  caché de búsquedas —del servidor o de un intermediario— convierte «ya existe» en «existía hace un
  minuto», y el reproceso duplica. Se cierra por dos lados independientes: el servidor **no cachea**
  si promete *read-your-writes*, y el cliente **pide explícitamente que no se cachee**, porque no
  puede suponer la configuración del servidor con el que habla.
- **Destino:** `fundamentos/datos-distribuidos/convenciones.md` (**C**) +
  `interoperabilidad/integracion/convenciones.md` (**C**: el cliente que deduplica pide `no-cache`).
- **Autoridad:** RFC 9111 (`Cache-Control: no-cache`); FHIR R5, semántica de búsqueda.
- **Se queda aquí:** el nombre del ajuste de HAPI.

### ADR-0020 · Una regla de seguridad que no casa deja la puerta abierta en silencio

- **Transversal:** el fallo de seguridad más peligroso es el que **no da error**. En Spring Security
  con `spring-webmvc` en el *classpath*, una cadena declarada con un patrón de texto se convierte en
  un emparejador de MVC que **nunca casa** con lo que sirve otro servlet: la cadena se construye, el
  log parece correcto y la API queda abierta. La regla general: **una regla de autorización se afirma
  con un test que pide sin credenciales y exige `401`**, no leyendo la configuración.
- **Destino:** `herramientas/seguridad.md` (**C**: la regla del test) + `stacks/spring/referencia.md`
  (**R**: el mecanismo exacto y cómo se reconoce en el log).
- **Autoridad:** Spring Security 6, `PathPatternRequestMatcher` frente a `MvcRequestMatcher`.
- **Se queda aquí:** que el otro servlet sea el de HAPI.

### ADR-0021 · El charset de un mensaje v2 viaja dentro del mensaje

- **Transversal:** el juego de caracteres se declara **dentro del propio mensaje** (`MSH-18`), así que
  para cuando lo lees ya has decodificado con otro. Tres reglas: se resuelve **una sola vez** en un
  tipo con nombre; la **lista de charsets aceptados es corta y explícita** —soportar lo que la
  librería sabe decodificar es fingir un soporte que nadie ha probado—; y un charset declarado y no
  aceptado **se rechaza**, no se avisa.
- **Destino:** `interoperabilidad/hl7-v2/convenciones.md` (**C**) + `referencia.md` (**R**: la tabla
  0211 y el problema del huevo y la gallina al decodificar).
- **Autoridad:** HL7 V2.5.1, `MSH-18` y tabla 0211.
- **Se queda aquí:** que el valor por defecto sea `ISO-8859-1` (es de un laboratorio español).

### ADR-0022 · El TLS de un canal no se configura con propiedades de la JVM

- **Transversal:** `javax.net.ssl.*` es **global al proceso**: configurar ahí el certificado de un
  canal se lo aplica a todas las conexiones salientes del servicio, incluida la que va al servidor de
  identidad. Un canal tiene **su** `SSLContext`, construido en código desde **su** almacén, con la
  contraseña por variable de entorno. Y **TLS encendido por defecto**: apagarlo es una decisión que
  se escribe.
- **Destino:** `herramientas/seguridad.md` (**C**) + `fundamentos/redes/referencia.md` (**R**: por qué
  el ajuste global parece funcionar hasta que rompe otra cosa).
- **Autoridad:** JSSE Reference Guide (propiedades del sistema son globales al proceso).
- **Se queda aquí:** `hispalis.mllp.tls.*` y la generación del almacén al levantar.

### ADR-0023 · Con una sola partición, una clave de reparto mal elegida parece correcta

- **Transversal:** con una sola partición **el orden sale bien por accidente** y una clave de reparto
  equivocada no se manifiesta; el día que se escala, el fallo aparece en producción y parece nuevo.
  Reglas: la clave es una **decisión de dominio** y se llama por su nombre en el esquema; **más de una
  partición desde el primer día**, aunque no haga falta rendimiento; y **creación automática de
  tópicos desactivada**, para que un nombre mal escrito falle en el acto en vez de nacer solo.
- **Destino:** `fundamentos/datos-distribuidos/convenciones.md` (**C**) + `referencia.md` (**R**).
- **Autoridad:** Apache Kafka, garantía de orden **por partición**; `auto.create.topics.enable`.
- **Se queda aquí:** que la clave sea `pacienteId` y los cuatro tópicos.

### ADR-0024 · El contexto de lanzamiento de SMART no está dentro del testigo

- **Transversal:** el contexto llega **en la respuesta del canje**, no dentro del *access token*, y
  **un cliente no abre un JWT**: si necesita la identidad, la pide al `userinfo` del emisor. Abrirlo
  en el cliente obligaría a traerse el JWKS y a hacer criptografía en un móvil para obtener lo mismo.
  Y la regla que evita el error de concepto: **ese contexto no es control de acceso**; sirve para no
  pedir a ciegas.
- **Destino:** `interoperabilidad/smart-on-fhir/convenciones.md` (**C**) +
  `herramientas/autenticacion.md` (**C**: un cliente no inspecciona el testigo que porta).
- **Autoridad:** SMART App Launch 2.x, parámetros de la respuesta del token; OIDC Core, `userinfo`.
- **Se queda aquí:** la app Flutter y su pantalla.

### ADR-0025 · El retorno de una autorización no se parece en móvil y en web

- **Transversal:** el `redirect_uri` es **por plataforma** y se resuelve **en la configuración de cada
  plataforma**, no con un `if` en el código. En móvil, esquema propio declarado en el manifiesto y en
  el `Info.plist`; en web, una página de retorno del propio proyecto con `postMessage` dirigido al
  origen. El `redirect_uri` **nunca se construye con datos de entrada**.
- **Destino:** `interoperabilidad/smart-on-fhir/convenciones.md` (**C**), `stacks/flutter/
  convenciones.md` (**C**: dónde se declara cada cosa) y `herramientas/autenticacion.md` (**R**).
- **Autoridad:** RFC 8252 (*OAuth 2.0 for Native Apps*), esquemas propios y URIs de redirección.
- **Se queda aquí:** `10.0.2.2` del emulador y los literales de HispaLIS.

### ADR-0026 · Un parámetro que solo falla con el servidor recién cargado

- **Transversal:** dos reglas, y la segunda vale para cualquier CI. **Un estado en disco que
  sobrevive entre ejecuciones convierte una prueba local en una prueba de otra cosa**: cuando algo
  pasa en local y falla en la CI, la primera pregunta es qué había **ya escrito** en el volumen. Y
  **la comprobación previa se escribe con la misma llamada que hace el cliente**, no con una variante
  más barata; si no, solo garantiza que la variante funciona.
- **Destino:** `principios/testing.md` (**C**: las dos) + `herramientas/docker.md` (**R**: el volumen
  que sobrevive) + una línea en `interoperabilidad/terminologia/convenciones.md` sobre `$expand` y
  `count`.
- **Autoridad:** FHIR R5, parámetros `count`/`offset` de `$expand`.
- **Se queda aquí:** el número concreto y el generador.

### ADR-0027 · Una credencial dentro de un recurso que la API sirve es una credencial publicada

- **Transversal:** el error de diseño y su alternativa. Un secreto en un recurso **legible por la
  API** está publicado a todo el que tenga permiso de lectura sobre ese tipo, y además queda en el
  **historial de versiones**, que no se borra. El recurso guarda **el identificador** de la clave; la
  clave vive en la configuración. Y en vez de portador, **se firma el cuerpo**: HMAC sobre
  `<marca-de-tiempo>.<cuerpo>`, con la marca dentro de lo firmado para poder descartar reenvíos, y
  comparación en tiempo constante.
- **Destino:** `herramientas/seguridad.md` (**C** + **R**: es una receta completa de *webhook*
  firmado).
- **Autoridad:** RFC 2104 (HMAC); prácticas de firma de *webhooks*.
- **Se queda aquí:** `Subscription.parameter` y el nombre `his-2026`.

### ADR-0028 · Una regla condicionada no se publica donde el servidor no la sirve

- **Transversal:** **antes de elegir dónde publicar una regla, comprobar que el servidor que la va a
  servir sabe servirla.** El estándar puede tener el sitio perfecto para una condición y la
  implementación no traerlo; publicar ahí deja la regla en un elemento que nadie devuelve y obliga a
  leerla de otro sitio — dos fuentes de verdad para lo mismo.
- **Destino:** `interoperabilidad/terminologia/convenciones.md` (**C**) + `diseno/referencia.md`
  (**R**: la regla general «el sitio correcto del estándar no siempre es un sitio disponible»).
- **Autoridad:** HAPI FHIR 8.10.1, `TranslationQuery`/`TranslationRequest` sin `dependency`, medido.
- **Se queda aquí:** que la regla sea «declarar si el resultado es POS».

### ADR-0029 · Un `SearchParameter` en `draft` se publica y no se indexa

- **Transversal:** **un recurso de conformidad que el servidor tiene que ejecutar se publica en
  `active`, aunque la guía esté en `draft`.** El `status` de un artefacto no es solo documentación:
  hay implementaciones que lo usan para decidir si lo cargan, y el modo de fallo es el peor —se
  guarda, se lee, se publica, y la funcionalidad no existe, sin error y sin aviso—. Lo experimental
  se dice con `experimental`, que es el campo para eso.
- **Destino:** `interoperabilidad/fhir/convenciones.md` (**C**) + `referencia.md` (**R**: el
  mecanismo, con la clase que filtra).
- **Autoridad:** HAPI FHIR 8.10.1, `SearchParamRegistryImpl` y `SearchParameterCanonicalizer`, medido
  con `javap`.
- **Se queda aquí:** el parámetro `vencimiento` sobre `Task`.

### ADR-0030 · La traza del acceso que falla es la que el servidor se niega a guardar

- **Transversal:** en un registro de auditoría, **una referencia literal a algo que no existe hace
  que el servidor rechace la traza entera** — y la traza del acceso fallido es justo la que más falta
  hace. Regla: **literal solo lo que el servidor llegó a devolver**; lógica (`type` + `identifier`)
  todo lo que solo se pidió, y también quien llamó, porque su identidad la afirma el proveedor de
  identidad y no nosotros. Y al revés: **una traza no puede impedir borrar lo que observó**.
- **Destino:** `interoperabilidad/fhir/convenciones.md` (**C**: referencias literales frente a
  lógicas) + `herramientas/seguridad.md` (**C**: la auditoría no puede depender de la integridad
  referencial de lo auditado).
- **Autoridad:** FHIR R5, `Reference` (literal frente a lógica) y `AuditEvent`; integridad referencial
  de HAPI JPA.
- **Se queda aquí:** los caminos concretos donde se desactiva la comprobación al borrar.

### ADR-0031 · Cazar la excepción no deshace el `rollback-only`

- **Transversal:** **un `catch` no limpia la transacción.** Si el método que lanzó es transaccional y
  participaba en la tuya, la transacción ya está condenada cuando te enteras, y el síntoma
  —`UnexpectedRollbackException` al confirmar— aparece lejos de la causa, con el log diciendo que
  todo fue bien. Regla práctica: **dentro de una transacción, «¿existe esto?» se pregunta buscando**,
  que devuelve vacío, no leyendo y cazando.
- **Destino:** `stacks/spring/convenciones.md` (**C**) + `principios/manejo-errores.md` (**C**: no uses
  excepciones de una llamada transaccional como flujo de control) + `referencia.md` (**R**).
- **Autoridad:** Spring Framework, propagación de transacciones y marca *rollback-only*.
- **Se queda aquí:** el `Group` de la cohorte.

### ADR-0032 · El `display` que el FSH no escribe y el `$lookup` no inventa

- **Transversal:** **una referencia entre conceptos transporta identidad —sistema y código—, no
  contenido.** Lo que es de un concepto se pide **a ese concepto**. Cualquier dato leído del `Coding`
  que apunta es una copia, y las copias se quedan viejas o directamente no existen: en FSH,
  `Sistema#CODIGO` compila a un `Coding` **sin `display`**, así que leer de ahí un nombre da `null`.
- **Destino:** `interoperabilidad/terminologia/convenciones.md` (**C**) +
  `interoperabilidad/perfilado-fsh/referencia.md` (**R**: qué genera exactamente `Sistema#CODIGO`).
- **Autoridad:** SUSHI, semántica de `Sistema#CODIGO`; FHIR R5, `Coding.display` como copia.
- **Se queda aquí:** la enfermedad EDO y su plazo.

### ADR-0033 · Autorizar la operación no autoriza la segunda vez que escribe

- **Transversal:** con un motor de autorización que evalúa **recurso a recurso**, hay que enumerar
  cada tipo **y cada verbo** que el camino toca: autorizar una operación no autoriza **lo que la
  operación escribe** (y `create` y `update` son permisos distintos aunque los escriba el mismo
  código), ni autorizar un recurso autoriza **la operación de lectura sobre él**. Con dos corolarios
  de prueba de primer orden: **la segunda vez no es la primera otra vez** —un caso de uso idempotente
  en su resultado no lo es en sus verbos—, y **el cruce de dos configuraciones de test que existen por
  separado no está probado por mucho que ambas estén en verde**.
- **Destino:** `herramientas/seguridad.md` (**C**: enumerar tipo × verbo por camino) +
  `principios/testing.md` (**C**: listar las dimensiones de configuración y buscar las casillas
  vacías) + `interoperabilidad/smart-on-fhir/referencia.md` (**R**: el caso con HAPI).
- **Autoridad:** HAPI FHIR 8.10.1, `AuthorizationInterceptor` y sus *pointcuts*
  `STORAGE_PRESTORAGE_RESOURCE_CREATED` / `_UPDATED`, medido.
- **Se queda aquí:** `$validar`, `$status` y los scopes concretos.

### ADR-0034 · Un código que llega como frase deja de ser un código

- **Transversal:** **una conversión con pérdida en el borde no se nota en el borde.** Colapsar un
  valor codificado en texto funciona en todas las pantallas y rompe tres capas más adentro, donde
  alguien compara códigos. En HL7 v2, `CE`/`CWE` **no es `ST` con adornos**: es la misma distinción
  que en FHIR hay entre `valueCodeableConcept` y `valueString`. Y la regla que evita inventar: un
  código de un vocabulario que no sabes situar **se guarda como texto**, ni se le pone un `system`
  ajeno ni se descarta. Detalle de implementación reutilizable: los componentes de un compuesto de v2
  **se leen por posición**, no por la clase concreta que instancie la librería.
- **Destino:** `interoperabilidad/hl7-v2/convenciones.md` (**C**) +
  `interoperabilidad/integracion/convenciones.md` (**C**: conversiones con pérdida) +
  `principios/testing.md` (**R**: otra casilla vacía, «cualitativo × por el canal v2»).
- **Autoridad:** HL7 V2.5.1, `OBX-2` y tipos `CE`/`CWE`/`CNE`; FHIR R5, `Observation.value[x]`.
- **Se queda aquí:** LEGIOAG, la declaración EDO y el nombre `99HISPCUAL`.

### ADR-0035 · Montar un volumen no es poder escribir en él

- **Transversal:** **un volumen de Docker recién creado es de `root`**, y montarlo da visibilidad, no
  permiso: un contenedor sin privilegios —o *distroless*— se lleva un `AccessDeniedException` en el
  primer arranque limpio. El remedio es un servicio de inicialización que haga `mkdir` + `chown`, y el
  servicio que escribe **espera a que termine bien**; no `user: root`, que es cambiar el problema de
  sitio. Corolario de diagnóstico: **un fallo en un trabajo asíncrono aparece en otro sitio y con otra
  cara** — el primer sitio donde mirar no es la respuesta, es el log del proceso que lo ejecuta.
- **Destino:** `herramientas/docker.md` (**C** + **R**), con el bloque de `compose` copiable.
- **Autoridad:** Docker, propiedad y permisos de los volúmenes al crearse.
- **Se queda aquí:** la ruta de las exportaciones y los UID/GID de esta imagen.

### ADR-0036 · Lo que el parser tira sin decir nada revienta lejos

- **Transversal:** tres, y las tres viajan solas. (1) **Un campo que el deserializador no reconoce se
  pierde sin ruido**, y un `201` no significa que se haya guardado lo que mandaste; al construir un
  recurso a mano hay que comprobar el **nombre exacto** del elemento, y al leer uno ajeno, no dar por
  hecho que un elemento presente traiga valor. (2) **`.map(...).findFirst()` es un
  `NullPointerException` esperando**: `Optional` no admite nulos. (3) **Un `catch` de última instancia
  sin traza convierte un fallo en un misterio**, y **en un bucle que procesa muchos, el fallo de uno
  no puede ser el fallo de la vuelta**.
- **Destino:** `stacks/java/convenciones.md` (**C**: el `filter(Objects::nonNull)` antes de
  `findFirst`), `principios/manejo-errores.md` (**C**: el `catch` de última instancia y la unidad de
  aislamiento) e `interoperabilidad/fhir/referencia.md` (**R**: `value` que no es `value[x]`, y por
  qué el parseo permisivo es correcto aunque duela).
- **Autoridad:** FHIR R5, `Subscription.parameter.value` (`string`, no elección); Javadoc de
  `Optional.of`; HAPI FHIR, manejador de errores permisivo por defecto.
- **Se queda aquí:** el relay de notificaciones y el identificador de clave.

### ADR-0037 · El camino que atiende el fallo también falla

- **Transversal:** seis cosas, y las seis valen fuera de HL7 v2 — es la ficha con más carga de todo
  el dossier. (1) **En un protocolo con acuse, el manejador de errores es código de primera línea**:
  su contrato incluye qué responder cuando no se puede componer una respuesta buena, y hay que
  probarlo con entrada que ni siquiera parsee. (2) **Comprueba qué hace tu librería cuando el
  manejador devuelve nulo** — hay librerías que lanzan y el emisor no recibe nada; hay otras que
  tragan y cierran. (3) ⚠️ **Un servidor que compone su acuse de error con el mensaje de la excepción
  publica su esquema al primero que le mande basura.** Un mensaje de error tiene **dos destinatarios
  que no comparten texto**: el de fuera necesita saber qué hacer, el de dentro qué pasó, y fundirlos
  es exactamente cómo el `getMessage()` de una excepción acaba en la respuesta con la sentencia SQL,
  la ruta del fichero o el nombre del servidor dentro. Vale para **cualquier protocolo con acuse**:
  el cuerpo de un `500`, el `ERR` de un mensaje, el campo de motivo de un rechazo binario. Y no se
  encuentra leyendo código: se encuentra mandando basura y leyendo lo que vuelve. (4) **Al
  inventariar qué está dentro de la red de seguridad, mira lo que ocurre antes de entrar**: registrar,
  autenticar y auditar van delante del `try` por buenas razones, y por eso sus fallos salen por
  caminos que nadie ha diseñado. (5) ⚠️ **Un *fuzzer* contra un parser de red no busca solo caídas:
  busca ausencia de respuesta.** En un protocolo con acuse, **el silencio es peor que un rechazo** —el
  emisor no sabe si llegó, y su lógica de reintento decide a ciegas—, y el criterio completo son
  cuatro cosas: se contesta o se cierra, no se filtra el interior, no se filtra el dato del usuario y
  **el servicio sigue atendiendo al siguiente** (lo último se comprueba mandando algo bueno después de
  cada entrada mala). (6) **Y el arnés hostil miente en las dos direcciones si su lector se escribe a
  ojo** — ver la ficha transversal I.
- **Destino:** `principios/manejo-errores.md` (**C**: los dos destinatarios de un mensaje de error, y
  que el camino de fallo del fallo tiene que contestar), `herramientas/seguridad.md` (**C**: la fuga
  por el acuse de error es divulgación de información, y el sitio donde se comprueba es una entrada
  hostil, no una revisión de código — la línea que hoy dice «nunca expongas mensajes de error con
  detalles internos» se queda corta en el **cómo se detecta**), `principios/testing.md`
  (**C**: los cuatro criterios de un fuzzer, que **no** son «no se cae» — ⚠️ **corrige** la línea
  actual, que enfoca *panics*, UB y desbordes y es la lista propia de código nativo; para un servicio
  de red el fallo que importa es el que no contesta; **R**: por qué la lista de rastros prohibidos
  vive en el test) e `interoperabilidad/hl7-v2/referencia.md` (**R**: el recorrido del enrutador
  cuando la cabecera no se deja leer, y que el lector MLLP mira el charset sobre bytes crudos).
- **Autoridad:** `hapi-base` 2.6.0, `ApplicationRouterImpl.processMessage` verificado con `javap`;
  PostgreSQL, `0x00` no admitido en `text`. Ciento cinco entradas generadas por socket, con semilla
  fija.
- **Se queda aquí:** que la librería sea HAPI y las propiedades del canal con las que se compone el
  `ACK` de último recurso.

### ADR-0038 · Medir la cobertura cambió lo que se medía

- **Transversal:** (1) **Medir la cobertura cambia la transformación del código y con ella lo que
  se mide.** No es un observador pasivo: instrumenta, y con ello puede cambiar rutas, `sourcemaps`,
  identidad de módulos y tiempos. **Hay tests que solo pasan sin instrumentar**, y un test que pasa
  sin cobertura y falla con ella no es un fallo de la herramienta: es un test que dependía del
  empaquetado y que se habría roto solo con la siguiente versión del constructor. El corolario
  incómodo es que **la suite «verde» y la suite «medida» no son la misma suite**, así que la primera
  medición se hace pronto, no el día que se quiere un número. (2) **`__dirname` e `import.meta.url`
  no son fiables dentro de un test empaquetado**: si un test lee un fichero, la ruta se ancla a algo
  que el ejecutor garantice —el directorio de trabajo— o se inyecta. (3) **La primera medición de
  cobertura es un test del arnés antes que del código** — ficha transversal I.
- **Destino:** `principios/testing.md` (**C**: anclar rutas de fichero en tests empaquetados, y medir
  cobertura pronto porque cambia lo medido; **R**: la primera medición como revisión del arnés) y
  `stacks/angular/referencia.md` (**R**: el caso concreto, con el constructor y el proveedor `v8`).
  La regla del empaquetado, sin el constructor concreto, va a `stacks/typescript/convenciones.md`
  (**C**) — cualquier ejecutor que empaquete el test tiene el mismo problema.
- **Autoridad:** medido en este repositorio con `@angular/build` 22 y `vitest` 4: los mismos tres
  tests, verdes sin `--coverage` y `ENOENT` con él.
- **Se queda aquí:** que la fuente de verdad de los `system` sea `ig/input/fsh/aliases.fsh`. La parte
  de este ADR que hablaba de **qué encontró** la medición se ha separado en `adr-0041`: son dos
  lecciones distintas y la segunda no es de TypeScript.

### ADR-0039 · Una edición de SNOMED no se descarga: se compone

- **Transversal:** (1) **«Una release» puede ser varias.** La Edición Española de SNOMED CT son
  **tres** descargas —Internacional (conceptos), *Spanish Edition* (**solo** descripciones) y
  extensión nacional— a **tres cadencias** distintas, y no valen las últimas de cada una: hay que
  coger las versiones ancladas que el Área de Descarga publica como *«Dependencia EE SNS»*. Antes de
  escribir el lector de un formato, mirar cómo se **distribuye** el producto. (2) **Un `[-1]` sobre un
  `glob` es una decisión disfrazada de detalle**: «el último» solo significa algo si el orden
  significa algo, y el orden alfabético de unas carpetas que elige el usuario no significa nada.
  (3) **Un valor por defecto que rellena un hueco esconde el hueco** — `display or fsn or codigo`
  publicó el número del código como nombre, sin excepción y sin aviso. (4) **En terminología
  licenciada la versión es parte del dato**, y se deduce del contenido, no se escribe a mano. (5) **Un
  arnés sintético prueba el formato, no el producto**: la mini-*release* tenía un fichero por tabla
  porque así se escribió, y ese detalle invisible era la premisa que fallaba.
- **Destino:** `interoperabilidad/terminologia/` (**C**: leer todos los ficheros de una release
  compuesta y deducir la versión del *refset* de dependencias; **R**: los tres modos de fallo
  medidos), `interoperabilidad/espana/` (**R**: los tres productos del Área de Descarga, las entradas
  de dependencia y que la licencia de afiliado es gratuita para quien reside y trabaja en España),
  `principios/manejo-errores.md` (**C**: donde el hueco importa, el defecto es el error) y
  `principios/testing.md` (**R**: el arnés sintético que fija una premisa falsa).
- **Autoridad:** documentación del Centro Nacional de Referencia de SNOMED CT para España, edición
  20260601 (notas para la versión y guía del Área de Descarga, archivadas en `_fuente/_externos/` de
  la biblioteca). Los tres modos de fallo, reproducidos en `terminologia/tests/test_snomed.py`.
- **Se queda aquí:** que este proyecto cargue un `CodeSystem` fragmentario y que la variable se llame
  `HISPALIS_SNOMED`.

### ADR-0040 · Un refset oficial se referencia, no se copia

- **Transversal:** (1) **Antes de escribir una lista de códigos, buscar si la autoridad ya la
  publica** — no para copiarla, sino para **declarar la relación** con ella; una lista local sin
  relación declarada es una copia que nadie va a sincronizar. (2) **«Está en la lista oficial» es
  comprobable, y donde no se comprueba es una creencia**; el sitio de la comprobación es el punto por
  el que el contenido entra. (3) **Un refset oficial dice qué es un concepto, no qué hacer con él**:
  confundir vocabulario con política acaba con reglas de negocio escondidas dentro de un `ValueSet`.
  (4) **Un refset con cero miembros puede estar avisando**, no roto: es la ruta de migración antes de
  inactivarlo. (5) **La configuración que nadie usa todavía es la que más falla**, y falla el día que
  alguien va a usarla por primera vez.
- **Destino:** `interoperabilidad/terminologia/` (**C**: la sintaxis del `ValueSet` implícito
  —`?fhir_vs=refset/…`—, que la URI de edición no es un `system`, y que un subconjunto local se
  declara contra el refset oficial; **R**: la forma de intersección de `compose.include` y por qué
  aquí no vale), `interoperabilidad/espana/` (**R**: qué publica la extensión del SNS —80 refsets,
  ligados al CMDIC del RD 1093/2010— y qué advierten sus fichas) y `principios/testing.md` (**C**:
  probar también la configuración que todavía no usa nadie).
- **Autoridad:** fichas de los conjuntos de referencias de la extensión 20260601 (CNR); FHIR R5,
  `ValueSet.compose.include` («si se listan varios conjuntos, el código tiene que estar en todos»,
  verificado en `hl7.fhir.r5.core@5.0.0`) y la página de SNOMED CT del estándar para las cinco formas
  del `ValueSet` implícito.
- **Se queda aquí:** los diez tipos de muestra concretos de este laboratorio y su catálogo de EDO
  simulado.

### ADR-0041 · Un cero redondo de cobertura suele ser una regla duplicada

- **Transversal:** (1) ⚠️ **La cobertura sirve por los ceros redondos, no por el porcentaje.** Entre
  un 89 % y un 91 % no hay información; en un 0 % sobre un método con nombre de regla de negocio, toda
  — y el porcentaje no se mueve cuando el fallo está dentro. (2) **Código que nadie llama rara vez
  sobra: casi siempre hay otra copia de la misma regla, y la copia en uso es la peor.** La versión
  extraída se escribió a propósito, pensando en el caso general; la que se quedó en línea la escribió
  quien tenía prisa. Al arreglarlo se borra la de dentro y se llama a la compartida, **nunca al
  revés**. (3) **Un cero tiene cuatro diagnósticos y hay que escribir cuál es** —regla duplicada,
  regla redundante que impone otro de verdad, camino real sin test, inalcanzable a propósito—; el
  cuarto se anota **con su motivo** o se vuelve a discutir en cada medición. (4) **Al borrar código
  muerto bien documentado, el comentario vale más que el código**: se muda a donde la regla se aplica.
  (5) **Un umbral de cobertura sustituye la pregunta por la métrica**, y la forma barata de subir el
  número son tests que recorren sin afirmar.
- **Destino:** `principios/testing.md` (**C**: cómo se lee una medición de cobertura y los cuatro
  veredictos de un cero; **R**: los tres ceros con su diagnóstico, que es lo que hace creíble la
  regla). ⚠️ **Matiza** la línea actual «usa cobertura para revelar huecos, no como fin en sí mismo»,
  que dice **para qué** y no dice **cómo se lee**, que es donde está todo el valor.
- **Autoridad:** medición única por componente sobre seis componentes y cuatro herramientas distintas
  (JaCoCo, `coverage`, `vitest`+v8, `flutter test --coverage`); los tres ceros y sus arreglos, en
  rojo→verde.
- **Se queda aquí:** los nombres de los tres métodos y que uno de ellos tuviera consecuencias de
  declaración obligatoria.

### ADR-0042 · Una propiedad mal enunciada da un rojo que es del test

- **Transversal:** (1) **Un rojo de *property-based* es, muchas veces, del enunciado.** El reflejo es
  tocar el código; la primera pregunta es qué afirma exactamente la propiedad. «Idempotente» es la
  palabra que más se enuncia mal: dice que **el estado final no cambia**, no que no se escriba.
  (2) **Reenunciar una propiedad solo vale si la nueva afirma más.** Debilitarla para verla en
  verde es silenciar lo único que estaba señalando algo; aquí pasó de contar escrituras a comparar el
  estado final completo. (3) **No escribas la función para poder probarla.** El catálogo de
  propiedades canónicas —ida y vuelta, idempotencia, orden— invita a buscar en el sistema una
  transformación que encaje, y la que encaja puede no existir: elegir la que el sistema **sí** hace es
  el trabajo. (4) **Que una propiedad salga verde a la primera es un resultado y se anota**: mide que
  el invariante estaba bien entendido. (5) **El *shrinking* se puede sustituir, no ignorar** —semilla
  fija e impresa, un caso por test con nombre propio, contraejemplo renderizado entero—; lo que no
  vale es un rojo que diga «falló con una de las treinta entradas».
- **Destino:** `principios/testing.md` (**C**: diagnosticar el enunciado antes que el código, y la
  regla de que solo se reenuncia hacia arriba; **R**: el caso de la idempotencia y la propiedad
  descartada). ⚠️ **Matiza** la línea actual de *property-based*, que da por supuesto el *shrinking*
  y propone el *round-trip* como ejemplo canónico — que es justo el que invita a inventarse la
  función.
- **Autoridad:** cuatro propiedades sobre tres canales, con generadores propios de nombres españoles
  (dobles apellidos, partículas, `Ñ`, tildes, `ç`) y semilla fija.
- **Se queda aquí:** los canales concretos, y que la propiedad descartada fuera la de la banda
  magnética de la tarjeta sanitaria.

---

## Dos fichas que no salen de un solo ADR

Son las dos aportaciones más transversales del proyecto y **ninguna es de un ADR**: cada una aparece
en cuatro o cinco, siempre como una línea al final, y el patrón solo se ve al ponerlas juntas. Se
destilan como ficha propia porque en la biblioteca serían una regla, no cinco anécdotas.

### I. El arnés también es código, y miente en las dos direcciones

| | |
|---|---|
| **Qué es transversal** | El arnés de pruebas —el generador, el emisor, el lector de la respuesta, la *release* sintética, el ejecutor con cobertura— **no tiene arnés a su vez**, y por eso sus fallos son invisibles: hacia arriba inventa rojos que no existen, hacia abajo tapa los que sí. Cuatro casos medidos: un lector de acuses que daba por hecho el separador de campos, cuando el mensaje atacante puede **redefinirlo**; ese mismo lector contando como silencio una conexión cerrada, que es lo contrario de un silencio; una *release* sintética con un fichero por tabla —porque así se escribió— que fijó como premisa del lector algo que el producto real no cumple; y tres tests verdes por casualidad que solo se cayeron al instrumentar. |
| **La regla** | (1) **El arnés no da por hecho nada del formato que ataca**: si la entrada puede redefinir la sintaxis, el oráculo no puede llevarla escrita. (2) **«Sin respuesta» es un estado del arnés antes que del sistema**: silencio, tiempo agotado y conexión cerrada son tres cosas distintas y solo una es el fallo que se persigue. (3) **Un arnés escrito a la vez que el código comparte sus premisas**, así que no puede validarlas: la premisa se comprueba contra la distribución real o contra la documentación del producto, nunca contra la *fixture*. (4) **Al cambiar de instrumento —cobertura, entrada generada, volumen— lo primero que se mide es el arnés**, y encontrar fallos en él es el resultado esperado, no un contratiempo. |
| **Dónde iría** | `principios/testing.md` (**C**: las cuatro; es material nuevo — el fichero habla de qué prueba escribir y no de que la infraestructura de prueba sea código sin probar) + **R** con los cuatro casos. |
| **Autoridad** | Medido en este proyecto en cuatro sitios independientes: `adr-0037` (los dos fallos del oráculo), `adr-0038` (los tres verdes por casualidad), `adr-0039` (la *release* sintética) y `adr-0042` (la reproducibilidad comprada sin *shrinking*). |
| **Se queda aquí** | Los nombres del emisor crudo, del generador de nombres y de las mini-*releases*. |

### II. Lo que no ejecuta nadie todavía es lo que peor falla

| | |
|---|---|
| **Qué es transversal** | Cinco fallos del proyecto son **el mismo fallo**: algo escrito, revisado, plausible y en verde que **nada ejecuta**, y que por eso nadie descubre hasta el día en que hace falta. Un interceptor registrado en el registro equivocado —que no da ningún error: sencillamente no se llama nunca, y el sistema *parece* protegido—; una regla de autorización cuyo emparejador no casa con lo que sirve el otro servlet, con la cadena construida, el log correcto y la API abierta; un recurso de conformidad publicado en `draft`, que se guarda, se lee y no se indexa; dos URI de terminología que el compilador acepta y ningún servidor resuelve, en un alias que no usa ningún perfil; y los ceros redondos de cobertura. **Ninguno da error y cuatro de los cinco parecen correctos en el log.** |
| **La regla** | (1) **La declaración no es el efecto.** Una configuración está activa cuando algo observable lo demuestra, y la afirmación se escribe **por el efecto y por el camino de verdad**: pedir sin credenciales y exigir el `401`, buscar por el parámetro y exigir que el índice lo use, resolver la URI contra el servidor que la va a resolver. Leer la configuración, o verla en el log, no es la prueba. (2) **Lo que todavía no usa nadie se prueba igual**, porque el día que se use será el día de más prisa. (3) El modo de fallo compartido es **el silencio**, así que la pregunta operativa no es «¿está bien escrito?» sino «¿qué se rompería si esto no estuviera?» — y si la respuesta es «nada, hoy», ahí está el agujero. |
| **Dónde iría** | `principios/testing.md` (**C**: (1) y (2)) + `herramientas/seguridad.md` (**C**: generaliza lo que hoy sólo está dicho para autorización — el `401` es un caso de la regla, no la regla) + `diseno/referencia.md` (**R**: el catálogo de los cinco, que es lo que la hace creíble). |
| **Autoridad** | `adr-0014`, `adr-0020`, `adr-0029`, `adr-0040` y `adr-0041`, los cinco medidos y arreglados en rojo→verde. |
| **Se queda aquí** | Los cinco casos con su librería y su nombre de clase. |

---

## Lo que NO aporta nada, y conviene decirlo

Decir dónde **no** hay lección vale tanto como decir dónde la hay: evita que alguien recorra un
componente entero buscando una trampa que no existe, y evita que la biblioteca reciba relleno.

- **`interoperabilidad/bulk-data/`.** El proyecto implementó `$export` entero —asíncrono, sondeo,
  manifiesto, billetes opacos, caducidad y barrendero— y **no ha encontrado ninguna trampa que la
  biblioteca no tenga ya**. Lo que sí sacó de ahí son dos hallazgos que **no son de Bulk Data**: el
  permiso del volumen (`adr-0035`, va a Docker) y la forma de la regla de autorización (`adr-0033`,
  va a seguridad). Anotarlo así evita que alguien busque en `bulk-data` una lección que está en otro
  sitio.
- **`stacks/python/`.** Los dos componentes en Python —el cargador de terminología y los generadores—
  produjeron dos ADR (`0039`, y su parte en `0026`) y **ni una sola lección de Python**. Lo del
  cargador es del formato de distribución de SNOMED, y le habría pasado igual escrito en Java. Quien
  vaya a buscar allí la trampa de un `rglob` la encontrará en `interoperabilidad/terminologia/`, que
  es donde está.
- **`fundamentos/concurrencia/`.** El único fallo de concurrencia del proyecto —un test intermitente
  una vez de cada tres, con la traza compitiendo con el borrado— **confirma** la regla que el fichero
  ya tiene («una prueba *flaky* es un bug, no algo tolerable») y no la corrige. Lo que sí era nuevo
  era la causa, y esa vive en `adr-0030`. Confirmar es un resultado y no genera edición.
- **`ux-ipo/`.** Hay una web en Angular y una app en Flutter, y **ninguna de las dos ha dejado nada**:
  son funcionales, no diseñadas, y sus decisiones de interfaz no se tomaron —se heredaron del camino
  más corto—. Un dossier honesto no puede convertir eso en una regla de usabilidad.
- **`interoperabilidad/espana/`** recibía **un solo** ADR (`0003`) y ahora recibe **tres**. Lo que no
  ha cambiado es el motivo de que fuera uno: lo español que el proyecto tocó al principio —NUHSA,
  apellidos dobles, INE, DNI— **ya estaba escrito** en la biblioteca, y el proyecto lo confirmó sin
  corregirlo. Los dos nuevos (`0039`, `0040`) llegan por otra puerta: **documentación oficial que el
  proyecto no tenía** hasta el 2026-08-15, la del Centro Nacional de Referencia de SNOMED CT para
  España. La lección de método es esa —el hueco no estaba en lo que se había hecho, sino en lo que no
  se había leído.
- **Y una advertencia sobre `principios/testing.md`, que es el destino más cargado.** Doce entradas no
  significan doce reglas nuevas: tres de ellas **corrigen o matizan** líneas que ya existen (el
  criterio de un *fuzzer*, el *shrinking* como requisito de *property-based*, y cómo se lee una
  medición de cobertura) y esas tres son las que más cuesta curar, porque hay que reescribir sin
  contradecir. Meterlas como líneas nuevas al lado de las viejas dejaría el fichero diciendo dos cosas
  a la vez.

## Cómo se usa este dossier

1. Se lee **fichero a fichero**, no ADR a ADR: la tabla de arriba agrupa por destino porque una
   sesión de curación edita un `convenciones.md` y su `referencia.md` a la vez.
2. Cada regla candidata se contrasta con lo que el fichero **ya dice**. Varias fichas son
   confirmaciones o matices de reglas existentes, no reglas nuevas; meterlas dos veces empeora el
   fichero.
3. Lo que entre en `convenciones.md` va en **imperativo y sin justificación** —la justificación es lo
   que hace que un `convenciones.md` deje de poder leerse entero— y con un enlace al `referencia.md`
   hermano.
4. La **autoridad se cita en `referencia.md`**, con versión. «Medido sobre HAPI 8.10.1» envejece
   distinto que «lo dice la norma», y quien lo lea dentro de dos años necesita saber cuál de las dos
   es.
5. **Lo marcado con ⚠️ corrige o matiza algo que el fichero ya dice**, y no se cura como una línea
   más: hay que reescribir la que está, porque dejar las dos convierte el `convenciones.md` en un
   fichero que se contradice. Son pocas y están todas señaladas.
6. **`principios/testing.md` se cura al final**, cuando ya se sabe qué han dejado los demás: es el
   destino de doce fichas y de las dos transversales, y curarlo por partes produce repeticiones que
   solo se ven leyéndolo entero.
