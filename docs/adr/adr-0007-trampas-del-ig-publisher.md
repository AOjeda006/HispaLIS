---
tipo: referencia
stack: [fsh]
aplica_a: [ig]
revisado: 2026-08-03
tags: [adr, ig-publisher, sushi, toolchain, ci, jekyll, fhir]
---

# ADR-0007: Las cuatro trampas del IG Publisher que hay que resolver antes de escribir FSH

- **Estado:** aceptado
- **Fecha:** 2026-08-03

## Contexto

Al andamiar `ig/` (ítem 1 del hito 1) la IG **no contenía todavía ni un perfil**: solo
`sushi-config.yaml`, los alias y la portada. Aun así, la cadena de construcción falló **cuatro veces
seguidas por motivos distintos**, ninguno relacionado con FHIR ni con el contenido de la guía.

Las cuatro son de *toolchain*, se manifiestan con mensajes que no apuntan a su causa, y **las cuatro
aparecen antes de que el primer perfil exista**. Documentarlas tiene valor más allá de este proyecto:
son las mismas que se encontrará cualquiera que monte una IG con SUSHI + IG Publisher hoy.

### 1. SUSHI retiró la propiedad `template` de `sushi-config.yaml`

Casi toda la documentación y los tutoriales existentes dicen que se ponga `template:` en
`sushi-config.yaml` y que SUSHI genera el `ig.ini`. **Ya no.** SUSHI falla con un error explícito y
obliga a mantener `ig.ini` a mano.

### 2. El `ig.ini` no admite líneas de comentario

Añadir un comentario `;` antes de `[IG]` hace que el publisher aborte con:

> `The IG Publisher was unable to find an ig.ini, and hasn't been configured correctly`

El mensaje culpa a la **ausencia** del fichero, cuando el fichero está y el problema es su
**contenido**. Comprobado en aislamiento: falla igual con un comentario ASCII puro
(`; comentario simple ascii`) que con uno acentuado, así que **no es un problema de codificación —
es que el parser no tolera comentarios**.

### 3. El IG Publisher se niega a construir si hay un espacio en la ruta

> `java.lang.Error: There is a space in the folder path: "…\PROYECTOS Y REPOS\HispaLIS\ig".`
> `Please fix your directory arrangement to remove the space and try again`

No es un aviso: es una negativa. Afecta **solo al desarrollo local** —en GitHub Actions la ruta es
`/home/runner/work/HispaLIS/HispaLIS`— pero impide construir la IG en cualquier máquina cuyo árbol de
carpetas tenga un espacio, que en Windows es lo habitual (`Mis documentos`, `Program Files`).

### 4. `fhir.base.template` dejó de considerarse segura, y Jekyll no viene incluido

El publisher avisa de que `fhir.base.template` **ya no es segura ni está mantenida** (un investigador
la usó para demostrar un paquete npm malicioso; el nombre está bloqueado en npmjs.com) y de que *"en
algún momento próximo, el IG Publisher se negará a ejecutarse"* con ella. La sustituta es
`fhir2.base.template`.

Además, el publisher **genera el HTML con Jekyll**, que no forma parte del `.jar` ni viene
preinstalado en `ubuntu-latest`. Sin Jekyll, la construcción recorre entera la fase FHIR —carga de
paquetes, validación, narrativas— y **muere al final**, al renderizar las páginas. Es el fallo más
caro de diagnosticar de los cuatro, porque todo lo que uno mira parece haber ido bien.

## Decisión

1. **`ig.ini` se mantiene a mano, versionado y sin una sola línea de comentario.** La explicación de
   por qué existe y por qué no lleva comentarios vive fuera del fichero: en este ADR y en la memoria
   técnica (§11.1).
2. **La plantilla es `fhir2.base.template#current`**, desde el primer commit. No se adopta
   `fhir.base.template` ni siquiera de forma transitoria: está anunciada su retirada.
3. **La CI instala Ruby y Jekyll explícitamente** (`ruby/setup-ruby` + `gem install jekyll`) antes de
   invocar al publisher.
4. **La restricción del espacio en la ruta se documenta como requisito de entorno local**, no se
   intenta sortear. Quien tenga un espacio en su ruta construye la IG en la CI o clona el repositorio
   en una ruta sin espacios.

## Consecuencias

**A favor:**

- Las cuatro trampas están resueltas **antes** de que exista el primer perfil, que es cuando salen
  baratas. Encontrarlas con nueve perfiles escritos habría mezclado fallos de *toolchain* con fallos
  de perfilado, que es exactamente el escenario en el que se pierde una tarde.
- Al fijar `fhir2.base.template` desde el día uno, la retirada anunciada de la plantilla vieja no
  genera ninguna migración futura.

**En contra:**

- `ig/ig.ini` es un fichero de configuración **sin comentarios que expliquen para qué sirve**, lo cual
  contradice la práctica habitual del proyecto. Se compensa documentándolo aquí y en la memoria
  técnica (§11.1), pero es
  una excepción que hay que recordar: **quien añada un comentario "de mejora" rompe la construcción**.
- El desarrollo local de la IG queda condicionado a la ruta del clon, algo que no se puede arreglar
  desde el repositorio.

## Alternativas consideradas

- **Dejar `template:` en `sushi-config.yaml` y no versionar `ig.ini`.** Descartada: SUSHI ya no lo
  admite, no es una preferencia.
- **Confiar en que el runner traiga Jekyll.** Descartada: `ubuntu-latest` trae Ruby, pero no Jekyll.
- **Renombrar el directorio de trabajo para quitar el espacio.** Descartada: es el árbol de carpetas
  personal del usuario, no algo que decida este repositorio. Se documenta y se sigue.
- **Seguir con `fhir.base.template` hasta que rompa.** Descartada: la retirada está anunciada, y
  migrar una IG con nueve perfiles es más caro que empezar bien.
