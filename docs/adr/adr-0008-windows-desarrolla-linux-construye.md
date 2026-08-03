---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, git, ci, portabilidad, windows, linux, gitattributes, permisos]
---

# ADR-0008: Se desarrolla en Windows y se construye en Linux — las dos cosas que git no lleva igual

- **Estado:** aceptado
- **Fecha:** 2026-08-03

## Contexto

HispaLIS se desarrolla en Windows y su integración continua corre en `ubuntu-latest`. Entre esos dos
sistemas hay **dos atributos de fichero que git trata de forma asimétrica**, y los dos rompen la
construcción **solo en el runner**, nunca en la máquina del desarrollador:

1. **Los finales de línea.** Git en Windows convierte a CRLF al extraer y, sin configuración, puede
   almacenar CRLF. Un script de shell con CRLF hace que Linux busque un intérprete llamado
   `/bin/sh\r` y falle con `bad interpreter: No such file or directory`.
2. **El bit de ejecución.** NTFS no tiene bit de ejecución POSIX, así que git en Windows arranca con
   `core.filemode=false` y **registra todo fichero nuevo como `100644`**. En Linux, `./mvnw` sobre un
   fichero `100644` da `Permission denied`.

El segundo es más traicionero que el primero por tres motivos: no hay ninguna configuración que lo
prevenga —`.gitattributes` **no** gobierna el modo—, el fichero se ve perfectamente normal en el
editor, y el fallo aparece **la primera vez que la CI ejecuta ese script**, que puede ser semanas
después de haberlo commiteado.

En este proyecto pasó exactamente eso. El problema de los finales de línea se previó al andamiar el
monorepo y se resolvió con `.gitattributes` antes del primer push. El del bit de ejecución **no se
previó**: `backend/mvnw` se commiteó como `100644` y el fallo no apareció hasta que la CI del backend
se ejecutó por primera vez, tres commits después, con el mensaje escueto
`./mvnw: Permission denied`.

## Decisión

Se adoptan **dos medidas distintas**, porque los dos problemas no se arreglan igual:

**Finales de línea — configuración, en `.gitattributes` en la raíz.** Normaliza a LF en el
repositorio todo lo textual, y fija explícitamente lo que debe conservar CRLF:

```gitattributes
* text=auto eol=lf
*.sh      text eol=lf
mvnw      text eol=lf
gradlew   text eol=lf
*.bat     text eol=crlf
*.cmd     text eol=crlf
*.jar     binary
```

**Bit de ejecución — comprobación, porque no hay configuración que lo resuelva.** Todo script que la
CI invoque como `./script` se marca explícitamente al añadirlo:

```bash
git update-index --chmod=+x <ruta>
git ls-files -s <ruta>    # debe empezar por 100755
```

**Regla operativa:** al andamiar un componente cuya CI ejecute un script del repositorio, se comprueba
el modo en el índice **en el mismo commit** que lo añade. No se delega en que el runner lo descubra.

## Consecuencias

- **La CI deja de fallar por motivos que no tienen nada que ver con el código.** Los dos fallos
  cuestan lo mismo de arreglar que de diagnosticar, y diagnosticarlos es lo caro: ninguno de los dos
  mensajes de error menciona ni Windows, ni git, ni el atributo real que falla.
- **`.gitattributes` tiene que existir antes del primer commit de código.** Añadirlo después obliga a
  renormalizar el árbol (`git add --renormalize .`), que produce un diff enorme y sin valor.
- **El bit de ejecución no se puede automatizar del todo desde Windows**, porque el sistema de
  ficheros no lo sostiene: queda como comprobación explícita. Un *hook* de pre-commit que rechace
  scripts `100644` sería la evolución natural si el proyecto acumulase más scripts; con uno solo no
  se justifica.
- **En el equipo de desarrollo no cambia nada visible.** Los dos atributos solo se manifiestan al
  otro lado.

## Alternativas consideradas

- **Configurar `core.autocrlf=input` en la máquina del desarrollador** — se descartó: es
  configuración **local**, no viaja con el repositorio y no protege a quien clone después. La
  corrección tiene que estar versionada.
- **Poner `core.filemode=true` en Windows** — no resuelve nada: git lo desactiva precisamente porque
  NTFS no puede sostener el atributo, y forzarlo produce diffs espurios de modo en cada `status`.
- **Invocar el script con el intérprete explícito en la CI** (`sh ./mvnw` o `bash mvnw`) — funciona y
  esquiva el bit de ejecución, pero **enmascara el problema** en vez de arreglarlo: el fichero sigue
  mal en el repositorio y falla para cualquiera que lo ejecute a mano tras clonar. Se descartó por
  la misma razón por la que no se silencia un aviso del compilador.
- **`chmod +x` como paso de la CI** — misma objeción, agravada: hay que acordarse de repetirlo en
  cada workflow que lo use.
