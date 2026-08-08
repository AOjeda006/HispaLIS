---
tipo: referencia
stack: [java, kafka, docker]
aplica_a: [backend, infra]
revisado: 2026-08-08
tags: [adr, kafka, particiones, orden, outbox, eventos, testing]
---

# ADR-0023: Con una sola partición, una clave de reparto mal elegida parece correcta

- **Estado:** aceptado
- **Fecha:** 2026-08-08

## Contexto

Kafka garantiza el orden **dentro de una partición**, y nada más. Elegir la clave de reparto es, por
tanto, decidir **qué secuencias tienen que llegar en orden**: es una decisión de dominio disfrazada de
detalle de configuración.

En este laboratorio la secuencia que importa es la del paciente. `PETICION_CREADA` →
`ESPECIMEN_RECIBIDO` → `RESULTADO_MEDIDO` → `RESULTADO_VALIDADO` → `INFORME_EMITIDO` tienen que
llegarle a un consumidor en ese orden **para el mismo paciente**; entre pacientes distintos el orden
no significa nada. Así que la clave es el `pacienteId`.

Lo que hace este ADR necesario son tres formas de que eso parezca correcto sin serlo:

1. **Con una sola partición, cualquier clave produce orden total.** Un tópico de una partición ordena
   todo con todo, así que una clave mal elegida —o ninguna clave— pasa todas las pruebas. El día que
   alguien añada particiones para escalar, el orden se rompe en producción, con el código intacto y
   sin ningún cambio que señalar.
2. **La creación automática de tópicos esconde el error un paso más.** Con
   `auto.create.topics.enable` en `true`, un tópico mal escrito en un cliente **nace solo**, con una
   partición y la configuración por defecto del broker, y nadie se entera hasta que falta un mes de
   eventos en el tópico bueno.
3. **Los reintentos del productor desordenan.** Un productor que reintenta y tiene más de una petición
   en vuelo puede entregar el reintento de la primera después de la segunda. No pierde nada —de ahí
   que parezca inofensivo— pero **desordena mensajes de la misma clave**, que es justamente la
   garantía por la que se eligió la clave.

Y un cuarto, que es de prueba y no de producción: **el broker embebido de los tests ignora el puerto
que se le pide**. Medido en este proyecto: pidiendo el 40245 abrió el 40247. Fijar el puerto es lo
único que permite arrancar la aplicación apuntando a un broker que **todavía no existe**, que es
exactamente el escenario que hay que probar —el circuito tiene que aceptar escrituras con el bus
caído—. Sin puerto fijo, ese test no se puede escribir.

## Decisión

**La clave de partición se elige como una decisión de dominio, se escribe donde se ve, y la
configuración se pone de forma que un error no pueda pasar desapercibido.**

- **Clave = `pacienteId`**, puesta en el `outbox` en la misma transacción que el dominio y arrastrada
  hasta el productor. El campo se llama `clave_de_particion` en la tabla: no es «un identificador que
  además se usa para repartir», es la clave de reparto.
- **Tres particiones por tópico, no una.** No es escalado —este laboratorio no lo necesita—: es que
  con una sola partición el orden sale bien por accidente y nadie nota que la clave está mal puesta.
  Con tres, un reparto incorrecto se ve en la primera ejecución.
- **`auto.create.topics.enable = false`**, y los cuatro tópicos los crea un servicio de arranque con
  sus particiones y su retención explícitas. Un tópico mal escrito en un cliente falla en el acto en
  vez de nacer solo.
- **Productor idempotente**, que es lo que fija el número de peticiones en vuelo a un valor que no
  desordena. La idempotencia se pide por el efecto que la gente asocia a otra cosa —no duplicar— pero
  aquí se pide sobre todo por **no desordenar**.
- El puerto del broker embebido se fija por el hueco que deja la librería, y **queda anotado como
  riesgo**: al subir de versión hay que buscar otra forma —un proxy TCP delante, o repuntar el
  productor en caliente—.

## Consecuencias

- El orden por paciente es una propiedad probada y no una casualidad de la configuración.
- Añadir particiones más adelante no rompe nada, porque el reparto ya se está ejercitando.
- Un tópico nuevo exige tocar el servicio que los crea. Es fricción a propósito: un tópico es un
  contrato, y los contratos no se crean por un error tipográfico en un cliente.
- **Se generaliza a cualquier bus con particiones o colas con clave de sesión** —Kinesis, Service Bus,
  Pub/Sub ordenado—. La pregunta siempre es la misma: *¿qué secuencias tienen que llegar en orden?*, y
  la respuesta siempre es de dominio. Y el corolario también: **un entorno de pruebas con una sola
  partición no prueba el orden**, lo finge.

## Alternativas consideradas

- **Sin clave (reparto por turnos).** Descartada: reparte de maravilla y desordena todo. Un consumidor
  vería `INFORME_EMITIDO` antes que `RESULTADO_VALIDADO` para el mismo paciente.
- **Clave = `peticionId`.** Descartada por poco, y merece explicarse: ordena bien dentro de una
  petición, que es casi todo. Pero un paciente puede tener dos peticiones abiertas a la vez, y hay
  consumidores —el notificador EDO del hito 3, cualquier vista de historial— cuyo estado es por
  paciente y no por petición. Con la clave por petición, esos consumidores ven dos secuencias que se
  intercalan sin orden entre sí.
- **Una sola partición y olvidarse del problema.** Descartada: funciona hoy y falla el día que alguien
  escale, sin ningún cambio de código que culpar. El fallo más caro es el que se introduce por
  configuración años después de que el código estuviera bien.
- **Dejar la creación automática encendida y crear los tópicos «por si acaso».** Descartada: entonces
  el servicio de arranque no es la fuente de verdad, es una sugerencia. La configuración de un tópico
  creado solo no coincide con la del creado a mano, y el que gana es el que llegó primero.
- **Ordenar en el consumidor por marca de tiempo.** Descartada: exige una ventana de espera —¿cuánto?—
  y relojes de acuerdo entre productores. Es rehacer mal lo que el bus ya hace bien.
