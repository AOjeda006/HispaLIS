---
tipo: referencia
stack: [typescript, angular, vitest]
aplica_a: []
revisado: 2026-08-15
tags: [adr, testing, cobertura, bundler, vitest, angular, portabilidad]
---

# ADR-0038: Medir la cobertura cambió lo que se medía

- **Estado:** aceptado
- **Fecha:** 2026-08-15

## Contexto

La primera vez que se midió la cobertura de la web —`ng test --coverage`, con el ejecutor `vitest` del
constructor `@angular/build:unit-test`— tres tests que llevaban semanas en verde se cayeron:

```
Error: ENOENT: no such file or directory,
       open 'C:\Users\Predator\Desktop\ig\input\fsh\aliases.fsh'
```

Cuatro niveles por encima de la raíz del repositorio. Sin `--coverage`, los mismos tres pasaban.

El test cruza los `Identifier.system` que la web lleva escritos contra `ig/input/fsh/aliases.fsh`, que
es su fuente de verdad, y resolvía la ruta así:

```ts
const ALIASES = join(__dirname, '..', '..', '..', '..', 'ig', 'input', 'fsh', 'aliases.fsh');
```

Contando desde `src/app/fhir/`, esos cuatro `..` llegan exactamente a la raíz del repositorio. La
cuenta está bien. Lo que está mal es la premisa: **`__dirname` dentro de un `.spec` no es el
directorio del fichero**. Los specs se transforman y se empaquetan antes de correr, y a qué apunta
`__dirname` en el resultado depende de cómo el *bundler* haya inlineado ese módulo. Sin cobertura
resolvía al directorio del spec y la cuenta cuadraba; al activar la cobertura cambia la
transformación —el proveedor `v8` necesita otra instrumentación—, `__dirname` pasó a ser la raíz del
proyecto Angular y los cuatro `..` se fueron a `Desktop`.

Los tres tests no estaban en verde porque el código estuviera bien. Estaban en verde **por
casualidad**, y la casualidad se habría roto sola con la siguiente versión de `@angular/build`, sin
que nadie tocara nada suyo.

## Decisión

**La ruta se calcula desde `process.cwd()`**, que el constructor de Angular fija en la raíz del
proyecto la ejecute quien la ejecute:

```ts
const ALIASES = join(process.cwd(), '..', 'ig', 'input', 'fsh', 'aliases.fsh');
```

Con eso `ng test` y `ng test --coverage` dan lo mismo. El *porqué* va en el fichero, junto a la
constante: sin él, el siguiente que lo lea contará los niveles, verá que sobran tres y lo «arreglará»
de vuelta.

## Consecuencias

- El test depende del directorio de trabajo en vez de depender del empaquetado. Es una dependencia
  peor a la vista y mejor de verdad: el directorio de trabajo lo fija el constructor y está
  documentado; a qué apunta `__dirname` tras el empaquetado, no.
- Si algún día se ejecuta `vitest` a mano desde la raíz del repositorio, el test fallará con un
  `ENOENT` claro en vez de leer otro fichero.
- Queda una pregunta abierta que no se resuelve aquí: **un test que lee un fichero de otro componente
  del monorepo es frágil por definición**. La alternativa —copiar los alias y cruzarlos en la CI— tiene
  sus propios problemas, y la copia sin cruce es justo lo que este test existe para impedir.

## Alternativas consideradas

- **`import.meta.url`.** Misma familia de problema: también la reescribe el empaquetado, y con las dos
  formas conviviendo el fallo sería más difícil de leer, no menos.
- **Una variable de entorno con la raíz del repositorio.** Funciona y obliga a configurar el entorno
  para correr los tests, que es exactamente lo que un `npm test` no debería pedir.
- **Mover el `aliases.fsh` o duplicarlo dentro de la web.** La copia es el defecto que el test caza.
- **Dejar de medir cobertura en la web.** Habría dejado los tres tests en verde por casualidad y sin
  que nadie lo supiera, que es peor que tenerlos en rojo.

## Lo reutilizable

1. **Activar la cobertura cambia cómo se transforma el código.** No es un observador pasivo: instrumenta,
   y con ello puede cambiar rutas, `sourcemaps`, identidad de módulos y tiempos. Un test que pasa sin
   cobertura y falla con ella **no es un fallo de la herramienta de cobertura**: es un test que
   dependía de un detalle del empaquetado.
2. **`__dirname` y `import.meta.url` no son fiables dentro de un test empaquetado.** Si un test lee un
   fichero del disco, la ruta se ancla a algo que el ejecutor garantice —el directorio de trabajo— o se
   inyecta.
3. **Medir cobertura la primera vez es un test del arnés antes que del código.** En este proyecto, la
   primera medición encontró tres tests frágiles, dos métodos que no llamaba nadie y una regla de
   negocio duplicada en dos sitios con la peor de las dos copias en uso — y ni una sola de esas cosas
   se veía en el porcentaje.
