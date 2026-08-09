// El catálogo de valores críticos: las pruebas cuyo resultado obliga a descolgar el teléfono.
//
// ⚠️ Los umbrales NO se inventan. Los de aquí salen de una fuente publicada y citable, y cada
// concepto lleva su procedencia dentro —`procedencia-del-valor-critico`, en `CodeSystem/catalogo-
// pruebas`—, no en un comentario de este fichero ni en el código que los consume. Un comentario no
// viaja con el recurso; la propiedad sí.

ValueSet: ValoresCriticos
Id: valores-criticos
Title: "Catálogo de valores críticos — las pruebas que obligan a avisar"
Description: """
Pruebas del catálogo para las que el laboratorio declara un **límite crítico**: la cifra a partir de
la cual el resultado no se limita a estar fuera de rango, sino que **obliga a avisar al peticionario
de inmediato**. Los dos límites y su procedencia son propiedades de cada concepto en
`CodeSystem/catalogo-pruebas` (`limite-critico-bajo`, `limite-critico-alto` y
`procedencia-del-valor-critico`); este conjunto dice **cuáles** los tienen.

### No son los rangos de referencia, y la diferencia es la que importa

Un rango de referencia dice qué es normal; un límite crítico dice qué es urgente. Un potasio de
6,2 mmol/L está fuera de rango y **no** es crítico; uno de 7,5 sí. Los rangos de referencia de este
laboratorio **no están en la guía** a propósito: dependen del método y del analizador, y dos
laboratorios que usan el mismo código `CREA` publican rangos distintos sin contradecirse. El límite
crítico es lo contrario — es el umbral pactado con quien recibe la llamada, y tiene que estar
publicado para que el clínico sepa qué dispara un aviso.

### La fuente

Llopis Díaz MA, Gómez Rioja R, Álvarez Funes V, Martínez Brú C, Cortés Rius M, Barba Meseguer N,
Ventura Alemany M, Alsina Kirchner MJ. *Comunicación de valores críticos: resultados de una encuesta
realizada por la Comisión de la Calidad Extraanalítica de la SEQC*. Revista del Laboratorio Clínico.
2010;3(4):177-182. ISSN 1888-4008.

La encuesta se envió a los laboratorios del Programa de Garantía Externa de la Calidad de la SEQC y
recogió los límites que cada uno tenía establecidos. Su **tabla 6** publica, por magnitud, la
mediana y los percentiles 10-90 de los límites declarados, separando **consulta externa** de
**hospitalización**.

Se toma **la mediana de la columna de consulta externa**, y no la de hospitalización, porque este
laboratorio es privado y ambulatorio: sus pacientes están en su casa. La diferencia no es cosmética
—en potasio son 6,3 frente a 6,5 mmol/L— y avisar antes es la dirección correcta del error cuando el
paciente no está ingresado.

### Por qué solo cinco pruebas

Solo las que están **a la vez** en la tabla 6 y en el catálogo de este laboratorio, y con la misma
unidad. Las demás magnitudes de la tabla (cloruro, calcio, fosfato, urato, amilasa, bilirrubina, ALT,
AST) no se ofertan aquí, y las pruebas del catálogo que la tabla no cubre —hemoglobina, plaquetas,
TSH— **se quedan sin límite crítico declarado**. Rellenar ese hueco de memoria sería exactamente lo
que este fichero existe para impedir.

### Por qué no hay umbral por sexo

Porque la fuente no lo estratifica. Los intervalos de referencia sí dependen del sexo y este proyecto
los modela así; los límites críticos publicados, no. Partirlos por sexo aquí daría una precisión que
la fuente no respalda, y en el único sitio del sistema donde una cifra de más o de menos se traduce
en una llamada que se hace o no se hace.

### Cómo se compara

Los límites son **inclusivos**: un resultado es crítico cuando alcanza el límite alto o baja hasta el
bajo (`valor >= alto` o `valor <= bajo`). En la duda se avisa. Y se comparan siempre contra la
`unidad-ucum` del concepto: un resultado que llegue en otra unidad **no se declara «no crítico»**, se
rechaza la comparación — contestar «no es crítico» a una pregunta que no se ha podido responder es
la forma de fallar que este catálogo existe para evitar.

### Advertencia

**Este catálogo es verosímil, no el de ningún laboratorio real.** La propia encuesta que se cita
concluye que no hay una lista aceptada universalmente y que cada laboratorio debe acordar la suya con
sus clínicos. Lo que se demuestra aquí es el mecanismo: que los umbrales estén publicados, sean
citables y los consuma el sistema desde un único sitio.
"""

* ^purpose = "No es el `binding` de ningún elemento. Lo consume la regla de la doble validación, que pregunta al servidor de terminología por los límites de la prueba que se está firmando. Publicarlo como `ValueSet` es lo que impide que esos umbrales acaben en un `Map<String,String>` dentro del código."

* CatalogoPruebas#GLU
* CatalogoPruebas#CREA
* CatalogoPruebas#UREA
* CatalogoPruebas#NA
* CatalogoPruebas#K
