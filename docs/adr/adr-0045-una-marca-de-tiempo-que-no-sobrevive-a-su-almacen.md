---
tipo: referencia
stack: [java, postgresql, fhir]
aplica_a: []
revisado: 2026-08-20
tags: [adr, testing, intermitentes, precision, timestamp, proyeccion, reconciliacion]
---

# ADR-0045: Una marca de tiempo que no sobrevive a su almacén divergirá, una de cada dos mil veces

- **Estado:** aceptado
- **Fecha:** 2026-08-20

## Contexto

Un rojo intermitente en el *gate* del backend. `ReconciliacionDelLaboratorioEnteroTest` escribe
sesenta pacientes por la API, recorriendo el circuito entero, y afirma que el barrido del laboratorio
no encuentra ninguna divergencia nueva. En una ejecución de CI encontró **una**: un
`DiagnosticReport` que nadie había tocado, y que seguía ahí en el barrido siguiente. Las dos
afirmaciones de la clase cayeron por el mismo recurso.

El mensaje de fallo decía **qué** referencia sobraba y nada más. Con un `UUID` suelto no se puede
distinguir el único par de diagnósticos que importa —si el recurso es del corpus que el test acaba de
escribir, y entonces hay un fallo en el camino de escritura; o si es ajeno, y entonces el fallo es de
aislamiento del test—, y son diagnósticos opuestos que llevan a arreglos opuestos. El rojo pasó **dos
ejecuciones sin diagnosticarse**, y la segunda se dio por buena porque un commit que no tocaba
`backend/**` salió verde: el rojo no se había corregido, se había movido.

La tentación era acotar la afirmación al corpus propio y seguir. Habría funcionado, y habría
silenciado un fallo real de la proyección: exactamente contra lo que avisa `adr-0042`.

### El mecanismo

Un instante recorre tres sitios y cada uno tiene su precisión:

| | Precisión | Qué hace con lo que sobra |
|---|---|---|
| `Instant.now()` | nanosegundos | — |
| `timestamptz` de PostgreSQL | microsegundos | **redondea** |
| `instant` de FHIR | milisegundos | trunca al publicar |

La emisión publica el `DiagnosticReport` a partir del agregado **en memoria**, con la marca tal y
como salió del reloj. El reconciliador lo regenera a partir del agregado **releído de la base**. Si
el reloj cae en el último medio microsegundo de un milisegundo, el redondeo del almacén cruza la
frontera del milisegundo, y las dos proyecciones del mismo recurso se separan en uno:

```
memoria  …T12:00:00.123999600Z   →  publicado  "issued": "…T12:00:00.123+02:00"
base     …T12:00:00.124000000Z   →  regenerado "issued": "…T12:00:00.124+02:00"
```

Son **500 ns de cada millón: uno de cada dos mil** recursos fechados. En el circuito hay dos por
paciente publicados como `instant` —`DiagnosticReport.issued` y `Provenance.recorded`—, así que un
barrido de sesenta pacientes lo pisa **una vez de cada diecisiete**. `Observation.effective` y
`ServiceRequest.authoredOn` no están expuestos: se publican como `dateTime`, que HAPI emite con
precisión de segundo.

**La transacción no tiene nada que ver.** La proyección se sigue escribiendo dentro de la
transacción del dominio (D3, `adr-0012`), y eso no cambia. Lo que no se sostenía era el corolario que
nadie había escrito: que por escribirse a la vez tengan que **decir** lo mismo. Una de las dos copias
venía de la memoria y la otra de la base, y en medio había una conversión con pérdida.

### Por qué no se reproducía en local

Porque el reloj de la máquina de desarrollo no llega a la franja. Medido: `Instant.now()` en este
Windows devuelve **17 valores distintos** de la parte por debajo del milisegundo en 300 000 muestras,
todos alrededor de 284 µs, y **ninguno** en la franja. En Linux —el runner de CI— `clock_gettime`
devuelve los nueve dígitos y la franja se alcanza con la probabilidad de arriba. El fallo era, por
construcción, **imposible de reproducir en el portátil y solo visible en CI**.

## Decisión

1. **Un agregado no guarda más precisión de la que su almacén sabe devolver.** La marca nace ya en
   milisegundos —la única de las tres precisiones que da lo mismo a la ida y a la vuelta—, en
   `MarcaDeTiempo.publicable(…)`, y por ahí pasan `Informe.emitir` y `Validacion.por`. Se trunca
   hacia abajo, nunca hacia un futuro que no ocurrió.
2. **Se arregla el sistema, no el test.** `ReconciliacionDelLaboratorioEnteroTest` no cambia ni una
   afirmación: hizo su trabajo, que era encontrar esto.
3. **El fallo se diagnostica solo la próxima vez.** Cuando una de las dos afirmaciones de esa clase
   reviente, el mensaje dice de cada referencia inesperada si es **del corpus** —y de qué escenario—,
   qué **clase** de divergencia es y **en qué capa existe**, medido aparte y no deducido de la clase.
   Contrastar las dos cosas es lo que distingue «el recurso no está» de «el recurso está y la
   búsqueda no lo encuentra».
4. **Un intermitente se cierra provocándolo a mano.** `LaProyeccionSobreviveALaIdaYVueltaTest` pone
   la marca en la franja mala a propósito y comprueba que las dos proyecciones del mismo recurso
   coinciden. No depende del reloj, así que corre igual en Windows y en Linux, y falla siempre si
   alguien deshace el arreglo.

## Consecuencias

- El laboratorio fecha lo que publica en milisegundos, y punto. Es la precisión que FHIR publica de
  todas formas, así que no se pierde nada visible: lo que se pierde son dígitos que el almacén no iba
  a devolver.
- `Validacion.por` truncaba también lo que llega de fuera, y un test que comparaba la fecha de la
  firma con el `Instant` que le había pasado dejó de valer tal cual. Se reenunció **hacia arriba**:
  ahora afirma la fecha *y* que no lleva precisión que no sobreviva.
- Las otras dos marcas que **sí** entran en el recorrido del reconciliador —`Peticion.solicitadaEn`
  y `Medicion.realizadaEn`— siguen sin truncar, y no divergen porque se publican como `dateTime`, que
  HAPI emite con precisión de **segundo**: el redondeo del almacén no llega a verse. Eso las salva
  hoy, pero las salva el tipo de FHIR elegido y no una garantía del dominio. **El día que una de las
  dos se publique como `instant`, tiene que pasar por `MarcaDeTiempo`**, y ese es el motivo de que la
  regla esté escrita en un sitio con nombre en vez de resuelta con un `truncatedTo` suelto.
- El resto de `Instant.now()` del sistema —la traza de acceso, la declaración a Salud Pública, el
  *outbox*, las notificaciones y las exportaciones— quedan fuera de esta regla porque **no los
  reconcilia nadie**: no hay agregado contra el que compararlos.
- El diagnóstico del fallo recorre el dominio entero para responder si un `DiagnosticReport` existe.
  Es caro y da igual: solo se ejecuta cuando algo ya ha fallado.

## Alternativas consideradas

- **Acotar la afirmación del test al corpus propio.** Es lo que pedía el cuerpo. Habría puesto la
  suite en verde sin tocar el defecto, y el defecto es que la vía oficial de recuperación **inventa
  divergencias**: en modo reparación reescribe recursos que están bien y caduca los `ETag` de sus
  clientes sin motivo.
- **Releer el agregado después de guardarlo y proyectar desde la copia releída.** Arregla el síntoma
  con una consulta de más en cada escritura, y deja el agregado en memoria diciendo algo distinto de
  lo que hay en la base: la misma trampa, movida de sitio.
- **Truncar en el traductor, al pasar a FHIR.** No arregla nada. `Date.from` ya trunca; el que
  redondea, y hacia arriba, es el almacén.
- **Truncar en cada repositorio, al escribir.** Pone una decisión de modelo en seis sitios de
  infraestructura y deja al agregado creyendo que tiene nanosegundos.
- **Ignorar el campo `issued` al comparar.** Un reconciliador que no mira la fecha de emisión no
  detecta un informe republicado con otra fecha, que es justo un caso que sí importa.
- **Reintentar, `@RepeatedTest` o marcarlo como conocido.** El proyecto tiene escrito que una prueba
  intermitente es un bug, y este lo era.

## Lo reutilizable

1. **Una marca de tiempo que cruza una frontera de persistencia tiene tres precisiones, no una.** La
   del reloj, la del almacén y la del formato de salida. Si no coinciden, el valor no es el mismo a
   la ida que a la vuelta, y cualquier comparación entre las dos copias fallará **a veces**. La regla
   es guardar en la más estrecha, desde el principio.
2. **`timestamptz` redondea, no trunca.** Es el detalle que convierte una pérdida de precisión
   inofensiva en un salto de un milisegundo, y el que hace que el fallo aparezca solo cuando el reloj
   cae en el último medio microsegundo.
3. **Escribir dos copias en la misma transacción garantiza que estén las dos, no que digan lo
   mismo.** Si una sale de la memoria y la otra de la base, la atomicidad no las iguala.
4. **Un intermitente que no se reproduce en local puede ser una diferencia de reloj, no de carga.**
   La resolución de `Instant.now()` no es la misma en Windows que en Linux, y medirla es una línea.
   Antes de culpar a la concurrencia, mirar de qué está hecho el valor.
5. **Un mensaje de fallo que no dice de dónde sale lo que sobra cuesta ejecuciones enteras.** La
   pregunta que hay que dejar respondida de antemano es «¿esto es mío o es de otro?»: es la que
   separa un fallo del sistema de un fallo de aislamiento, y sin ella no se puede elegir el arreglo.
6. **Un verde de un commit que no toca el componente no es un verde.** El rojo de aquí sobrevivió una
   ejecución porque el commit siguiente solo cambiaba un comentario del `pom.xml` y la CI filtra por
   rutas. Para dar por cerrado un intermitente hay que ver el trabajo **ejecutado**.
