---
tipo: referencia
stack: [java, spring]
aplica_a: [backend]
revisado: 2026-08-08
tags: [adr, spring-security, servlet, hapi, fhir, autorizacion, oauth2]
---

# ADR-0020: Una regla de seguridad que no casa deja la puerta abierta en silencio

- **Estado:** aceptado
- **Fecha:** 2026-08-08

## Contexto

La API FHIR no la sirve el `DispatcherServlet` de Spring MVC: la sirve el `RestfulServer` de HAPI,
registrado como servlet propio en `/fhir/*` (ADR-0011). Spring MVC sigue estando en el *classpath*
—HAPI lo arrastra, y el resto de la aplicación lo usa—, y eso cambia el significado de una línea que
parece obvia:

```java
http.securityMatcher("/fhir/**")
```

Con `spring-webmvc` presente, Spring Security 6 interpreta esa cadena como un patrón **de Spring
MVC** y construye un `MvcRequestMatcher`, que resuelve la ruta preguntándole al `DispatcherServlet`.
Para una petición que atiende **otro** servlet, ese emparejador no casa.

El resultado es que la cadena de seguridad se construye, se registra y el arranque la anuncia con
toda naturalidad:

```
o.s.s.web.DefaultSecurityFilterChain : Will secure Or [Mvc [pattern='/fhir/**']] with filters: …
```

…y ni una sola petición pasa por ella. **La API queda abierta.** No hay excepción, no hay aviso, no
hay `WARN`. Se detectó porque un test pedía sin testigo y esperaba `401`: lo que llegó fue el `403`
del interceptor de HAPI, que es la segunda capa —la que sí funcionaba— contestando en lugar de la
primera. Sin ese test, la única señal habría sido que todo iba bien.

## Decisión

**Las rutas de la cadena de seguridad se emparejan sobre la URL de la petición, nunca a través de
Spring MVC**, y se declara un único constructor de emparejadores para que no haya dos formas de
hacerlo en el mismo fichero:

```java
private static final PathPatternRequestMatcher.Builder RUTAS = PathPatternRequestMatcher.withDefaults();

http.securityMatcher(RUTAS.matcher(RUTA_BASE + "/**"))
    .authorizeHttpRequests(rutas -> rutas
        .requestMatchers(RUTAS.matcher(RUTA_METADATA), RUTAS.matcher(RUTA_DESCUBRIMIENTO)).permitAll()
        .anyRequest().authenticated());
```

Y **hay un test que pide sin testigo y exige `401`**, distinguiéndolo explícitamente del `403` que
daría la capa de HAPI. Es la única forma de vigilar esto: el modo de fallar es «todo verde y la
puerta abierta».

La regla general: **en una aplicación con más de un servlet, `requestMatchers(String)` y
`securityMatcher(String)` son ambiguos y no deben usarse.**

## Consecuencias

- La cadena se aplica a `/fhir/**` de verdad, y las dos rutas públicas —`metadata` y
  `.well-known/smart-configuration`— se exceptúan igual de explícitamente.
- El `PathPatternRequestMatcher` empareja contra el camino dentro de la aplicación, así que un
  contexto de despliegue distinto de `/` sigue funcionando sin tocar nada.
- Queda una dependencia de una clase concreta de Spring Security (`PathPatternRequestMatcher`, 6.5+)
  en vez de la API de cadenas. Es el precio de que la regla signifique lo que dice, y está barato.
- **Lo que no arregla:** cualquier otra regla que se añada mañana con una cadena volverá a ser un
  emparejador de MVC. Por eso hay una sola constante `RUTAS` y el motivo escrito encima de ella.

## Alternativas consideradas

- **`AntPathRequestMatcher`.** Empareja sobre la URI y habría valido igual. Descartada porque está
  deprecada desde Spring Security 6.5 justo en favor de `PathPatternRequestMatcher`: adoptar hoy algo
  que hay que quitar mañana, para el mismo resultado.
- **Publicar HAPI dentro del `DispatcherServlet`.** Haría que los patrones de MVC funcionaran, y a
  cambio metería el servidor FHIR dentro del ciclo de vida de MVC —conversión de mensajes, manejo de
  excepciones, negociación de contenido—, que es precisamente lo que un servidor FHIR hace por su
  cuenta y de otra manera. Cambiar la arquitectura del borde para que un emparejador case es
  arreglarlo por el lado equivocado.
- **Confiar en el interceptor de autorización de HAPI y no poner cadena de Spring.** Es lo que estaba
  pasando sin querer, y no vale: el interceptor sabe de *scopes*, no de firmas. Quien comprueba que
  el testigo está firmado por el emisor correcto, no ha caducado y va dirigido a este servidor es el
  filtro. Sin él, un testigo inventado llegaría hasta las reglas de HAPI sin que nadie hubiera mirado
  la criptografía.
