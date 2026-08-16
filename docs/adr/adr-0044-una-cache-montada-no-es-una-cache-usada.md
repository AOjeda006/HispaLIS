---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-16
tags: [adr, docker, maven, cache, toolchain, documentacion-ejecutable]
---

# ADR-0044: Una caché montada no es una caché usada

- **Estado:** aceptado
- **Fecha:** 2026-08-16

## Contexto

El README documenta cómo correr las puertas de Java en un contenedor con el JDK de la CI, porque el
del equipo es más nuevo y rompe el formateador. La orden llevaba, desde el primer día, un volumen para
reaprovechar la caché de dependencias:

```bash
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD":/repo -v "$HOME/.m2":/tmp/.m2 -w /repo/backend \
  eclipse-temurin:21-jdk ./mvnw clean verify
```

**Ese volumen no cachea nada.** Maven no mira `$HOME`: el repositorio local sale de `${user.home}`, y
la JVM resuelve `user.home` por el **uid contra `/etc/passwd`**, no por el entorno. En
`eclipse-temurin` el uid 1000 ya existe y se llama `ubuntu`, así que las dependencias se van a
`/home/ubuntu/.m2/repository` —dentro del contenedor— y el `--rm` se las lleva. Cada ejecución vuelve
a bajarse el árbol entero.

Lo que hacía el engaño perfecto es que **el volumen no estaba vacío**: el guion del *wrapper* sí
respeta `$HOME`, así que en `~/.m2` aparecía puntualmente `wrapper/dists/…` con la distribución de
Maven. Un `ls` decía que la caché funcionaba. Solo contarlo decía la verdad: **11 MB en el volumen
del anfitrión mientras dentro del contenedor iban ya 538 MB**, y al terminar el `--rm` se llevaba los
538.

Y hay una segunda cara. La otra orden del README, la del formato, iba con `-o`:

```
Cannot access confluent (https://packages.confluent.io/maven/) in offline mode
and the artifact org.springframework.boot:spring-boot-starter-parent:pom:3.5.16
has not been downloaded from it before.
```

`-o` exige una caché poblada, y **nada de la página la poblaba**. Funcionaba en el equipo donde se
escribió porque allí había medio giga de tandas anteriores; en un equipo recién montado, la primera
orden de la sección no llega ni a leer el `pom`.

## Decisión

1. **La orden lleva `-Dmaven.repo.local=/tmp/.m2/repository`**, que es lo único que hace que el montaje
   sirva. Medido: con la caché en frío, `verify` del backend tarda **954 s**; el de `integracion`, con
   la caché ya poblada por el anterior, **140 s**.
2. **Se le quita el `-o` a la comprobación de formato.** Una orden de arranque no puede depender de un
   estado que la documentación no crea.
3. **Las dos cosas van con su ⚠️ y el porqué al lado**, porque son dos banderas que quien lea el README
   dentro de un año va a querer quitar por parecer ruido.

## Consecuencias

- La primera vuelta sigue siendo lenta y ahora se sabe por qué: el `pom` declara el repositorio de
  Confluent —hace falta, las *serdes* de Avro no están en Central— y **un repositorio declarado en el
  `pom` se consulta antes que el heredado**, así que cada artefacto del árbol pide primero a Confluent,
  se lleva un fallo y vuelve a pedir a Central. Se documenta y **no se toca el `pom`**: reordenar
  repositorios al cierre del proyecto cambia de dónde sale cada `jar` a cambio de unos minutos que se
  pagan una sola vez.
- Queda anotado que la CI **no** tenía este problema: usa `cache: maven` de `actions/setup-java`, que
  es exactamente lo que la orden local creía estar haciendo.

## Alternativas consideradas

- **Poner `MAVEN_OPTS=-Dmaven.repo.local=…` en el entorno del contenedor.** Igual de válido y menos
  visible: la bandera en la orden se lee, una variable de entorno en un `-e` se copia sin mirar.
- **Crear el usuario dentro del contenedor con `--user` y un `HOME` de verdad.** Arregla `user.home`
  y obliga a montar `/etc/passwd`, que es más frágil que una bandera.
- **Dejar `-o` y documentar que hay que calentar la caché antes.** Un paso previo no escrito en la
  orden es un paso que no se da.

## Lo reutilizable

1. **Montar un volumen donde crees que la herramienta escribe no es comprobar que escribe ahí.** Se
   comprueba con `du` después de la primera vuelta, no con `ls`: un volumen con algo dentro parece un
   volumen que funciona.
2. **`$HOME` no es `user.home`.** La JVM lo resuelve por el uid contra `/etc/passwd`, así que
   `-e HOME=/tmp` no mueve el `~/.m2` de Maven, ni el `~/.gradle`, ni nada que pregunte a la
   plataforma en vez de al entorno. Y las imágenes oficiales traen el uid 1000 ya ocupado.
3. **Una orden con `-o` (o `--offline`, o `--frozen-lockfile`, o `--no-network`) hereda un
   prerrequisito que hay que escribir.** Si la documentación no dice quién llena la caché, la orden
   solo funciona en el equipo de quien la escribió.
4. **La documentación ejecutable se verifica ejecutándola desde cero, y el síntoma de estas dos
   trampas no es un error: es tiempo.** Nada falla, todo tarda, y por eso puede vivir años sin que
   nadie lo mire.
