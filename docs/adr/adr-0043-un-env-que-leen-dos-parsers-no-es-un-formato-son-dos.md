---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-16
tags: [adr, configuracion, docker-compose, shell, portabilidad, rutas-con-espacios]
---

# ADR-0043: Un `.env` que leen dos parsers no es un formato, son dos

- **Estado:** aceptado
- **Fecha:** 2026-08-16

## Contexto

`infra/compose/.env` lo lee `docker compose`, y lo leen además tres guiones de `infra/` que necesitan
las mismas credenciales. Los guiones lo cargaban de la forma corta:

```bash
set -a
. "$entorno"
set +a
```

Desde un clon recién hecho, con el repositorio en una carpeta cuyo nombre lleva un espacio, los tres
mueren **antes de hacer nada**:

```
infra/compose/.env: line 61: Y: command not found
```

`HISPALIS_RELEASES=/c/PROYECTOS Y REPOS/BibliotecaDocumentacion/_fuente/_externos` es un valor
perfectamente legítimo —`compose` se queda con **todo lo que hay detrás del primer `=`**, espacios
incluidos—, pero `source` no lee un fichero de configuración: **ejecuta un guion**. Ahí dentro,
`REPOS/...` es una orden. Y como los tres llevan `set -euo pipefail`, el `rc=127` de esa orden mata el
proceso en la línea 61 de un fichero que ni siquiera es suyo, sin una palabra sobre el `.env`.

**Por qué no se había visto nunca.** El `.env.example` propone una ruta **relativa y sin espacios**, y
mientras nadie mueva la variable, `source` y `compose` coinciden por casualidad. La variable existe
justo para apuntar a otro sitio; el día que se usa para lo que está, los tres guiones dejan de
arrancar a la vez. En Windows, además, «a otro sitio» lleva un espacio la mitad de las veces.

## Decisión

**Un fichero de configuración de otra herramienta se parsea, no se ejecuta.** La carga vive en
`infra/entorno.sh`, en una función `cargar_entorno`, y hace lo que hace `compose`: partir por el
**primer** `=`, tomar el resto como valor literal, y quitar las comillas envolventes si las hay —que
también es lo que hace `compose`, de modo que el mismo fichero vale para los dos con comillas y sin
ellas—.

Va en **un** fichero compartido y no copiado en los tres. Una regla en tres sitios es la trampa de
`adr-0041` servida: se arregla en dos y el tercero se queda con la versión mala, que además es la que
corre.

## Consecuencias

- Los tres guiones arrancan con cualquier valor que `compose` acepte, con espacios y sin comillas.
- `infra/` gana un fichero que **no cubre ninguna puerta de CI**: los `paths:` de los siete workflows
  no incluyen `infra/*.sh`. Queda comprobado a mano ejecutando los tres contra el `.env` que los
  mataba, y anotado como lo que es — una comprobación que nadie repetirá sola.
- La función es deliberadamente tonta: no interpreta `${VAR}` ni comillas internas ni continuaciones.
  Interpretar de más es volver al problema por el otro lado.

## Alternativas consideradas

- **Entrecomillar el valor en el `.env` y documentarlo.** Funciona —`compose` quita las comillas— y no
  protege de nada: el siguiente que escriba la ruta a mano no leerá la nota.
- **Dejar de compartir el fichero**, con un `.env` para el `compose` y otro para los guiones. Dos
  sitios donde escribir la misma contraseña.
- **`export $(grep -v '^#' .env | xargs)`**, que es el remedio que más circula. Es peor que el
  original: `xargs` parte por espacios, así que la ruta con espacio se convierte en dos variables.

## Lo reutilizable

1. **Un fichero de configuración que leen dos herramientas tiene dos gramáticas, y hay que escribir
   contra la más estricta.** `docker compose`, `dotenv`, `systemd` y `source` de bash **no** coinciden
   en comillas, espacios ni escapes. El que coincidan en los valores fáciles es lo que hace que el
   fallo llegue tarde.
2. **`set -a; . fichero` no es «leer variables»: es ejecutar código que alguien escribió para otra
   herramienta.** Un valor con un espacio, un `$`, un backtick o un `#` cambia lo que hace.
3. **Un valor de ejemplo sin espacios esconde toda esta clase de fallo.** Si una variable admite una
   ruta, el ejemplo debería llevar un espacio a propósito: es el caso que rompe y el que nadie prueba.
4. **Cuando el mismo bloque frágil está copiado en N guiones, el arreglo es un fichero compartido**, no
   N parches. Si no, la copia que se olvida es la que corre.
