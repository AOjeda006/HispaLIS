---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-09
tags: [adr, terminologia, fhir, hapi, ci, toolchain, reproducibilidad]
---

# ADR-0026: Un parámetro que solo falla con el servidor recién cargado

- **Estado:** aceptado
- **Fecha:** 2026-08-09

## Contexto

La comprobación «¿está cargado el catálogo?» de `ci-simuladores` se escribió así:

```bash
curl --get "$SERVIDOR/ValueSet/\$expand" \
  --data-urlencode 'url=…/ValueSet/pruebas-del-catalogo' \
  --data-urlencode 'count=0'
```

`count=0` es lo que **la especificación de FHIR** define para «devuélveme solo el total, sin
enumerar códigos». Es la llamada barata y correcta para preguntar si un conjunto tiene algo dentro.

Se probó en local contra el `compose` y devolvió `21`. En la CI devolvió:

```
HTTP 500 — HAPI-0831: Expansion of ValueSet produced too many codes (maximum 0) - Operation
aborted! - ValueSet "…/pruebas-del-catalogo" has not yet been pre-expanded. Performing in-memory
expansion without parameters. Current status: NOT_EXPANDED
```

Dos cosas, y la segunda es la que importa:

1. **HAPI no interpreta `count=0` como la norma.** Lo lee como «el máximo de códigos que admito es
   cero», compara la expansión de 21 conceptos contra ese máximo y aborta. Veintiún códigos son
   «demasiados» porque el techo es cero.
2. **Solo pasa mientras el `ValueSet` está `NOT_EXPANDED`.** El límite lo aplica el camino de
   expansión **en memoria**, que es el que HAPI usa antes de que la pre-expansión programada haya
   corrido. Una vez pre-expandido, la misma llamada toma otro camino, no aplica el límite y
   devuelve el total sin quejarse.

El local y la CI no discrepaban en la versión ni en la configuración: discrepaban en que **el
volumen de terminología del local venía de una ejecución anterior** y ya tenía la pre-expansión
hecha. La CI arranca siempre con el volumen vacío, así que allí el `ValueSet` está siempre
`NOT_EXPANDED` y el fallo es del cien por cien.

## Decisión

**Pasar siempre un `count` real** (`1000` en la comprobación y en el generador, `500` en la web) y
contar los `contains`, en vez de pedir `count=0` y leer `expansion.total`.

Y dos reglas de las que el arreglo es solo el caso concreto:

1. **Un estado en disco que sobrevive entre ejecuciones convierte una prueba local en una prueba de
   otra cosa.** Cuando algo pasa en local y falla en la CI, la primera pregunta no es qué versión
   hay en cada sitio, sino **qué había ya escrito** en el volumen del local. Aquí, la diferencia
   entera estaba ahí.
2. **La comprobación previa se escribe con la misma llamada que hace el cliente**, no con una
   variante más barata. El paso existe para que un fallo aparezca **antes** de generar el corpus; si
   la llamada de la comprobación no es la del generador, lo único que garantiza es que la variante
   barata funciona.

## Consecuencias

- La comprobación pide 21 códigos en vez de un entero. Es un coste irrelevante y compra que el paso
  ejercite exactamente el camino que va a recorrer el generador.
- **`count=0` queda proscrito contra HAPI en todo el proyecto**, y así está escrito en
  `terminologia/CLAUDE.md`. Los ejemplos de `README.md` y de ese mismo fichero lo usaban: los dos
  habrían fallado contra un servidor recién levantado, que es justo cuando alguien copia un comando
  de la documentación.
- **El síntoma es el peor posible para diagnosticar**: «produced too many codes» apuntando a un
  conjunto de veintiún elementos manda a buscar un límite de tamaño que no tiene nada que ver, y el
  `maximum 0` del mensaje pasa desapercibido porque parece parte de la plantilla del error.
- Queda como aportación pendiente a la biblioteca, en
  `interoperabilidad/terminologia/`: junto con la vuelta del `$translate` (`HAPI-1154`) y el
  `match.equivalence` de R4, son ya tres divergencias medidas entre HAPI y el capítulo de
  terminología de R5.

## Alternativas consideradas

- **Esperar a que la pre-expansión termine** antes de comprobar. Descartada: haría el paso lento y
  frágil por una razón equivocada — el problema no es que haya que esperar, es que el parámetro está
  mal elegido. El generador funciona perfectamente con el `ValueSet` sin pre-expandir.
- **Bajar `pre_expand_value_sets` a falso.** Descartada: quita una función útil del servidor para
  esconder el síntoma, y el camino en memoria seguiría siendo el que se recorre.
- **Comprobar con `$validate-code` en vez de con `$expand`.** No aplica el límite y funcionaría, pero
  no es la llamada que hace el generador — que es precisamente lo que la regla 2 prohíbe.
