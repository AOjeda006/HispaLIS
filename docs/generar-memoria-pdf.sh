#!/usr/bin/env bash
#
# Genera el PDF de la memoria técnica de HispaLIS desde cero.
#
#   docs/generar-memoria-pdf.sh
#
# Lo único que hace falta tener instalado es **Docker**: los diagramas los rasteriza
# mermaid-cli y el documento lo compone pandoc con XeLaTeX, los dos en contenedor. Así
# el PDF sale igual en cualquier equipo, que es lo que se le pide a un guion de
# publicación — y no hay que instalar una distribución de TeX de tres gigas para
# regenerar un documento.
#
# Salida: docs/HispaLIS-memoria-tecnica.pdf
#
# XeLaTeX es obligatorio y no es un capricho: la Ñ, las tildes y la ç de los apellidos
# españoles son casos de prueba de este proyecto, y pdfLaTeX las trata como caracteres
# de una codificación de ocho bits. Con XeLaTeX el fichero es UTF-8 de principio a fin.

set -euo pipefail

MERMAID_IMAGEN="${MERMAID_IMAGEN:-minlag/mermaid-cli:latest}"
PANDOC_IMAGEN="${PANDOC_IMAGEN:-pandoc/extra:latest}"

aqui=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fuente="memoria-tecnica.md"
salida="HispaLIS-memoria-tecnica.pdf"
diagramas="memoria/diagramas"
encabezado="memoria/encabezado.tex"

cd "$aqui"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: hace falta Docker. No se instala nada más: las dos herramientas van en contenedor." >&2
  exit 1
fi

if [[ ! -f $fuente ]]; then
  echo "ERROR: no encuentro $fuente. Este guion se ejecuta desde el repositorio." >&2
  exit 1
fi

# El contenedor escribe en el directorio montado; sin --user dejaría ficheros de root.
usuario="$(id -u):$(id -g)"

echo "==> Rasterizando los diagramas Mermaid"
inicio=$SECONDS
for origen in "$diagramas"/*.mmd; do
  destino="${origen%.mmd}.png"
  printf '    %-44s -> %s\n' "$(basename "$origen")" "$(basename "$destino")"
  # -s 3: tres veces la resolución natural. A la anchura de la caja de texto salen
  #       unos 450 puntos por pulgada, que es más de lo que hace falta para imprimir.
  # -b white: fondo blanco explícito. Un PNG con transparencia sobre papel es una
  #           lotería según el visor.
  docker run --rm -u "$usuario" -v "$PWD":/data "$MERMAID_IMAGEN" \
    -i "/data/$origen" -o "/data/$destino" -b white -s 3 >/dev/null
done
echo "    ($((SECONDS - inicio)) s)"

echo "==> Componiendo el PDF con pandoc y XeLaTeX"
inicio=$SECONDS
docker run --rm -u "$usuario" -v "$PWD":/data "$PANDOC_IMAGEN" \
  "$fuente" \
  --output="$salida" \
  --pdf-engine=xelatex \
  --include-in-header="$encabezado" \
  --toc \
  --number-sections \
  --resource-path=.
echo "    ($((SECONDS - inicio)) s)"

echo
echo "Listo: $aqui/$salida  ($(du -h "$salida" | cut -f1))"
echo
echo "Los PNG de $diagramas/ son artefactos generados y no se versionan;"
echo "las fuentes son los .mmd que hay a su lado."
