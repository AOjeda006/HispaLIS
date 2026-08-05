---
tipo: referencia
stack: [java, spring, maven]
aplica_a: [java]
revisado: 2026-08-05
tags: [adr, maven, spring-boot, postgres, entorno-local, docker, testing]
---

# ADR-0013: Arrancar en local sin Docker, con el PostgreSQL que ya usan los tests

- **Estado:** aceptado
- **Fecha:** 2026-08-05

## Contexto

El backend de HispaLIS no levanta su contexto de Spring sin una base de datos real: el servidor JPA
de HAPI necesita conectarse al arrancar. En el equipo de desarrollo **no hay Docker instalado**, así
que `./mvnw spring-boot:run` muere con `Connection to localhost:5432 refused`.

Los **tests** sí funcionan, porque arrancan un binario real de PostgreSQL en proceso
(`io.zonky.test:embedded-postgres`, ADR del ítem 6). Pero esa base de datos la levanta un
`@DynamicPropertySource`, que es un mecanismo del *TestContext framework* de Spring: **solo existe
dentro de un test**. `spring-boot:run` no lo ve.

El problema no era académico. La web del profesional se escribió entera —cliente HTTP y dos
pantallas— sin poder ejercitarla nunca contra la API, y dos de sus supuestos solo se pueden
comprobar con el servidor en marcha: que la búsqueda por `POST _search` funcione a través de un
proxy, y que el enlace `Bundle.link[relation=next]` vuelva apuntando a un sitio al que el navegador
pueda ir. Los dos son fallos que **no aparecen hasta la segunda página** y que ningún test del
servidor detecta.

## Decisión

Una clase con `main` en **`src/test/java`** —`ArranqueLocal`— que arranca el PostgreSQL embebido,
publica su URL en las **mismas variables de entorno que lee `application.yaml`**, y llama a
`SpringApplication.run` sobre la aplicación de producción. Se invoca con un perfil de Maven:

```bash
./mvnw spring-boot:run -Parranque-local
```

Vive en `src/test` porque el PostgreSQL embebido es una dependencia de alcance `test` y ahí debe
quedarse: **esto no es un modo de despliegue**, es una comodidad de desarrollo. Publica la
configuración por las variables de entorno existentes y no por un mecanismo propio, para que no
haya un segundo camino de configuración que se pueda desviar del de producción.

**La trampa está en el perfil, y cuesta media hora encontrarla.** El parámetro `useTestClasspath`
del `spring-boot-maven-plugin` añade las **dependencias** de alcance `test` pero **no las clases
compiladas de `src/test`**, así que la clase de arranque no está en el classpath que se ejecuta.
Hace falta añadirla aparte:

```xml
<configuration>
  <mainClass>es.hispalis.backend.ArranqueLocal</mainClass>
  <useTestClasspath>true</useTestClasspath>
  <additionalClasspathElements>
    <additionalClasspathElement>${project.build.testOutputDirectory}</additionalClasspathElement>
  </additionalClasspathElements>
</configuration>
```

Y va como **perfil versionado y no como `-D` en la orden**: `useTestClasspath` se ignora al pasarlo
por línea de órdenes —`-Dspring-boot.run.mainClass` también, porque el plugin nombra sus propiedades
de usuario en *kebab-case* (`spring-boot.run.main-class`)—, con lo que la orden falla por dos
motivos a la vez y ninguno se menciona en el error.

## Consecuencias

- **El proyecto entero se puede ejecutar en una máquina sin Docker**, que es exactamente la situación
  en la que empieza cualquiera que clone el repositorio.
- **Lo que se prueba es la aplicación de producción**, no una variante: el `main` solo aporta la base
  de datos, y la configuración entra por donde entraría en cualquier entorno.
- **Cada arranque empieza con la base vacía.** La base se crea en un directorio temporal y desaparece
  al parar el proceso. Para una demo hay que dar de alta los datos, que es justo lo que hacen las
  pantallas; para un corpus grande está el generador.
- **No sustituye al `docker compose`.** El compose sigue siendo el criterio de aceptación y lo que
  usa cualquiera que no vaya a compilar el proyecto.
- **El diagnóstico del fallo es engañoso:** si algo de esto se rompe, el error es
  `ClassNotFoundException` de la propia clase de arranque, que invita a buscar un error de
  compilación o de paquete cuando el problema es el classpath del plugin.

## Alternativas consideradas

- **Instalar PostgreSQL en la máquina** — resuelve el caso propio y no el de nadie más: no viaja con
  el repositorio, y quien clone vuelve a estar donde estábamos.
- **Esperar al `docker compose` del ítem 15** — era el plan y estaba mal: habría dejado toda la web
  escrita y sin ejercitar hasta el final del hito, que es precisamente cuando descubrir un fallo de
  supuestos cuesta más caro. Además el compose depende de que el usuario instale Docker, o sea de
  algo que no está en nuestras manos.
- **Un `@SpringBootTest` que se quede dormido** — funciona y es peor: convierte un modo de arranque
  en un test que no comprueba nada, y cualquier ejecución de la batería se quedaría colgada en él.
- **Un `docker-compose` solo con PostgreSQL** — misma dependencia de Docker, y la mitad del problema.
- **H2 o PostgreSQL en modo compatible** — descartado ya en el ítem 6 por la misma razón que allí: el
  dialecto no es el de producción y esconde precisamente los fallos que se buscan.
