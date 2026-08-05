---
tipo: referencia
stack: [sql, java, python]
aplica_a: []
revisado: 2026-08-06
tags: [adr, flyway, migraciones, configuracion, datos-maestros, duplicacion, monorepo]
---

# ADR-0015: Los datos de configuración no van en las migraciones de esquema

- **Estado:** aceptado
- **Fecha:** 2026-08-06

## Contexto

Los **rangos de referencia** del laboratorio —entre qué cifras es normal cada prueba— se sembraron con
un `INSERT` dentro de la migración de Flyway que creaba su tabla. Parecía el sitio natural: son datos
que el sistema necesita desde el primer arranque y no cambian a menudo.

El generador de datos sintéticos necesita **los mismos números** para sortear valores verosímiles.
Está escrito en Python, no puede alcanzar esa base de datos, y el camino corto fue escribirlos otra
vez en un módulo suyo. Quedaron dos copias y **nada comprobaba que coincidieran**.

Lo que hace peligrosa esa duplicación es que **no rompe nada al divergir**. Los dos ficheros son
válidos por separado; el corpus generado sigue pasando el validador oficial; los tests de los dos
lados siguen en verde. Lo único que cambia es que el generador produce resultados que el laboratorio
interpreta de otra manera — y eso solo se ve comparando un valor concreto con el rango que el
laboratorio publica para él. Al unificarlas se comprobó que **ya habían divergido**: una copia
escribía los límites como enteros y la otra con un decimal, así que el mismo rango se publicaba como
`4`–`11` o como `4.0`–`11.0` según quién lo escribiera.

La causa de fondo no es el descuido. Es que **meter datos en una migración los convierte en
esquema**: a partir de ahí, cambiar un número exige escribir otra migración, y copiarlos en otro
sitio se vuelve el camino más corto. La migración no es un sitio para guardar datos; es el registro
de cómo llegó la base a su forma actual.

## Decisión

**Un fichero de datos versionado como fuente única, leído por todos los que lo necesiten.** La
migración se queda con la estructura y, cuando los datos son solo configuración, **la tabla
desaparece**: los rangos se leen del fichero al arrancar y viven en memoria.

La regla, en tres preguntas:

- ¿**Alguien del sistema escribe** estas filas en ejecución? Si no, no son datos: son configuración.
  Una tabla cuyo único escritor es una migración es un fichero de configuración con pasos de más.
- ¿Las necesita **más de un componente**, y en más de un lenguaje? Entonces el sitio es un fichero
  común, no la base de datos de uno de ellos.
- ¿Son **vocabulario compartido** con quien hable con el sistema? Si lo son, van a la guía de
  implementación como terminología. Si no —los rangos dependen del método y del analizador de cada
  laboratorio—, van al fichero de configuración.

El fichero vive **en el árbol del componente que los publica**, no en una carpeta neutra: el
laboratorio es la autoridad sobre sus propios rangos y el generador es un consumidor, igual que la
guía publica el catálogo de pruebas y el generador lo lee.

**Las garantías que daba la base de datos hay que reponerlas al leer.** Dos índices únicos parciales
impedían definir dos rangos para el mismo paciente; al salir de la tabla, esa garantía se habría
perdido en silencio, porque duplicar una línea de un JSON no rompe el JSON. Se comprueban al cargar,
**en cada lenguaje que lo lee**: validar la propia entrada no es duplicar lógica.

## Consecuencias

- **Los dos componentes no pueden divergir**, porque solo hay un sitio donde escribirlos.
- **Cambiar un rango es editar una línea**, no escribir una migración y desplegar.
- **Aparece un acoplamiento nuevo y hay que declararlo en la CI.** El generador depende de un fichero
  del backend, así que el *workflow* de Python vigila también esa ruta: sin eso, cambiar un rango no
  dispararía los tests que comprueban que el generador lo carga. Un fichero compartido cuyo cambio no
  ejecuta ninguna prueba es peor que dos copias.
- **La tabla se borra en una migración nueva, no editando la que la creó.** Cambiar una migración ya
  aplicada le rompe la suma de comprobación a Flyway, y cualquier base que la hubiera pasado —el
  volumen de quien haya levantado la pila una vez— se negaría a arrancar. La migración que borra
  documenta además por qué.
- **Si el fichero falta o es incoherente, el proceso no arranca.** Es lo que se quiere: mejor no
  levantar que servir durante horas un rango a medias.
- **No vale para todo.** Datos que el sistema sí modifica en ejecución, o que crecen, siguen siendo
  datos y siguen en la base.

## Alternativas consideradas

- **Dejar las dos copias y añadir un test que las cruce.** Detecta la divergencia en vez de
  impedirla, y obliga a que el test conozca los dos formatos. Cuesta casi lo mismo que unificar y
  deja el problema en pie.
- **Mantener la tabla y sembrarla al arrancar desde el fichero.** El fichero sería la fuente y la
  tabla una copia materializada, con sus restricciones intactas. Se descartó porque añade un paso
  —un sembrador idempotente— para sostener una tabla que nadie escribe y que solo se lee entera.
- **Publicar los rangos en la guía de implementación**, junto al catálogo. Sería afirmar que son
  vocabulario compartido, y no lo son: dos laboratorios que usan el mismo código publican rangos
  distintos sin contradecirse.
- **Una carpeta neutra en la raíz del repositorio.** Simétrica, y deja el fichero sin dueño: nadie
  responde de su contenido y su ruta no pertenece al `paths:` de ningún *workflow*.
