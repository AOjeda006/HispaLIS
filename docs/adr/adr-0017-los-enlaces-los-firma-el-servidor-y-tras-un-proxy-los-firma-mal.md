---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-06
tags: [adr, api-rest, hateoas, paginacion, proxy, nginx, docker, fhir]
---

# ADR-0017: Los enlaces los firma el servidor, y detrás de un proxy los firma mal

- **Estado:** aceptado
- **Fecha:** 2026-08-06

## Contexto

La paginación de FHIR es HATEOAS pura: el servidor devuelve `Bundle.link[relation=next]` y **el
cliente tiene que tratar esa URL como opaca**. No debe construirla, ni corregirla, ni deducir de ella
un desplazamiento — lleva el identificador de una búsqueda cacheada, y un cliente que se invente
`&_getpagesoffset=…` funciona hasta que el servidor cambia de estrategia y entonces se salta
resultados en silencio.

Para escribir ese enlace, el servidor necesita saber **su propia dirección pública**, y lo único que
tiene es la petición que le ha llegado. Detrás de un proxy —que es como lo alcanza siempre un
navegador— esa no es la dirección del cliente. En el `compose` de este proyecto la web se sirve tras
nginx, así que el servidor firmaba los enlaces como `http://backend:8080/fhir?…`: un nombre que solo
resuelve dentro de la red de Docker.

Lo que convierte esto en una trampa cara es la combinación de tres cosas:

- **El cliente no puede arreglarlo**, y hace bien: para él la URL es opaca por contrato. La única
  pieza que puede corregirla es la que la escribe.
- **El fallo no aparece hasta la segunda página.** Toda la primera página funciona. Cualquier prueba
  manual con pocos datos pasa.
- **Ningún test del servidor lo detecta**, porque en un test el cliente llega directo y la dirección
  que ve el servidor sí es la buena.

Es un caso concreto de algo general: **cualquier API que se autorreferencie** —paginación, `Location`
de un `201`, `Link` de RFC 8288, un `_links` de HAL— tiene este problema en cuanto hay un salto de
red por medio.

## Decisión

**Cerrarlo por los dos lados**, porque cada uno sabe algo que el otro no.

- **El proxy dice quién es el cliente.** nginx envía `X-Forwarded-Host` y `X-Forwarded-Proto`; el
  servidor de desarrollo de Angular hace lo mismo con `"xfwd": true`. Sin esas cabeceras, el backend
  no tiene forma de saberlo, y adivinar sería peor.
- **El servidor las respeta.** Se le da una estrategia de dirección que usa las cabeceras
  `X-Forwarded-*` cuando llegan y **cae a la dirección de la petición cuando no**. Así el mismo
  binario sirve igual detrás de un proxy y sin él, sin configuración por entorno.

**Y se prueba en los dos sitios**, porque cada test dice algo distinto:

- Un test del backend envía las cabeceras a mano y comprueba que el enlace sale con la dirección
  reenviada. Prueba que el servidor las respeta.
- La verificación contra la pila del `compose` comprueba que el enlace vuelve como
  `http://localhost:4200/fhir?…` **y que la segunda página se alcanza siguiéndolo tal cual**. Prueba
  que el proxy las envía, que es lo que el test del backend no puede saber.

## Consecuencias

- **La paginación funciona desde el navegador**, que es donde nunca se había ejercitado.
- **Aparece una dependencia de confianza en el proxy.** El servidor cree lo que le digan esas
  cabeceras, así que **quien lo despliegue tiene que asegurarse de que el proxy las fija y no las
  reenvía tal cual** desde el cliente: un cliente que las mande a su antojo puede hacer que el
  servidor firme enlaces hacia donde quiera. Es aceptable en una red cerrada de `compose` y hay que
  revisarlo en cuanto haya un despliegue de verdad.
- **La configuración del proxy pasa a ser parte del contrato**, no un detalle de infraestructura. Las
  dos líneas de nginx llevan un comentario que dice exactamente qué se rompe si se quitan: sin él,
  cualquiera las borraría al limpiar.
- **La lección vale para el resto de la API**, no solo para la paginación: la cabecera `Location` de
  un `201` se construye igual y se arregla con lo mismo.

## Alternativas consideradas

- **Configurar la dirección pública a mano**, con una propiedad por entorno. Funciona y hay que
  acordarse de cambiarla en cada despliegue; el día que no se cambie, el fallo vuelve exactamente
  igual de silencioso. Además rompe el caso de acceder al backend directamente.
- **Que el cliente reescriba el enlace** quedándose con la ruta y la consulta. Es lo que hace mucha
  gente y contradice el contrato: si el cliente puede reconstruir la URL, ya no la trata como opaca,
  y el día que el servidor pagine de otra manera el cliente se salta resultados sin avisar.
- **Servir el backend en su propio dominio, sin proxy.** Quita el problema y trae CORS, un origen
  distinto para el token del hito 2 y una pieza más que configurar.
- **Confiar en `Forwarded` (RFC 7239) en vez de `X-Forwarded-*`.** Es la cabecera estándar y la
  soporta menos software; se puede adoptar el día que toda la cadena la hable.
