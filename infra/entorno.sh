#!/usr/bin/env bash
#
# Lee `infra/compose/.env` y exporta lo que hay dentro.
#
#   . "$raiz/infra/entorno.sh"
#   cargar_entorno "$raiz/infra/compose/.env"
#
# Por qué no vale `set -a; . .env`
# --------------------------------
# Ese fichero lo leen **dos parsers distintos** y no se parecen. Docker Compose se queda con todo lo
# que va detrás del primer `=` tal cual, espacios incluidos; `source` de bash lo interpreta como
# código. Así que un valor perfectamente legítimo para el `compose` —una ruta con un espacio, que en
# Windows es lo normal— llega a bash como dos palabras y la segunda se ejecuta como una orden:
#
#   HISPALIS_RELEASES=/c/PROYECTOS Y REPOS/BibliotecaDocumentacion/_fuente/_externos
#   → «Y: command not found», y con `set -euo pipefail` el guion muere ahí, con `rc=127`, antes de
#     hacer nada y sin decir que el problema es el `.env`.
#
# No se veía porque el `.env.example` propone una ruta **relativa y sin espacios**, y el día que
# alguien usa la variable para apuntar a otro sitio —que es justo para lo que está— los tres guiones
# de `infra/` dejan de arrancar a la vez.
#
# Se lee, por tanto, como lo lee el `compose`: partir por el primer `=`, el resto es el valor. Y se
# quitan las comillas envolventes si las hay, que es también lo que hace el `compose`, para que el
# mismo fichero valga para los dos con comillas y sin ellas.

# Exporta las variables de un fichero de entorno de `docker compose`.
#
# Argumentos:
#   $1: ruta del fichero. Si no existe, no hace nada — el `.env` es opcional en los tres guiones.
cargar_entorno() {
  local fichero=$1 linea clave valor
  [[ -f $fichero ]] || return 0

  # `read` sin `-r` se comería las contrabarras de una contraseña, y sin `|| [[ -n $linea ]]` se
  # perdería la última línea si el fichero no termina en salto.
  while IFS= read -r linea || [[ -n $linea ]]; do
    [[ $linea =~ ^[[:space:]]*(#|$) ]] && continue
    [[ $linea == *=* ]] || continue

    clave=${linea%%=*}
    clave=${clave#"${clave%%[![:space:]]*}"}
    clave=${clave%"${clave##*[![:space:]]}"}
    clave=${clave#export }
    [[ $clave =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

    valor=${linea#*=}
    # Las comillas envolventes son del formato, no del valor: `compose` también las quita.
    if [[ $valor == \"*\" && ${#valor} -ge 2 ]]; then
      valor=${valor:1:${#valor}-2}
    elif [[ $valor == \'*\' && ${#valor} -ge 2 ]]; then
      valor=${valor:1:${#valor}-2}
    fi

    export "$clave=$valor"
  done <"$fichero"
}
