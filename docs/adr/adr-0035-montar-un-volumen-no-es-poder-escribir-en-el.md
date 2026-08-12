---
tipo: referencia
stack: [docker, compose]
aplica_a: []
revisado: 2026-08-12
tags: [adr, docker, compose, volumenes, permisos, contenedores-sin-privilegios]
---

# ADR-0035: Montar un volumen no es poder escribir en él

- **Estado:** aceptado
- **Fecha:** 2026-08-12

## Contexto

`$export` escribe los NDJSON en `HISPALIS_EXPORT_DIR`. El `compose` ya lo tenía previsto y hasta
explicado:

```yaml
# … el proceso corre como `hispalis` (UID 10001) y no puede crear directorios bajo `/var/lib`,
# así que sin el volumen la primera exportación fallaría al escribir.
HISPALIS_EXPORT_DIR: /var/lib/hispalis/exportaciones
volumes:
  - exportaciones:/var/lib/hispalis/exportaciones
```

El razonamiento es correcto y la conclusión es falsa. Un volumen de Docker recién creado pertenece a
`root:root` con `0755`, y **montarlo no cambia quién puede escribir dentro**. El proceso, que corre
como 10001, sigue sin poder crear la carpeta del trabajo:

```
java.nio.file.AccessDeniedException: /var/lib/hispalis/exportaciones/b7aa7e60-…
```

Lo caro fue **dónde apareció**. `$export` es asíncrono: el `POST` contesta `202` con su
`Content-Location` y el trabajo se ejecuta en otro hilo. Así que la petición que falla va bien, y el
error sale doscientos milisegundos después en el primer sondeo, como un `400` sobre un trabajo que
«ha fallado» — sin decir por qué desde el lado del cliente. Y en la pila de esta casa el mismo
problema **ya estaba resuelto para otro servicio**: `terminologia-datos` es un `busybox` que hace
`chown` sobre el volumen del índice, con un comentario que cuenta exactamente esto. La lección
estaba escrita y no se aplicó al segundo volumen.

## Decisión

**Cada volumen que vaya a escribir un proceso sin privilegios lleva su servicio de inicialización.**

```yaml
exportaciones-datos:
  image: busybox:1.37
  command: ['sh', '-c', 'mkdir -p /datos && chown -R 10001:999 /datos && chmod 750 /datos']
  volumes:
    - exportaciones:/datos
```

Y el servicio que escribe **espera a que termine bien**:

```yaml
depends_on:
  exportaciones-datos:
    condition: service_completed_successfully
```

## Consecuencias

- La exportación funciona desde el primer arranque sobre un volumen nuevo, que es el caso que
  importa: tras un `docker compose down -v` no queda nada que arrastre el permiso de antes.
- Un servicio más en el `compose` por cada volumen escribible. Es ruido, y es el precio de no darle
  privilegios al que escribe.
- `chmod 750`: los NDJSON son el único fichero de esta pila con datos de varias personas dentro. Que
  no los lea cualquier otro proceso del contenedor no cuesta nada.

## Alternativas descartadas

- **`user: root` en el servicio.** Arregla el síntoma dándole al servidor FHIR permisos de
  administrador de todo el contenedor para que pueda crear una carpeta. Es cambiar el problema de
  sitio, y el sitio nuevo es peor.
- **Crear el directorio en el `Dockerfile` con el dueño correcto.** No sirve: el montaje del volumen
  tapa lo que hubiera en esa ruta de la imagen, con su propietario incluido.
- **`tmpfs` en vez de volumen.** Resolvería el permiso y rompería otra cosa: un fichero a medio
  descargar desaparecería al reiniciar el contenedor. Que se borre es trabajo de la caducidad y del
  barrendero, que saben cuándo toca.
- **Que la aplicación cree el directorio con permisos relajados.** No puede: el fallo es justo que no
  puede crearlo.

## Lo reutilizable

1. **Un volumen nuevo es de `root`.** Vale para Docker y para Compose, con cualquier imagen que corra
   sin privilegios — que hoy son casi todas las bien hechas, y todas las *distroless*. Montar da
   visibilidad, no permiso.
2. **El comentario que explica un problema no lo arregla.** Aquí estaba escrito el diagnóstico
   correcto y aun así faltaba el `chown`. Un `AccessDeniedException` en un directorio montado es
   siempre la misma causa: mirar el dueño antes que el código.
3. **Un fallo en un trabajo asíncrono aparece en otro sitio y con otra cara.** El `202` es honesto y
   el error sale en el sondeo. Cuando algo se ejecuta fuera de la petición, el primer sitio donde
   mirar no es la respuesta: es el log del proceso que lo ejecuta.
4. **Si la pila ya resolvió esto para otro servicio, hay un patrón que aplicar y no un caso
   particular.** Dos volúmenes escribibles y un solo `chown` es una asimetría que se paga en el
   primer arranque limpio.
