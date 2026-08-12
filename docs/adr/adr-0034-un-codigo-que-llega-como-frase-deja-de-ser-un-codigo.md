---
tipo: referencia
stack: [java, hl7-v2, fhir]
aplica_a: []
revisado: 2026-08-12
tags: [adr, hl7-v2, terminologia, integracion, mapeo]
---

# ADR-0034: Un código que llega como frase deja de ser un código

- **Estado:** aceptado
- **Fecha:** 2026-08-12

## Contexto

El motor traduce `OBX` a `Observation`. `OBX-2` dice de qué tipo es el valor, y el canal ya hacía lo
difícil: distinguir `NM` de todo lo demás para no intentar convertir «Negativo» en un número. Los
cuatro tipos no numéricos iban por la misma rama:

```java
if ("ST".equals(tipo) || "TX".equals(tipo) || "CE".equals(tipo) || "CWE".equals(tipo)) {
    return new ResultadoMedido(codigoLocal, Optional.empty(), Optional.of(valor), null, cuando);
}
…
medido.texto().ifPresent(texto -> recurso.setValue(new StringType(texto)));
```

Funciona, y el test lo respaldaba: un `LEGIOAG|ST|Negativo|` se guarda como `valueString: "Negativo"`,
que es exactamente lo correcto — eso no es un código, es una descripción.

Lo que no se veía es lo que pasa cuando el analizador **sí** manda un concepto. `CE` y `CWE` no son
texto: son código, cómo se lee y de qué vocabulario sale. Al meterlos por la rama del texto, el
laboratorio recibía `valueString: "POS"` — y el dominio, que distingue `informarTextual` de
`informarCualitativo`, lo guardaba como descripción y **sin código**.

La consecuencia estaba a tres saltos y era grave: la regla de declaración obligatoria compara el
código del resultado con el que declara el catálogo (`resultado-que-declara = POS`). Sin código, no
hay comparación posible. **Una Legionella positiva que entrase por el analizador no se declaraba a
Salud Pública jamás** — y todo lo demás funcionaba: el resultado se guardaba, se validaba, salía en
el informe y se veía «POS» en pantalla.

**Por qué ningún test lo veía.** El único cualitativo que probaba el canal era texto libre, y su
comportamiento era —y sigue siendo— el correcto. Los tests del ítem 48 entraban por la API FHIR con
un `valueCodeableConcept` bien puesto. Nadie cruzó las dos mitades: *cualitativo* × *por v2*.
Apareció recorriendo el circuito del ítem 51 contra el `compose`, mirando por qué el `Task` de la
declaración no aparecía.

## Decisión

**Un valor codificado se conserva codificado, y el vocabulario se traduce o no se pone.**

```java
if (valor.getData() instanceof Composite concepto) {   // CE, CWE, CNE: comparten los 3 primeros
    codigo  = componente(concepto, 0);
    sistema = sistemaDelValor(componente(concepto, 2));  // 99HISPCUAL → la URI canónica
}
```

Tres reglas, y la tercera es la que evita inventar:

1. `NM` → cantidad. Igual que antes.
2. `CE`/`CWE` **de un vocabulario que el motor sabe situar** → `valueCodeableConcept` con su `system`.
3. Todo lo demás —`ST`, `TX`, y un `CWE` del diccionario interno del aparato— → `valueString`. Un
   código sin `system` no es un código; ponerle el nuestro afirmaría una equivalencia que nadie ha
   declarado, y descartarlo perdería un resultado legítimo.

El simulador del analizador emite las dos formas, y la distingue por el `^` del valor —el separador
de componente de v2—: `LEGIOAG:POS^Positivo` es un concepto y `LEGIOAG:Negativo` una frase. **No hay
ninguna lista de códigos dentro del simulador**, que es lo que exige el invariante 4.

## Consecuencias

- El circuito EDO funciona por sus dos entradas, no solo por la API. Comprobado contra el `compose`:
  `Task … businessStatus=ACUSADA` y el SVEA con su declaración registrada.
- Los componentes se leen **por posición** (`Composite.getComponent(n)`) y no por la clase concreta
  que HAPI instancie según `OBX-2`. Preguntar por `instanceof CE` obligaría a enumerar `CWE` y `CNE`
  y a fallar en silencio con la siguiente que apareciera.
- Hay un nombre local nuevo, `99HISPCUAL`, y por tanto una convención que documentar: el vocabulario
  de las **pruebas** y el de los **valores** son distintos y viajan en campos distintos del mismo
  segmento.

## Alternativas descartadas

- **Que el backend interprete un `valueString` que coincida con un código del catálogo.** Es el
  atajo, y convierte una coincidencia de cadenas en una afirmación clínica. «POS» escrito por un
  analizador que no comparte vocabulario no significa necesariamente lo mismo.
- **Rechazar el `CWE` de vocabulario desconocido.** Coherente con el resto del canal —lo que no se
  traduce va a la bandeja de errores— pero desproporcionado aquí: un serotipo en el diccionario del
  aparato es un resultado real y perderlo es peor que guardarlo como texto. La diferencia con el
  código de **prueba** es que aquel decide qué análisis se ha hecho, y este solo cómo se lee.

## Lo reutilizable

1. **Un tipo de dato que se colapsa en la traducción no vuelve.** Código → texto es una conversión
   con pérdida, y la pérdida no se nota en el borde: se nota tres capas más adentro, donde alguien
   compara códigos. Cuando un mapeo tenga una rama «y todo lo demás como cadena», hay que mirar qué
   entra por ahí.
2. **`CE`/`CWE` no es `ST` con adornos.** En HL7 v2 es la diferencia entre un concepto y una frase, y
   es la misma que en FHIR hay entre `valueCodeableConcept` y `valueString`. Los dos estándares la
   modelan; perderla es cosa del mapeo.
3. **El test que falta es el del cruce.** Había test del cualitativo y test de la declaración. El
   fallo vivía en cualitativo **por el canal v2**, que era la casilla vacía. Es el mismo patrón que
   `adr-0033`: enumerar las dimensiones y mirar qué combinación no prueba nadie.
4. **Los componentes de un compuesto se leen por posición.** Las familias de tipos codificados de v2
   comparten prefijo por diseño; atarse a la clase concreta que instancie la librería es atarse a un
   detalle del parser.
