---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-06
tags: [adr, seguridad, privacidad, phi, api-rest, fhir, busqueda, logs]
---

# ADR-0016: Un identificador de paciente no viaja en la URL de búsqueda

- **Estado:** aceptado
- **Fecha:** 2026-08-06

## Contexto

La pantalla de alta de petición empieza buscando al paciente por su número de historia. La forma
obvia, y la que enseña cualquier tutorial de FHIR, es

```
GET /fhir/Patient?identifier=https://…/sid/nhc|00123456
```

Esa petición funciona, es conforme y **deja el identificador del paciente escrito en cuatro sitios de
los que no se borra**: la barra del navegador, su historial, el registro de acceso del proxy y la
traza del servidor. Ninguno de los cuatro está pensado para guardar datos de salud, ninguno se cifra
por defecto y ninguno se purga con los datos del paciente. El del proxy es el peor: lo guarda una
pieza de infraestructura que a menudo ni siquiera administra quien administra la aplicación.

No es un problema exclusivo de FHIR ni de sanidad: pasa con cualquier API donde el **criterio de
búsqueda** sea el dato sensible. Y no lo arregla HTTPS, porque el problema no es el transporte sino
lo que cada extremo escribe en disco.

El proyecto lo tenía escrito como invariante desde el primer día —«nunca PHI en URLs, logs, trazas ni
analítica»— y aun así el primer cliente lo incumplió, porque la forma que incumple es la que sale en
todos los ejemplos.

## Decisión

**La web busca con `POST [tipo]/_search`**, con los criterios en el cuerpo como
`application/x-www-form-urlencoded`. Es una forma **estándar** de FHIR, prevista exactamente para
esto, y devuelve el mismo `Bundle` que el `GET`.

El servidor **sigue admitiendo las dos formas**. El `GET` es lo que pide el criterio de aceptación de
búsqueda y paginación, y sigue probado: retirarlo sería incumplir la conformidad para resolver un
problema que es del cliente, no del servidor. Lo que se decide es **por cuál entra la aplicación**.

La comprobación no se queda en la petición de ida. Un test del backend verifica también que **el
enlace de la página siguiente que devuelve el servidor no reintroduce el identificador en una URL**:
sería inútil sacar el dato de la petición para que el propio servidor lo devolviera dentro de un
enlace que el cliente va a pedir a continuación.

## Consecuencias

- **El identificador deja de aparecer en los cuatro sitios**, comprobado contra la pila real y no
  solo en un test.
- **La petición deja de ser cacheable e idempotente para los intermediarios.** Es el precio real de
  la decisión, y aquí no cuesta nada: una búsqueda de pacientes no se debe cachear.
- **Un servidor FHIR que no implemente `_search` rompería el cliente.** Es parte de la
  especificación, no una extensión, así que el riesgo es teórico; queda anotado por si algún día se
  habla con un servidor ajeno.
- **Cuesta un poco más de leer al depurar:** el criterio ya no se ve en la URL del panel de red del
  navegador, hay que abrir la pestaña del cuerpo. Es exactamente el efecto que se busca.
- **No exime de lo demás.** Sacar el dato de la URL no autoriza a escribirlo en un log de
  aplicación; el invariante sigue siendo el mismo en los cuatro sitios.

## Alternativas consideradas

- **Buscar por el id lógico del recurso en vez de por el identificador.** No sirve para el caso: en
  el mostrador se conoce el número de historia, y llegar al id lógico exige justamente la búsqueda
  que se quiere evitar.
- **Un identificador opaco de un solo uso** (un token que el servidor traduce). Resuelve el problema
  y añade estado en el servidor y una pieza más que mantener, para un caso que el estándar ya
  contempla.
- **Configurar el proxy y el servidor para que no registren la cadena de consulta.** Deja fuera el
  navegador y su historial, y hace depender la privacidad de una configuración que cualquiera puede
  revertir sin enterarse de lo que revierte.
- **Cifrar el valor en la URL.** Un dato cifrado en la URL sigue siendo un dato en la URL: es
  estable, se puede correlacionar entre peticiones y sigue guardado donde no debe estar.
