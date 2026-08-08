---
tipo: referencia
stack: [java, python, docker]
aplica_a: [integracion, simuladores, infra]
revisado: 2026-08-08
tags: [adr, mllp, tls, seguridad, hl7-v2, keystore, integracion]
---

# ADR-0022: El TLS de un canal no se configura con propiedades de la JVM

- **Estado:** aceptado
- **Fecha:** 2026-08-08

## Contexto

MLLP es un protocolo desnudo: un byte de inicio, el mensaje, dos bytes de fin. No tiene cabeceras, no
tiene negociación, no tiene nombre de servidor y no tiene versión. Todo lo que en HTTP se acuerda en
el propio protocolo —quién es el servidor, qué se acepta, qué codificación— en MLLP se acuerda
**fuera de banda**, en la configuración de las dos partes. Eso incluye el TLS.

La forma que ofrece la librería HL7 de Java para poner TLS en un *listener* es
`SSLServerSocketFactory.getDefault()`, y eso lee las propiedades **globales de la JVM**:

```
-Djavax.net.ssl.keyStore=/ruta/al/almacen.p12
-Djavax.net.ssl.keyStorePassword=…
-Djavax.net.ssl.trustStore=…
```

Funciona, y tiene dos problemas que se pagan tarde:

1. **Afecta a todo lo que abra un socket en el proceso.** El motor de integración no es solo un
   *listener* MLLP: también es un **cliente HTTPS de la API FHIR del laboratorio** y un cliente del
   servidor de identidad. Poner un `trustStore` global para que el canal v2 confíe en el certificado
   del HIS cambia, de paso, en quién confía el cliente FHIR. Y poner un `keyStore` global significa
   que cualquier conexión TLS saliente del proceso puede presentar el certificado del canal v2 como
   certificado de cliente. Nadie lo escribió así; sale así.
2. **La contraseña del almacén queda en la línea de órdenes**, donde la ve cualquiera que liste
   procesos, y en el fichero de arranque, y en el log del gestor de servicios.

Hay un tercer problema, específico de una simulación pero real en cualquier proyecto: **el material
criptográfico del servidor no se puede versionar**. Un PKCS#12 con su clave privada dentro de un
repositorio es una clave publicada, y no vale decir «es de desarrollo»: lo que se copia el día que
alguien monte el entorno de verdad es el patrón, con el fichero incluido.

Y un cuarto, que apareció al intentar probar el camino completo: **Java y Python no comen el mismo
formato**. Java quiere un almacén (`PKCS12`/`JKS`) con contraseña; el módulo `ssl` de Python quiere un
par de ficheros PEM. Un certificado generado para un extremo no sirve tal cual para el otro, así que
«tenemos TLS en el canal» no implica «hemos ejercitado el canal con TLS de punta a punta».

## Decisión

**Un `SSLContext` propio por canal, construido en código desde su almacén, y el almacén se genera al
levantar.**

- El *listener* MLLP construye su `SSLContext` a partir de un `KeyStore` que carga él mismo, con la
  ruta y la contraseña que le llegan por **su** configuración (`hispalis.mllp.tls.*`), no por
  propiedades de la JVM. El certificado del canal v2 es del canal v2 y de nada más.
- La contraseña llega por **variable de entorno**, nunca por argumento de línea de órdenes ni desde
  un fichero versionado.
- **TLS encendido por defecto.** Apagarlo es una decisión que hay que escribir en la configuración, y
  el arranque la avisa en voz alta. Arrancar en claro sin decirlo pondría en la red el nombre y el
  documento de identidad de cada paciente.
- **Sin almacén y con TLS encendido, el arranque falla** con un mensaje que dice qué configurar y cuál
  es la alternativa explícita. No arranca en claro «por si acaso».
- **El almacén se genera al levantar la pila**, en un servicio de arranque del `compose` que crea un
  autofirmado en un volumen. No hay ni un byte de material criptográfico en el repositorio.

## Consecuencias

- El motor puede confiar en una CA para el canal v2 y en otra distinta —o en la del sistema— para el
  cliente FHIR, que es lo que un motor de integración necesita de verdad: los dos extremos son
  organizaciones distintas.
- Se pierde la comodidad de «lo configuro con dos `-D` y ya». A cambio, la configuración del canal se
  lee en el mismo sitio que el resto de la configuración del canal.
- **Queda un cabo suelto y conviene que se vea:** el camino TLS del emisor está cubierto por un test
  que levanta un HIS con certificado, pero el receptor Python del simulador **no se ha ejercitado con
  TLS a mano**, precisamente por la asimetría PKCS#12/PEM. Está anotado como pendiente y no como
  hecho.
- **Se generaliza a cualquier protocolo sin nombres de servidor**: AS2, SFTP con clave de host, un
  socket propietario de un analizador. En todos, la identidad del otro extremo es configuración, y la
  configuración de un canal no puede ser global al proceso.

## Alternativas consideradas

- **Propiedades de la JVM, que es lo que documenta la librería.** Descartada por lo de arriba: el
  proceso tiene más de un cliente TLS y no todos hablan con la misma organización.
- **Un proceso por canal, para que las propiedades globales sí sean por canal.** Descartada: resuelve
  el aislamiento con un despliegue en vez de con código, y multiplica por tres lo que hay que
  arrancar, vigilar y actualizar.
- **Terminar el TLS en un proxy delante del `listener`.** Descartada, y es una decisión de diseño ya
  tomada (D4): el plano de sistemas va **cifrado de extremo a extremo**. Un proxy que termina el TLS
  convierte el último salto en claro, y ese último salto lleva mensajes v2 completos, que son PHI de
  principio a fin.
- **Versionar un almacén de desarrollo «que no importa».** Descartada. El fichero se copia; la
  costumbre se copia; y un repositorio con una clave privada dentro entrena a todo el mundo a que eso
  es normal.
- **Apagar TLS en desarrollo y encenderlo «luego».** Descartada: «luego» es el nombre de las cosas que
  no se hacen. Generar el certificado al levantar cuesta seis líneas y deja el camino real ejercitado
  en cada arranque.
