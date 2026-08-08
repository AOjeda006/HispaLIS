---
tipo: referencia
stack: [java, typescript, dart]
aplica_a: [backend, web-profesional, app-ciudadano, infra]
revisado: 2026-08-08
tags: [adr, smart-on-fhir, oauth2, oidc, keycloak, seguridad, testigo]
---

# ADR-0024: El contexto de lanzamiento de SMART no está dentro del testigo

- **Estado:** aceptado
- **Fecha:** 2026-08-08

## Contexto

SMART on FHIR devuelve, junto al testigo de acceso, el **contexto de lanzamiento**: sobre qué paciente
se ha abierto la aplicación, si hace falta enseñar la banda con su nombre, qué recurso estaba en
pantalla. La norma es explícita en dónde vive eso: son **parámetros JSON de la respuesta del canje**,
hermanos de `access_token` y `expires_in`, y **no reclamaciones dentro del testigo**.

```json
{
  "access_token": "…",
  "token_type": "Bearer",
  "expires_in": 300,
  "scope": "openid fhirUser launch/patient patient/*.rs",
  "patient": "1a2b3c",
  "need_patient_banner": true
}
```

La razón es buena: **el testigo de acceso es opaco para el cliente**. Puede ser un JWT o puede ser una
cadena de referencia; puede estar cifrado; y aunque sea un JWT, un cliente que lo abre para deducir
sus permisos está leyendo un documento que no está dirigido a él. La norma lo dice y lo repite: el
cliente no parsea el testigo.

Contra eso chocan tres cosas medidas en este proyecto:

1. **Un servidor de identidad puede no saber poner nada en la respuesta del canje.** Keycloak 26.4
   tiene la opción `access.tokenResponse.claim` en sus *mappers*. Con
   `oidc-usermodel-attribute-mapper` —el que lee un atributo del usuario, que es exactamente lo que
   hace falta para el `patient`— **la opción se guarda y no tiene efecto**: se comprueba consultando
   la API de administración, está ahí, y la respuesta del canje sale sin el parámetro. Con
   `oidc-hardcoded-claim-mapper` sí funciona, así que no es un fallo de configuración: es que el
   *mapper* que sabe leer el dato no sabe escribirlo en la respuesta.
2. **Quien decide de quién son los datos es el servidor de recursos, no el contexto.** Si el
   laboratorio se fiara del `patient` que la aplicación dice tener, bastaría con cambiarlo. Así que el
   `patient` que gobierna el acceso es el que viaja **dentro del testigo**, firmado por el emisor, y el
   de la respuesta del canje solo sirve para que la aplicación sepa qué pedir sin adivinar.
3. **Un *scope* concedido no es un *scope* pedido.** El servidor puede conceder menos, o distinto. Un
   cliente que asume que le han dado lo que pidió descubre el recorte en forma de `403` a mitad de una
   pantalla.

## Decisión

**El contexto se lee de donde dice la norma, con una alternativa documentada para cuando no llegue, y
el testigo no se abre nunca en el cliente.**

- **El cliente lee `patient` de la respuesta del canje**, no del testigo. Es lo correcto y es lo que
  funcionará con cualquier servidor conforme.
- **Si no llega, cae al `fhirUser`**, y el `fhirUser` se pide al **`userinfo` del emisor**, no
  abriendo el `id_token`. Verificar la firma de un JWT en el cliente exigiría traerse el JWKS y hacer
  RSA en un móvil; una llamada TLS directa al emisor, autenticada con el testigo recién obtenido, da
  la misma garantía sin escribir criptografía en una aplicación. Y si el servidor no declara
  `sso-openid-connect` en su descubrimiento, ni se pregunta.
- **Ese contexto no es control de acceso y se dice donde se define.** Sirve para no pedir a ciegas y
  para saber a quién se le está enseñando la pantalla. Quien decide de quién es cada recurso es el
  laboratorio, recurso a recurso, y contesta `403` si no.
- **El servidor de recursos toma el sujeto de la reclamación firmada del testigo**, jamás de un
  parámetro de la petición ni de lo que diga el cliente.
- **Se guardan los *scopes concedidos*, no los pedidos**, y la aplicación se ajusta a ellos.
- **No se añade configuración que no hace nada.** Se probó a poner `access.tokenResponse.claim` en el
  *mapper* del `patient` y se revirtió al comprobar que no tiene efecto: una línea de configuración
  inerte acaba explicando un comportamiento que no existe, y el siguiente que la lea creerá que el
  parámetro llega.

## Consecuencias

- La aplicación funciona contra este Keycloak **y** contra un servidor que sí devuelva el parámetro,
  sin cambiar una línea: prefiere lo correcto y tolera lo real.
- El cliente nunca necesita saber si el testigo es un JWT. Puede cambiar de formato, cifrarse o pasar
  a ser opaco y no se entera.
- Hay una llamada HTTP de más por sesión (`userinfo`). A cambio, cero criptografía en el cliente y
  cero dependencias de verificación de JWT en un móvil.
- **Queda una asimetría que conviene tener presente:** el `fhirUser` sirve para una aplicación de
  ciudadano, donde el usuario **es** el paciente. En una aplicación de profesional no sirve de nada —el
  `fhirUser` es un `Practitioner`— y el contexto de paciente tiene que llegar por el lanzamiento. Ahí
  la limitación del servidor de identidad sí duele, y la salida es el `launch` opaco del EHR.
- **Se generaliza a cualquier OAuth con contexto**: el sitio donde el proveedor te devuelve
  información extra no es el sitio donde va la autorización, y confundirlos hace que el cliente lea
  un documento que no es suyo o que el servidor se fíe de la parte manipulable.

## Alternativas consideradas

- **Abrir el testigo en el cliente y sacar el `patient` de ahí.** Funciona con este Keycloak, porque
  el testigo es un JWT y lleva la reclamación. Descartada: acopla el cliente al formato del testigo,
  lo obliga a verificar una firma para poder fiarse de lo que lee, y contradice la norma en el punto
  donde más explícita es.
- **Cambiar el *mapper* a `oidc-hardcoded-claim-mapper`, que sí honra la opción.** Descartada: es
  *hardcoded*. Puede poner `true` en `need_patient_banner` —y ahí sí se usa— pero no puede leer el
  atributo del usuario, que es todo el problema.
- **Un `SecurityContext` propio: un endpoint del laboratorio que diga «quién soy».** Descartada por
  ahora: inventa una API fuera de la norma para tapar una limitación de un servidor de identidad
  concreto, y el día que se cambie de servidor queda un endpoint propietario que ya nadie necesita.
- **Un atributo del usuario leído por la propia aplicación desde el `userinfo`,** que es lo que
  finalmente se hace, **pero sin intentar antes el parámetro del canje.** Descartada: haría a la
  aplicación dependiente de una particularidad de Keycloak en vez de conforme a la norma con un plan
  B.
