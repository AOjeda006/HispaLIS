---
tipo: referencia
stack: [python]
aplica_a: [terminologia]
revisado: 2026-08-15
tags: [adr, terminologia, snomed, rf2, espana, licencias, versionado]
---

# ADR-0039: Una edición de SNOMED no se descarga: se compone

- **Estado:** aceptado
- **Fecha:** 2026-08-15

## Contexto

El cargador de terminología de este proyecto lee la Edición Española de SNOMED CT de una carpeta a la
que apunta `HISPALIS_SNOMED`, y buscaba cada tabla RF2 con un patrón:

```python
def _fichero(raiz: Path, patron: str) -> Path:
    encontrados = sorted(raiz.rglob(patron))
    ...
    return encontrados[-1]          # ← uno
```

La premisa —«una release es una carpeta con una tabla de conceptos, una de descripciones y un
*refset* de idioma»— es la que enseña cualquier tutorial de RF2, y es la que sostienen las
mini-*releases* sintéticas contra las que se probó. **Es falsa para cualquier edición nacional.**

La documentación del Centro Nacional de Referencia de SNOMED CT para España lo dice sin ambigüedad:
la *Spanish Edition* **solo contiene descripciones** —los conceptos y las relaciones viven en la
*International Edition*— y la Extensión para España del SNS añade sus propios conceptos y
descripciones complementarias sobre las dos. Son **tres descargas**, en el mismo Área de Descarga y
bajo la misma licencia de afiliado, que se descomprimen juntas.

Y no valen las últimas de cada una, porque van a cadencias distintas: internacional **mensual**,
española **trimestral**, extensión del SNS **semestral**. La edición del SNS de 20260601 declara
alinearse con la internacional de **20260401** y la española de **20260510**; por eso el Área de
Descarga publica, además de lo más reciente, las entradas *«(Dependencia EE SNS)»*, que son las
versiones ancladas y existen exactamente para esto.

Con los tres paquetes bajo una raíz, `sorted(...)[-1]` se quedaba con **uno**, y cuál dependía del
orden alfabético de unas carpetas que elige quien descomprime. Los tres modos de fallo, medidos:

| Qué se pierde | Cómo se manifiesta |
|---|---|
| El fichero de descripciones de otro paquete | `display` = **`888000000008`**, el número del código puesto de nombre. Sin excepción y sin aviso |
| La tabla de conceptos de otro paquete | «La edición … no tiene activos [...]. O el código está retirado o la release no es la que se cree» — un error que acusa al código correcto |
| El *refset* de dependencias de otro paquete | La versión declarada es la Internacional de abril mientras el contenido es de junio |

El primero es el caro. Un `CodeSystem` con `display` numérico se publica igual, se sirve igual y sale
en un informe: el sistema no tiene forma de distinguir un término que no encontró de un término que
es así.

## Decisión

**Se leen todos los ficheros que casan con cada patrón, no uno.** `_fichero` pasa a `_ficheros` y
devuelve la lista entera; conceptos, descripciones y *refsets* de idioma se recorren de los tres
paquetes.

**La edición que se declara es la del paquete que depende de los demás**, y se identifica por ser la
de fecha más reciente entre las que declaran dependencia del módulo de modelo. No es una heurística
cómoda: la dirección de la dependencia está publicada —la extensión se alinea con las ediciones
internacionales ya publicadas, nunca al revés—, así que la más nueva es siempre la dependiente. Y es
la que nombra al conjunto: la URI que resulta,
`http://snomed.info/sct/900000001000122104/version/20260601`, es la que la guía ya llamaba `$SCT_ES`.

**Si dos módulos distintos declaran la misma fecha, no se carga.** Ahí no hay forma de saber cuál
depende de cuál, y una edición mal declarada es peor que no cargar nada: los `display` dejan de ser
reproducibles sin que nadie se entere.

## Consecuencias

- El cargador tarda más: recorre tres tablas de descripciones en vez de una. Sigue siendo una pasada
  por fichero, que es lo que impide que la carga pase de segundos a horas.
- `HISPALIS_SNOMED` cambia de significado —de «la release» a «la raíz con las releases»— y con él
  `README.md`, `.env.example` y la ayuda del cargador, que son los tres sitios donde lo va a leer
  quien lo monte.
- El montaje sigue **sin probarse contra la release de verdad**, que no está en el equipo. Lo que se
  ha probado es el contrato con el formato de distribución, que es lo que estaba mal supuesto.
- Queda una diferencia que este ADR no resuelve: la extensión de Medicamentos es **una cuarta**
  descarga, mensual, y quien la necesite tendrá que decidir a qué versión la ancla.

## Alternativas consideradas

- **Una variable por paquete** (`HISPALIS_SNOMED_INTERNACIONAL`, `…_ESPANOLA`, `…_EXTENSION`).
  Describe mejor la realidad y obliga a configurar tres cosas para cargar una; además el propio RF2
  ya dice de qué paquete es cada fichero, así que la información estaría duplicada y podría
  contradecirse.
- **Exigir un fichero de manifiesto propio** que declare las tres rutas y la versión. Es la misma
  duplicación, con el añadido de inventar un formato donde ya hay uno estándar.
- **Declarar la versión a mano.** Es lo que el cargador lleva evitando desde el primer día: escrita a
  mano, un día dice una cosa y el contenido es otro.
- **Coger siempre la última versión de cada producto.** Es lo natural y es justo lo que las entradas
  *«Dependencia EE SNS»* existen para impedir: una extensión de junio con una internacional de agosto
  referencia conceptos que la internacional puede haber inactivado.

## Lo reutilizable

1. **«Una release» puede ser varias.** Antes de escribir el lector de un formato de distribución,
   mirar cómo se **distribuye** de verdad: cuántos paquetes, con qué cadencia y qué depende de qué. El
   formato del fichero se documenta en todas partes; la composición del producto, en las notas de
   versión, que no lee nadie.
2. **Un `[-1]` sobre un `glob` es una decisión disfrazada de detalle.** «Coger el último» solo tiene
   sentido si el orden significa algo, y el orden alfabético de unas carpetas que elige el usuario no
   significa nada. O se leen todos, o se elige por un criterio del contenido.
3. **Un valor por defecto que rellena un hueco esconde el hueco.** `display or fsn or codigo`
   convierte «no encontré el término» en un dato que se publica. Donde el hueco importa, el defecto es
   el error.
4. **En terminología licenciada, la versión es parte del dato.** Un `display` sin edición y fecha
   declaradas no es reproducible, y dos servidores contestan cosas distintas a la misma pregunta sin
   que ninguno esté roto.
5. **Un arnés sintético prueba el formato, no el producto.** La mini-*release* de los tests tenía un
   fichero por tabla porque así se escribió, y ese detalle —invisible, nunca cuestionado— es
   exactamente la premisa que fallaba.
