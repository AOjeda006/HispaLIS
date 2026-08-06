---
tipo: referencia
stack: [java, spring]
aplica_a: [backend, integracion]
revisado: 2026-08-06
tags: [adr, hapi, fhir, cache, idempotencia, read-your-writes, integracion, hl7-v2]
---

# ADR-0019: Una búsqueda cacheada convierte la idempotencia en una ilusión

- **Estado:** aceptado
- **Fecha:** 2026-08-06

## Contexto

El motor de integración no tiene transacción distribuida contra la API del laboratorio (D22): escribe
recurso a recurso, y la atomicidad se recupera **reprocesando**. Para que reprocesar no duplique, cada
canal hace lo mismo antes de escribir:

1. buscar el recurso por su **clave de negocio** (`ServiceRequest` por volante + código de prueba,
   `Specimen` por número de acceso, `Observation` por muestra + código);
2. si no está, crearlo;
3. si está, no tocar nada.

Es el patrón estándar y no depende de ningún registro privado del motor: la única fuente de verdad de
«esto ya está escrito» es la propia API.

**El servidor FHIR de HAPI reutiliza durante 60 segundos el resultado de una búsqueda ya vista.** Es
el valor por omisión de `JpaStorageSettings.getReuseCachedSearchResultsForMillis()`, y en la ejecución
de extremo a extremo de este proyecto se comportó así, de forma determinista:

```
t0 22:28:46.133  GET /fhir/Patient?identifier=…|70000888   → total 0
t1 22:28:46.252  POST /fhir/Patient                        → 201 Created
t2 22:28:46.345  GET  (la misma búsqueda)                  → total 0
t3 22:28:46.438  GET  (la misma búsqueda)                  → total 0
t4 22:28:46.533  GET  (la misma búsqueda)                  → total 0
```

La misma búsqueda con `Cache-Control: no-cache` devuelve 1 inmediatamente, y `hfj_search` deja las dos
filas a la vista: la del conjunto vacío que se reutiliza, y la que sí ejecuta.

El paso 1 del canal es exactamente lo que llena ese caché. Es decir: **el propio acto de comprobar que
algo no existe garantiza que, durante el minuto siguiente, el sistema siga diciendo que no existe** —
también después de haberlo creado. Un reproceso dentro de esa ventana escribe el duplicado que el
patrón existe para evitar, y lo hace en silencio.

Lo que hizo el fallo invisible durante todo el desarrollo del motor: sus tests escriben contra un
doble de la API del laboratorio, y **un doble no cachea**. Los 72 tests del motor pasaban en verde
mientras la propiedad que dicen probar no se sostenía contra el servidor de verdad. Apareció al
levantar backend, motor y simuladores a la vez: el `OML^O21` se rechazó con «el paciente no está
registrado» un décimo de segundo después de que el `ADT^A01` lo diera de alta.

## Decisión

**Dos capas, cada una por su motivo:**

1. **El laboratorio apaga el caché de búsquedas** — `setReuseCachedSearchResultsForMillis(null)` en
   `ConfiguracionServidorFhir`. *Read-your-writes* es un invariante del sistema (§9 del diseño) y no
   dice «el `GET` al `Location` funciona»: dice que ninguna lectura puede ir por detrás de una
   escritura ya confirmada. **Una búsqueda es una lectura.**
2. **El motor pide `Cache-Control: no-cache` en todas sus búsquedas** — un único punto en
   `ApiFhirHapi`. Todas las búsquedas de ese cliente son de idempotencia, y ninguna tolera un
   resultado de hace un minuto. El motor no puede *suponer* la configuración del servidor con el que
   habla: si algún día habla con uno que cachea, el reproceso duplicaría sin avisar.

No son dos arreglos del mismo fallo: el primero es una propiedad que el laboratorio garantiza a todos
sus clientes; el segundo es un requisito que el motor declara en el cable en vez de darlo por hecho.

## Consecuencias

- El reproceso idempotente pasa a serlo también contra el servidor real, no solo contra el doble.
- Se pierde el caché de búsquedas. A la escala de este laboratorio no compra nada: las búsquedas son
  por clave de negocio, con índice, y devuelven cero o un recurso.
- **Un doble de la API no prueba las propiedades del servidor real.** El motor sigue teniendo sus
  tests contra el doble —son rápidos y prueban el mapeo—, pero la ejecución de extremo a extremo con
  los procesos de verdad deja de ser una demostración y pasa a ser parte de la verificación.
- Queda un cabo sin atar, dicho aquí porque no conviene que se olvide: **no se consiguió reproducir el
  fallo en la suite de integración del backend.** El mismo `busca → crea → busca` contra el servidor
  arrancado por `@SpringBootTest` devuelve el recurso, con la fila de `hfj_search` en `FINISHED` y con
  la misma cadena de consulta normalizada; no se averiguó qué hace que la ruta de reutilización de
  HAPI no se active ahí. El test se quedó igualmente, describiendo la propiedad que hay que conservar,
  con el aviso de que no fue él quien vio el fallo.

## Alternativas consideradas

- **Que el motor lleve su propio registro de «esto ya lo escribí»** (una tabla con la clave de negocio
  y la referencia devuelta). Descartada: duplica la fuente de verdad. Ese registro y la API se
  desincronizan en cuanto alguien borra o corrige un recurso por otro camino, y entonces el motor cree
  que existe algo que ya no está. La API es la fuente de verdad de qué hay escrito, y preguntárselo a
  ella es lo correcto — lo que había que arreglar es que respondiese con datos frescos.
- **Bajar la ventana del caché a unos pocos milisegundos** en vez de apagarla. Descartada: convierte
  un fallo determinista en uno intermitente, que es peor. Y no hay ningún valor honesto que elegir: la
  ventana correcta para una lectura que decide si escribir es cero.
- **Solo `Cache-Control: no-cache` en el motor**, dejando el servidor como está. Descartada: el web
  del profesional y cualquier cliente futuro tienen el mismo problema, y el invariante es del sistema,
  no de un cliente.
- **Solo apagarlo en el servidor**, sin la cabecera del motor. Descartada por poco: es suficiente
  hoy, pero deja la corrección del motor dependiendo de una configuración que vive en otro repositorio
  lógico y cuyo valor por omisión es justamente el peligroso.
