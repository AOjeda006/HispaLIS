---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-15
tags: [adr, testing, cobertura, codigo-muerto, duplicacion, calidad]
---

# ADR-0041: Un cero redondo de cobertura suele ser una regla duplicada

- **Estado:** aceptado
- **Fecha:** 2026-08-15

## Contexto

La primera medición de cobertura de los seis componentes dio números perfectamente respetables —entre
el 74,9 % y el 92 %— y **ninguno de ellos sirvió para nada**. Lo que sirvió fueron los métodos que
salían a **cero exacto**, y no porque fueran pocos, sino porque un cero redondo no es un hueco de
cobertura: es una afirmación sobre el sistema. Alguien escribió ese código creyendo que hacía falta y
**nada lo ejecuta**.

Tres ceros, tres diagnósticos distintos, y los tres invisibles en el porcentaje:

| Cero | Qué era en realidad | Qué costaba |
|---|---|---|
| `Campos.fechaIso` | **Regla duplicada.** El transformador del canal `ADT` llevaba su propia copia, sin comprobar que los ocho caracteres de la fecha fueran dígitos | Con `ABCDEFGH` o `00000000` se componía una fecha imposible, el servidor la rechazaba y la excepción —que no cazaba ningún `catch` del canal— archivaba el mensaje como **fallo interno del laboratorio** cuando era un dato del emisor y ni siquiera grave |
| `CabeceraMsh.claveDeDeduplicacion()` | **Regla redundante.** La deduplicación la impone el `UNIQUE` de la tabla; ese método era una segunda forma de decir lo mismo | Nada, todavía. Lo que costaba es que se leyera y se creyera: la siguiente persona que buscara dónde se deduplica habría encontrado ahí una respuesta que no se ejecuta |
| `Resultado.informarTextual` | **Camino real sin test.** Se llega desde la API por dos vías, y la segunda es la caída que dejó `adr-0034` | Un resultado cualitativo sin código no es un positivo declarable, y no había una sola línea que lo afirmara |

El primero es el que da nombre a este ADR. **La copia buena era la que no se llamaba.** La función
compartida la escribió alguien a propósito, pensando en el formato; la copia de dentro del
transformador la escribió quien tenía prisa por sacar el campo. Eso no es casualidad: es la forma
normal de esta trampa. Cuando una regla existe dos veces, la versión cuidada es la que se extrajo y
la descuidada es la que se quedó en línea — y la que se quedó en línea es la que corre.

Ninguno de los tres se veía en la métrica agregada. Con el fallo dentro, `integracion/` medía 88,5 %;
sin él, mide 88,5 %.

## Decisión

**La cobertura se mide una vez por componente, sin umbral y sin `check`, y se lee por los ceros.**

Un umbral convierte «revelar huecos» en «subir el número», y la forma barata de subir el número es
escribir tests que recorran código sin afirmar nada. El porcentaje se anota como dato de contexto y
no entra en ninguna puerta de la CI.

**Cada cero redondo se cierra con uno de cuatro veredictos, escrito:**

1. **Regla duplicada** → se borra la copia en uso y se llama a la compartida, **nunca al revés**, y el
   arreglo empieza en rojo con la entrada que rompía la copia.
2. **Regla redundante**, impuesta de verdad en otro sitio → se borra el código y **el porqué se muda a
   donde la regla se aplica**.
3. **Camino real sin test** → se escribe el test.
4. **Inalcanzable a propósito** —adaptadores de plataforma, arranques, ramas que solo corren en el
   `compose`— → entra en una tabla de huecos aceptados **con su motivo**, para no volver a mirarlo
   desde cero la próxima vez.

Un cero no se cierra con «se ve que está bien».

## Consecuencias

- **Borrar código correcto es la parte que cuesta.** `claveDeDeduplicacion()` estaba bien escrito y
  bien documentado. El criterio no es la calidad: es que no lo ejecuta nada. Su javadoc, que era lo
  valioso, vive ahora donde la regla se cumple.
- La tabla de huecos aceptados es trabajo que hay que mantener, y es la que evita que la próxima
  medición vuelva a discutir los mismos seis ceros de siempre.
- Un porcentaje sin umbral **no protege de nada**, y no pretende hacerlo. Lo que protege son los tests
  que salieron de leerlo.
- Queda una asimetría honesta: este método encuentra reglas duplicadas **cuando una de las dos copias
  está extraída**. Dos copias en línea, las dos ejecutadas, no dan cero y no aparecen aquí.

## Alternativas consideradas

- **Un umbral de cobertura en la CI.** Es lo que hace casi todo el mundo y es lo que convierte la
  herramienta en un número que subir. Además fija el nivel en el peor momento posible: el día que se
  mide por primera vez.
- **Un detector estático de código muerto.** Habría encontrado los dos primeros ceros y **no el
  tercero**, que es código de producción que se ejecuta a diario y que ningún test tocaba. «Nadie lo
  llama» y «ningún test lo recorre» son conjuntos distintos, y el segundo contiene al primero. Y
  ninguna de las dos herramientas dice **cuál de las dos copias es la buena**: eso lo dice quien lee.
- **Borrar todos los ceros.** Habría borrado un camino de negocio real por no tener test, que es
  exactamente el diagnóstico contrario.
- **Medir en cada `build`.** Instrumentar cambia la transformación del código (`adr-0038`) y encarece
  cada vuelta. Se mide a propósito, cuando se va a leer.

## Lo reutilizable

1. **La cobertura se lee por los ceros redondos, no por el porcentaje.** Entre un 89 % y un 91 % no hay
   información; en un 0 % sobre un método con nombre de regla de negocio, toda.
2. **Código que nadie llama rara vez sobra: casi siempre hay otra copia de la misma regla, y la que
   corre es la peor.** La copia extraída se escribió a propósito; la que se quedó en línea, con prisa.
   Al arreglarlo se borra la de dentro y se llama a la compartida.
3. **Un cero redondo tiene cuatro diagnósticos y hay que escribir cuál es** —duplicada, redundante,
   camino sin test, inalcanzable a propósito—. El cuarto se anota con su motivo o se vuelve a discutir
   en cada medición.
4. **Al borrar código muerto bien documentado, el comentario vale más que el código.** Se muda a donde
   la regla se aplica de verdad; borrar los dos es perder la única explicación escrita del invariante.
5. **Un umbral de cobertura sustituye la pregunta por la métrica.** Medir sin umbral obliga a leer, que
   es más caro y es lo único que encuentra algo.
