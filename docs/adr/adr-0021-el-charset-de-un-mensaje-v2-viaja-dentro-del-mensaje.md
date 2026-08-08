---
tipo: referencia
stack: [java, python]
aplica_a: [integracion, simuladores]
revisado: 2026-08-08
tags: [adr, hl7-v2, mllp, charset, codificacion, integracion, espana]
---

# ADR-0021: El charset de un mensaje HL7 v2 viaja dentro del mensaje, y quien no lo lee rompe la Ñ

- **Estado:** aceptado
- **Fecha:** 2026-08-08

## Contexto

HTTP lleva el juego de caracteres en una cabecera que está **fuera** del cuerpo, así que se puede leer
antes de decodificar nada. Un mensaje HL7 v2 no: lo declara en **`MSH-18`**, que es un campo del
propio mensaje. Para saber cómo decodificar los bytes hay que haber decodificado ya lo bastante como
para encontrar `MSH-18`.

Eso deja tres agujeros, y los tres se cruzan en el mismo sitio:

1. **`MSH-18` puede venir vacío, y es legal.** El estándar dice que entonces vale «el juego por
   defecto acordado entre las partes», que es una forma educada de decir que no hay respuesta en el
   estándar. En España ese acuerdo es casi siempre `ISO-8859-1`, porque los HIS que no declaran
   charset son los antiguos y los antiguos mandan latín-1. Elegir UTF-8 «por moderno» convierte cada
   `Ñ` en dos caracteres.
2. **Decodificar con el juego equivocado no lanza ninguna excepción.** `ISO-8859-1` no tiene
   secuencias inválidas: cualquier byte es un carácter. Leer como UTF-8 unos bytes latín-1 produce
   `U+FFFD` y sigue; leer como latín-1 unos bytes UTF-8 produce `MUÃ‘OZ` y sigue. **Un test que solo
   compruebe que no hubo error pasa en los dos casos.**
3. **La tabla 0211 usa literales que no son los nombres IANA.** `8859/1`, `8859/15`, `UNICODE UTF-8`.
   Escribir `ISO-8859-1` en `MSH-18` es tan incorrecto como no escribir nada, y la librería que
   decodifica busca el literal del estándar, no el de Java.

En este proyecto el fallo tenía además dos mitades separadas: **HAPI decodifica** los bytes
(`ExtendedMinLowerLayerProtocol` lee `MSH-18` antes de convertir) y **el canal valida**. Si las dos
mitades no usan exactamente los mismos literales, una lee con un juego y la otra comprueba contra
otro — que es la peor combinación posible, porque el mensaje se acepta y el nombre se corrompe.

El caso español no es un adorno: `MUÑOZ`, `ÁLVAREZ` y `PEÑA` están en la lista de casos de prueba
obligatorios del proyecto por esto. Un apellido corrupto en un mensaje de admisión no se detecta al
llegar; se detecta meses después, cuando alguien busca al paciente y no aparece, o cuando aparece dos
veces.

## Decisión

**El charset se resuelve una sola vez, en un tipo con nombre propio, y lo que no se sepa leer se
rechaza en vez de escribirse.**

- Una clase `CharsetDeclarado` interpreta `MSH-18` con una **lista corta y explícita** de los
  literales de la tabla 0211 que este laboratorio acepta: `ASCII`, `8859/1`, `8859/15`, `UNICODE`,
  `UNICODE UTF-8`. La librería sabe decodificar bastantes más —cirílico, japonés, hebreo—; aceptarlos
  sería fingir un soporte que nadie ha probado. Un laboratorio de Sevilla que recibe un mensaje
  declarado en `8859/8` tiene un problema de configuración en el emisor, no un paciente israelí.
- **Ausencia de `MSH-18` ⇒ `ISO-8859-1`**, escrito como constante con su justificación al lado, no
  como el valor por defecto de la plataforma.
- Un charset declarado y **no aceptado** es un rechazo del mensaje (`AR`), no una advertencia. Para
  cuando el canal lo mira, la librería ya ha decodificado con el juego por defecto y lo que hay entre
  manos es basura silenciosa: escribirla sería poner un nombre corrupto en la historia de alguien.
- **Un test de ida y vuelta** comprueba que los literales que usa el validador son los mismos que usa
  el decodificador. Si divergen, el test falla; sin él, divergen sin que nadie se entere.
- Los apellidos con `Ñ` y tildes se comprueban **por punto de código**, no por igualdad de cadenas
  impresas en un log. Una consola mal configurada enseña `MU?OZ` de un dato perfecto y `MUÑOZ` de uno
  roto, según el terminal.

## Consecuencias

- El canal v2 tiene un punto único donde se decide la codificación, y ese punto está probado.
- Un HIS que declare algo exótico recibe un `AR` con motivo en vez de que su paciente se llame otra
  cosa. Es peor operativamente y mejor clínicamente, que es el orden correcto.
- El coste real: hay que mantener a mano el mapa de literales y la lista de aceptados. Es deliberado
  — la alternativa es una lista que crece sola con juegos que nadie ha ejercitado.
- **Se generaliza más allá de v2.** Cualquier formato que declare su codificación dentro de sí mismo
  —XML sin cabecera HTTP, CSV con BOM, un fichero de un laboratorio externo— tiene el mismo problema:
  el momento de decidir la codificación es antes de que exista el objeto de dominio, y la decisión
  hay que poder verla escrita.

## Alternativas consideradas

- **Decodificar siempre como UTF-8.** Descartada: es lo que hace que funcione en las pruebas —los
  simuladores mandan UTF-8— y falle en producción, donde el HIS de verdad manda latín-1. Un fallo que
  solo aparece contra el sistema real es peor que uno que aparece siempre.
- **Aceptar la tabla 0211 entera y delegar en la librería.** Descartada: convierte «esto no lo
  sabemos leer» en «esto lo leímos como pudimos». La librería sí sabe decodificar, pero nadie ha
  comprobado que el resto del circuito —la base de datos, la proyección FHIR, el ORU de vuelta—
  conserve esos caracteres.
- **Normalizar todo a UTF-8 a la entrada y olvidarse.** Es lo que se hace internamente, y no basta:
  la conversión sigue necesitando saber de qué se convierte. Además, el `ORU^R01` de vuelta al HIS
  tiene que salir en el juego que el HIS entiende, no en el nuestro.
- **Tratarlo como una advertencia y escribir igualmente.** Descartada de plano. En un sistema
  administrativo, un nombre corrupto es una molestia; en uno clínico, es un paciente que no se
  encuentra o que se duplica.
