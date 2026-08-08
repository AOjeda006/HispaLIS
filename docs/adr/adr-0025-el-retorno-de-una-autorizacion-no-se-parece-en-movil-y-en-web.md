---
tipo: referencia
stack: [dart, flutter, android, ios]
aplica_a: [app-ciudadano]
revisado: 2026-08-08
tags: [adr, oauth2, pkce, smart-on-fhir, flutter, android, ios, web, seguridad]
---

# ADR-0025: El retorno de una autorización no se parece en móvil y en web

- **Estado:** aceptado
- **Fecha:** 2026-08-08

## Contexto

El flujo de código de autorización tiene tres pasos: se abre el navegador, la persona se identifica, y
el navegador **devuelve el control** a la aplicación con el código en la URL de redirección. Los dos
primeros son iguales en todas partes. El tercero no se parece en nada de una plataforma a otra, y cada
plataforma tiene su forma propia de fallar en silencio.

**En móvil** la vuelta llega por un **esquema propio** (`es.hispalis.ciudadano://callback`) que el
sistema operativo tiene que saber que es tuyo. Si no está declarado en el manifiesto, el navegador se
queda con la redirección, la aplicación no se entera de nada y el flujo muere en una pestaña abierta:
**sin error, sin excepción y sin nada que buscar en un log**. Y aun declarándolo, en Android la
actividad que lo recoge necesita `android:taskAffinity=""`; sin eso, volver de la autorización abre
una segunda instancia de la aplicación y la sesión que se acaba de abrir se pierde por el camino.

**En web** no hay esquema que registrar. Hace falta una **página de verdad**, servida desde el mismo
origen que la aplicación, que reciba la redirección y le pase la URL a la ventana que abrió la
autorización con `postMessage`. Esa página es un fichero más del proyecto y su ausencia produce
exactamente el mismo síntoma: una pestaña en blanco y nada más. Y el `postMessage` tiene que ir
dirigido a `window.location.origin` y **nunca a `'*'`**: la URL de vuelta lleva el código de
autorización, y mandarlo a cualquier origen es regalárselo a la primera página que esté escuchando.

**En el emulador de Android**, `localhost` es el emulador. La máquina donde corre la pila de
desarrollo es `10.0.2.2`. Un `redirect_uri`, un `aud` o una base de API apuntando a `localhost`
funcionan en web, en iOS con simulador, en un dispositivo con reenvío de puertos… y fallan solo ahí.

Y hay dos cosas más que no son del retorno pero se descubren en el mismo sitio, cuando se compila para
publicar y deja de funcionar lo que funcionaba:

- **Flutter declara `INTERNET` solo en los manifiestos de depuración y de perfil.** Una aplicación que
  habla con un servidor y se compila en modo *release* se queda muda, y el mensaje no dice que falte
  un permiso.
- **La pila de desarrollo habla HTTP en claro**, y Android lo bloquea. La salida cómoda es
  `android:usesCleartextTraffic="true"`, que abre el HTTP en claro **contra todo internet** en el
  paquete que se publica, para que funcione un `localhost`.

## Decisión

**El retorno se resuelve por plataforma, en la configuración de cada plataforma, y la aplicación no
tiene ni un `if` que pregunte dónde está corriendo** — salvo uno, el del emulador, que está en un solo
sitio y con nombre.

- **Un único `redirect_uri` fijo y registrado por plataforma**, calculado al arrancar y nunca
  construido con datos de entrada: el esquema propio en móvil, `${origen}/auth.html` en web.
- **El esquema propio se declara en las dos plataformas**: `CallbackActivity` con su `intent-filter` y
  `taskAffinity=""` en `AndroidManifest.xml`, y `CFBundleURLTypes` en `Info.plist`. El literal es el
  mismo que la constante de la configuración, y eso se dice en un comentario en los tres sitios.
- **La página `web/auth.html` es parte del proyecto**, con su `postMessage` dirigido al origen.
- **La diferencia del emulador se resuelve en la configuración, no en la cabeza de quien arranque**:
  la base FHIR por defecto es `10.0.2.2` cuando la plataforma es Android nativo y `localhost` en todo
  lo demás, en una función de tres líneas con el porqué escrito al lado. Y se puede fijar al compilar
  con `--dart-define`.
- **`INTERNET` va al manifiesto principal**, no solo al de depuración.
- **Un `network_security_config.xml` nombra los tres anfitriones de desarrollo uno a uno** —
  `10.0.2.2`, `localhost`, `127.0.0.1` — y deja el resto del mundo exigiendo TLS. La regla general
  sigue siendo «TLS obligatorio»; lo que se abre es una excepción con nombre y apellidos.
- **Nunca un `WebView` empotrado**, y siempre sesión efímera. Un `WebView` dentro de la aplicación
  puede leer lo que la persona teclea y no enseña de quién es el dominio donde lo teclea, que son las
  dos cosas que hacen creíble una pantalla de identificación. La sesión efímera evita que en un
  dispositivo compartido el siguiente que abra la aplicación entre como el anterior.

## Consecuencias

- El flujo funciona igual en Android, iOS y web, y la lógica de autorización no sabe en cuál está.
- Cada plataforma nueva —escritorio, por ejemplo— es una entrada más de configuración y ninguna línea
  de lógica.
- **El coste real es de coordinación:** el mismo literal de esquema vive en cuatro sitios (la
  constante Dart, el manifiesto de Android, el `Info.plist` y la lista de redirecciones del servidor
  de identidad), y no hay ningún mecanismo que compruebe que coinciden. Lo único que se puede hacer es
  dejarlo dicho en los cuatro; está dicho.
- **Se generaliza a cualquier OAuth en cliente nativo**, y el patrón de fallo es el que hay que
  recordar: los errores de retorno **no producen errores**. Producen una pestaña abierta y una
  aplicación esperando. Cuando un flujo de autorización «no hace nada», el sitio donde mirar es el
  registro del esquema y la lista de redirecciones del servidor, no el código.

## Alternativas consideradas

- **Un `WebView` empotrado, que hace desaparecer todo el problema del retorno** porque la aplicación
  ve la navegación. Descartada, y no por comodidad: es el antipatrón que la norma señala. La
  aplicación puede leer las credenciales y la persona no puede comprobar en qué dominio las está
  escribiendo.
- **`android:usesCleartextTraffic="true"`.** Descartada: resuelve el desarrollo empeorando el
  producto. Tres líneas de configuración de red hacen lo mismo sin abrir el HTTP contra todo internet.
- **App Links / Universal Links** (`https://…` verificado por el dominio) en vez de esquema propio.
  Es más seguro —un esquema propio lo puede reclamar otra aplicación— y descartada **por ahora**: exige
  publicar un fichero de asociación en un dominio de verdad, y este laboratorio no tiene dominio. Es
  la primera cosa que cambiar si esto dejara de ser una simulación, y por eso se anota aquí.
- **Un servidor local efímero en el dispositivo escuchando la redirección** (el patrón de las
  herramientas de línea de órdenes). Descartada en móvil: abrir un puerto en un teléfono para recibir
  un código de autorización es peor que el esquema propio en todos los aspectos.
- **Detectar el emulador en tiempo de ejecución** en vez de mirar la plataforma. Descartada: no hay
  forma fiable de hacerlo, y el valor por defecto correcto para un Android nativo apuntando a una pila
  de desarrollo es `10.0.2.2` tanto en emulador como en un dispositivo con `adb reverse`.
