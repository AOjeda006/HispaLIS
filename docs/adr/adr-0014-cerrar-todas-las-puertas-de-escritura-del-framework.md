---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-06
tags: [adr, arquitectura, ddd, invariantes, framework, hapi, interceptores, testing]
---

# ADR-0014: Un framework que también escribe tiene varias puertas, y hay que cerrarlas una a una

- **Estado:** aceptado
- **Fecha:** 2026-08-06

## Contexto

El patrón de este proyecto —núcleo de dominio propio con el framework como **proyección** (ADR-0002)—
descansa en una premisa: **todo lo que se escribe pasa por el núcleo**. La premisa es correcta y la
implementación la incumplió **dos veces**, de dos maneras distintas y ninguna visible.

**Primera puerta: la herencia.** El proveedor de `Patient` hereda del de HAPI y solo sustituye la
creación, para quedarse gratis con la lectura, el `_history` y el `ETag`. Se dio por hecho que el
`update` heredado también valdría. No valía: **escribía la proyección FHIR y dejaba el dominio
atrás**, sin un solo error. Un `PUT` devolvía `200`, el `GET` siguiente devolvía el recurso
corregido, y la fila del dominio seguía con el valor viejo. La forma más barata de que las dos
mitades de un sistema se separen es que una de ellas se actualice sola.

**Segunda puerta: la que no se buscaba.** Un `Bundle` de tipo `transaction` no pasa por los
`ResourceProvider`: el procesador de transacciones de HAPI **llama a las DAO directamente**. Un
`POST /fhir` con un `Patient` dentro devolvía `201 Created` con un id numérico de HAPI, sin número de
historia validado y sin fila en `dominio.paciente`. El recurso quedaba publicado y no existía ningún
paciente.

Las dos comparten forma: **el framework tiene su propio camino de escritura, y sustituir el que se
conoce no cierra los que no**. Y las dos fallan en silencio, que es lo que impide que aparezcan
solas.

## Decisión

**Enumerar las puertas de escritura del framework y cerrar cada una explícitamente**, con la regla de
que ante la duda se prefiere el fallo visible al comportamiento callado:

1. **Lo heredado se prueba antes de darlo por bueno.** Que un método venga de la clase base no dice
   nada sobre si respeta el invariante: hay que escribir el test que lo comprueba, y escribirlo
   **contra el dominio**, no contra la proyección. El del `PUT` lee `dominio.paciente` con SQL: leer
   la proyección lo habría dado por bueno.
2. **Lo que no tiene reglas definidas se rechaza, no se permite.** Los cuatro recursos sin reglas de
   modificación devuelven un error explícito ante un `PUT`, en vez de dejar que el heredado escriba a
   medias. Entre un fallo visible y una corrupción callada, el fallo visible.
3. **Las puertas laterales se cierran con un interceptor en la capa que las dispara.** Un interceptor
   sobre `STORAGE_TRANSACTION_PROCESSING` rechaza las transacciones que tocan recursos con agregado.
4. **La lista de lo protegido se deduce, no se escribe.** El interceptor pregunta a los proveedores
   propios registrados qué tipos gobiernan. Una lista a mano nace correcta y envejece mal: dar de
   alta un proveedor nuevo lo protege solo.

## Consecuencias

- **La premisa de la arquitectura vuelve a ser cierta**, y esta vez hay tests que lo dicen.
- **Cada puerta cerrada cuesta un test en rojo primero.** Los dos fallos se descubrieron escribiendo
  un test que esperaba un error y recibía un `201`; ninguno se veía leyendo el código.
- **El interceptor hay que registrarlo donde se dispara el punto de enganche.** Los `STORAGE_*` los
  dispara la capa JPA, no el `RestfulServer`, y registrarlo en el registro equivocado **no da ningún
  error**: simplemente no se llama nunca. En un interceptor que cierra una puerta, esa es la peor
  forma posible de fallar, porque el sistema parece protegido.
- **Quedan puertas por revisar cuando se abran:** `PATCH`, las operaciones `$…` y el `conditional
  create`. Ninguna está habilitada hoy; el día que se habilite alguna, entra en esta lista.
- **Coste para el cliente:** un `Bundle transaction` deja de servir para dar de alta pacientes. Es
  una limitación real y se devuelve dicha con todas las letras, con la instrucción de enviarlos uno a
  uno.

## Alternativas consideradas

- **Mover los invariantes a la capa de persistencia** —comprobarlos en la DAO, por debajo de todas
  las puertas— pondría reglas de negocio en infraestructura y dejaría el núcleo sin ellas, que es
  exactamente lo que ADR-0002 quiere evitar.
- **No heredar del proveedor del framework** y escribir cada operación. Cierra la primera puerta por
  construcción y cuesta reimplementar `_history`, el `ETag` y la búsqueda, que ya funcionan bien. No
  cierra la segunda, que ni siquiera pasa por el proveedor.
- **Confiar en la revisión de código.** Es lo que había: las dos puertas pasaron revisión, porque lo
  que falta no se ve. Un invariante que depende de que alguien se acuerde no es un invariante.
- **Restricciones en la base de datos.** Cubren la integridad referencial y no las reglas de negocio
  —«una muestra rechazada no produce resultados» no es una clave ajena—, y dejarían el mensaje de
  error en manos del motor.
