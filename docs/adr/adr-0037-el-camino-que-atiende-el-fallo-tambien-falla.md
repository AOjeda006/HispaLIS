---
tipo: referencia
stack: [java, hl7-v2, hapi]
aplica_a: []
revisado: 2026-08-15
tags: [adr, hl7-v2, mllp, hapi, robustez, fuzzing, manejo-errores, seguridad]
---

# ADR-0037: El camino que atiende el fallo también falla

- **Estado:** aceptado
- **Fecha:** 2026-08-15

## Contexto

El motor de integración cumplía una regla escrita desde el hito 2: **siempre se responde**. Un emisor
HL7 v2 que no recibe acuse o reintenta para siempre o da el mensaje por entregado, y las dos cosas
son peores que un rechazo. Estaba probada con mensajes malos —un `MSH-9` desconocido, un `PID` sin
NHC, un laboratorio que contesta `500`— y en todos ellos el acuse salía.

La primera tanda de fuzzing por el socket real —105 entradas hostiles, semilla fija— dejó **45 sin
respuesta ninguna**: ni acuse, ni cierre, nada. El emisor se quedaba esperando hasta su propio plazo.

Las 45 tenían algo en común: el `MSH` no se dejaba leer. Y ahí el camino de HAPI (verificado con
`javap` sobre `hapi-base` 2.6.0) es este:

1. `ApplicationRouterImpl.processMessage` no consigue parsear.
2. Intenta componer el acuse de error con `getCriticalResponseData` + `logAndMakeErrorMessage`. Para
   eso necesita leer el `MSH` del entrante — que es justo lo que no se puede.
3. Registra «Exception occurred while logging parse failure» **en `debug`** y se queda con
   `outgoing = null`.
4. Llama al manejador de excepciones registrado con `(incoming, metadata, null, e)`.
5. Si el manejador devuelve `null`, **lanza** `"Application exception handler may not return null"`.

El manejador propio devolvía el `saliente` que le daban, que era nulo. Resultado: la excepción se la
come el servidor y **el emisor no recibe nada**. El agujero estaba en el peor sitio posible: un `MSH`
truncado o unos delimitadores a medias es exactamente lo que llega cuando se cae una conexión.

Y arreglando eso apareció la otra mitad. Con el acuse ya compuesto, otra entrada —un byte `0x00`
dentro del nombre del paciente— sacaba por el cable esto:

```
ERR|…|…|207^Application internal error^HL70357|E|||PreparedStatementCallback;
   bad SQL grammar [INSERT INTO integracion.mensaje (id, aplicacion_emisora, …
```

PostgreSQL no admite `0x00` en una columna `text`, el `INSERT` del archivo reventaba y HAPI compone
el acuse metiendo dentro `e.getMessage()`. El HIS del hospital recibía el esquema del laboratorio por
el puerto MLLP.

Las dos son el mismo defecto visto por sus dos caras: **el camino que atiende un fallo tiene su
propio camino de fallo, y nadie lo había recorrido.**

## Decisión

**El acuse de último recurso se compone a mano, y lo que se responde no es lo que se diagnostica.**

1. **El manejador de excepciones nunca devuelve nulo.** Si HAPI trae un acuse, se respeta el suyo; si
   no, se compone un `ACK` mínimo de v2.5.1 con `MSA-1 = AR`, el emisor y la recepción de las
   propiedades del canal y un `ERR` con código 100. Si hasta eso fallara, se responde una constante
   literal. En un protocolo con acuse, **no hay ninguna entrada para la que «no contestar» sea
   correcto**.

2. **`MSA-2` se deja vacío a propósito**, y va documentado en el código porque parece un descuido.
   Adivinar el `MSH-10` de un mensaje que no se deja parsear es contar campos por posición sobre
   bytes rotos, y el campo que caiga en su sitio puede ser la fecha de nacimiento del paciente. Un
   acuse sin `MSA-2` es incómodo; un acuse con filiación dentro es una fuga.

3. **El desenlace lleva dos textos y no uno.** `detalle` es lo que viaja por el cable —frases fijas,
   escritas para quien las va a leer, que es un sistema ajeno— y `detalleTecnico` es lo que se archiva
   y ve la consola interna. Antes eran el mismo campo, y por eso todo lo que servía para diagnosticar
   viajaba también al emisor.

4. **El archivado entra en la red de seguridad.** Era la única pieza fuera del `try` del despachador,
   por una razón buena —tiene que ocurrir antes de tocar nada— y con una consecuencia mala: su fallo
   no lo cazaba nadie.

5. **Lo que no se puede arreglar, se prueba y se dice.** Un cuerpo de menos de cuatro bytes muere en
   el lector MLLP de HAPI, que busca `MSH-18` en los bytes crudos antes de convertirlos a texto y se
   sale del array. Ahí no hay mensaje entrante, así que no hay manejador al que llamar. Se acepta
   porque el motor **cierra la conexión**: el emisor se entera en el acto y reconecta, que no es el
   silencio que la regla prohíbe. Hay un test con ese nombre.

## Consecuencias

- Cualquier entrada por el socket termina en una de dos: acuse o conexión cerrada. Ninguna deja la
  conexión abierta y muda.
- El acuse de último recurso es **pobre a propósito** —sin `MSA-2` y sin detalle— y eso hará que
  algún emisor no sepa a qué mensaje corresponde. Es el intercambio correcto frente a inventarlo.
- Quien diagnostique un fallo tiene que ir al archivo o a la consola: el acuse ya no se lo cuenta.
  Está dicho en la frase que sí sale («queda archivado con su motivo y es reprocesable»).
- Dos entradas de la tanda quedan guardadas como test de regresión con nombre propio, y la lista de
  rastros prohibidos del fuzzing creció con cuatro literales de SQL que la anterior no cazaba.

## Alternativas consideradas

- **Dejar que HAPI lance y no registrar manejador.** Es el comportamiento por defecto y es el que
  produjo las 45 entradas sin respuesta. La excepción se queda dentro del servidor.
- **Adivinar el `MSH-10` por posición para rellenar `MSA-2`.** Descartada por lo de arriba: sobre
  bytes rotos, el campo que caiga ahí puede ser filiación, y la habríamos devuelto al emisor.
- **Sanear el texto del mensaje antes de archivarlo** (quitar los `0x00`). Arregla ese caso concreto y
  deja el general: el archivo puede fallar por disco lleno, por conexión perdida o por cualquier otra
  cosa, y el acuse seguiría contando el porqué. Además viola la regla de guardar el original íntegro.
- **Filtrar el mensaje de la excepción con una lista de palabras prohibidas.** Es la versión frágil de
  la separación: funciona hasta la primera excepción cuyo texto no está en la lista. La lista de
  rastros existe, pero **en el test**, que es donde una lista incompleta solo cuesta un falso verde.

## Lo reutilizable

1. **En un protocolo con acuse, el manejador de errores es código de producción de primera línea, no
   una red de emergencia.** Su contrato incluye qué responder cuando no se puede componer una
   respuesta buena. Escríbelo y pruébalo con entrada que ni siquiera parsee.
2. **Comprueba qué hace tu librería cuando el manejador devuelve nulo.** HAPI lanza; otras tragan y
   cierran. La única forma de saberlo fue leer el bytecode.
3. **Un mensaje de error tiene dos destinatarios y no comparten texto.** El de fuera necesita saber
   qué hacer; el de dentro, qué pasó. Fundirlos es cómo el `getMessage()` de una excepción acaba en la
   respuesta — con la sentencia SQL, la ruta del fichero o el nombre del servidor dentro.
4. **Al inventariar qué está dentro de la red de seguridad, mira lo que ocurre *antes* de entrar.**
   Registrar, autenticar y auditar suelen ir delante del `try` por buenas razones, y por eso sus
   fallos salen por caminos que nadie ha diseñado.
5. **El criterio de un *fuzzer* no es «no se cae».** Es: se contesta o se cierra, no se filtra el
   interior, no se filtra el dato del usuario y **el servicio sigue atendiendo al siguiente**. Lo
   último se comprueba mandando algo bueno después de cada entrada mala; sin eso, un fallo que mate el
   hilo del servidor pasa por verde.
