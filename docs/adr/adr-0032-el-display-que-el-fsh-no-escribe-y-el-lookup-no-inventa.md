---
tipo: referencia
stack: [fsh, fhir]
aplica_a: []
revisado: 2026-08-12
tags: [adr, fhir, fsh, sushi, terminologia, codesystem, lookup, diagnostico]
---

# ADR-0032: El `display` que el FSH no escribe y el `$lookup` no inventa

- **Estado:** aceptado
- **Fecha:** 2026-08-12

## Contexto

En este proyecto, un concepto del catálogo local apunta a la enfermedad que declara con una propiedad
de tipo `Coding`:

```fsh
* #LEGIOAG ^property[0].code = #enfermedad-edo
* #LEGIOAG ^property[0].valueCoding = EnfermedadesEdo#LEGIONELOSIS
```

Se lee bien y **dice menos de lo que parece**. `Sistema#CODIGO` compila a un `Coding` con `system` y
`code`, y **sin `display`**. Un `Coding` con display se escribe `EnfermedadesEdo#LEGIONELOSIS
"Legionelosis"`, con el nombre entre comillas — y esa comilla que falta no la echa de menos nadie:
SUSHI compila sin un aviso, el validador oficial de HL7 da cero errores, y el recurso publicado es
correcto. Simplemente no lleva nombre.

El backend, a su vez, leía el nombre de la enfermedad de ahí:

```java
new ReglaDeDeclaracion(codigoDePrueba, enfermedad.getCode(), enfermedad.getDisplay(), …)
```

**El fallo, tal y como se vio.** Contra un servidor de terminología de verdad, un `Legionella`
positivo validado dejaba esto cada cinco segundos, para siempre:

```
WARN  NotificadorEdo : La vuelta del notificador EDO ha fallado entera; se reintenta. Causa:
      DataIntegrityViolationException: INSERT INTO dominio.notificacion_edo (…);
      ERROR: el valor nulo en la columna «nombre_enfermedad» viola la restricción de no nulo
```

Ninguna declaración se abría, ninguna cohorte de vigilancia existía, y el mensaje hablaba del nombre
de **una columna**: nada apuntaba al catálogo, que es donde estaba la causa.

**Y ningún test lo vio**, que es la parte que importa. Los tests de integración usan un doble de
terminología que construye la regla en Java, con el nombre puesto a mano; el test unitario del cliente
de terminología montaba la respuesta del `$lookup` a mano, y quien la montó escribió el `display`
porque al escribirlo a mano se escribe entero. **El único sitio donde el dato falta es el que nadie
teclea: la salida real de un servidor real sobre el `CodeSystem` real.**

## Decisión

**Lo que es de un concepto se lee de ese concepto, no de quien lo señala.**

El nombre de la enfermedad se toma del `display` que devuelve el `$lookup` **de la enfermedad**, no
del `display` del `Coding` con el que el catálogo de pruebas la apunta. En este caso sale gratis:
`plazoDe` ya hacía ese segundo `$lookup` para leer la modalidad y el plazo, y la respuesta ya traía el
nombre delante.

Es exactamente la misma regla que en el ítem 48 movió `plazo-horas` de la prueba a la enfermedad, y se
enuncia igual: **una referencia entre conceptos transporta identidad —sistema y código—, no
contenido.** Cualquier otra cosa que se lea del `Coding` que apunta es una copia, y las copias se
quedan viejas o no llegan a existir.

Dos medidas de apoyo:

1. **El invariante sube al dominio.** `ReglaDeDeclaracion` ya rechazaba una regla sin prueba, sin
   enfermedad, sin criterio y sin plazo; ahora rechaza también la que no dice cómo se llama la
   enfermedad. La diferencia no es que el fallo desaparezca —desaparece por la medida anterior—, es
   **quién lo cuenta**: un `DatoInvalido` que nombra el concepto en vez de un `NOT NULL` que nombra
   una columna dentro de un bucle de reintentos.
2. **Si ni la propia enfermedad publica nombre, se usa su código.** Un nombre es una comodidad para
   quien lee; perder una declaración obligatoria por no tener etiqueta sería desproporcionado. Al
   revés que con el plazo, que sí es un parámetro legal y por eso aborta.

## Consecuencias

- Las declaraciones se abren contra un servidor de terminología real, que es donde no se abrían.
- El nombre publicado es el de `CodeSystem/enfermedades-edo`, que es el sitio donde alguien lo
  mantiene. Antes había **dos** sitios posibles y solo uno estaba relleno.
- Queda un test unitario cuyo `Coding` va **deliberadamente pelado** —sin `display`—, y un comentario
  al lado que explica por qué. Es el detalle que se «arregla» al refactorizar si no está escrito.
- No se toca el FSH. Añadir el `display` al `valueCoding` habría hecho pasar el caso concreto y habría
  dejado la trampa puesta para el siguiente: el dato seguiría duplicado, y el día que la guía
  renombrase una enfermedad quedarían dos nombres distintos para la misma.

## Alternativas descartadas

- **Escribir `EnfermedadesEdo#LEGIONELOSIS "Legionelosis"` en el FSH.** Un renombrado no coordinado
  deja el catálogo diciendo una cosa y la declaración otra. En FHIR el `display` de un `Coding` es
  informativo por definición y **no es la fuente**; tratarlo como fuente es el error, no que estuviera
  vacío.
- **Dar por bueno un nombre nulo y aflojar el `NOT NULL` de la V15.** Convertiría un fallo ruidoso en
  una tabla de declaraciones sin nombres. La columna está bien: lo que estaba mal es lo que se le
  metía.
- **Comprobarlo con una puerta de CI sobre el FSH** («todo `valueCoding` lleva `display`»). Ataca el
  síntoma en el sitio equivocado y obligaría a mantener una lista de excepciones para las propiedades
  donde el nombre no pinta nada.

## Lo reutilizable

1. **`Sistema#CODIGO` en FSH no lleva `display`, y nada te avisa.** Ni SUSHI, ni el validador
   oficial: un `Coding` sin nombre es perfectamente válido. Si tu código lee ese `display`, lee
   `null`. La forma con nombre lleva el literal entre comillas detrás.
2. **Un `$lookup` no rellena el `display` de las propiedades.** Devuelve el `display` **del concepto
   consultado** como parámetro suelto, y las propiedades tal y como estén escritas en el
   `CodeSystem`. Es fácil suponer lo contrario porque el servidor «tiene» los dos `CodeSystem`
   cargados y podría resolverlo — pero no es su trabajo.
3. **La regla general: de una referencia entre conceptos se lee la identidad, y el contenido se pide
   al dueño.** Vale igual para `Coding`, para `Reference` y para un identificador en una tabla ajena.
4. **Y la comprobación que lo caza:** un ensayo contra la pila de verdad. Los dobles de test se
   escriben a mano, y **lo que se escribe a mano se escribe completo**; por eso un doble nunca
   reproduce un campo que falta en el origen. Es la tercera vez en este proyecto que el ensayo en vivo
   ve lo que la suite no (`adr-0020`, `adr-0029`), y las tres veces la diferencia ha sido la misma:
   los tests comprueban el código contra lo que alguien creía que llegaba.
