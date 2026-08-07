"""El subconjunto de LOINC que usa el laboratorio, extraído de la release archivada.

**Nada de esto se versiona.** La release de LOINC vive fuera del repositorio —en la biblioteca—
y de ella se extrae, en el arranque del servicio, solo lo que la guía referencia. El
`CodeSystem` que sale se declara `content = fragment`, que es la palabra que FHIR tiene para
decir «esto es un trozo»: un `complete` con 21 conceptos sería mentir sobre un sistema de cien
mil.

La licencia de LOINC permite redistribuir a condición de que cada copia lleve el aviso de
copyright y **la versión**, y de que no se altere el contenido de los campos. Las dos cosas se
cumplen aquí: el aviso va en `CodeSystem.copyright`, la versión en `CodeSystem.version`, y los
`display` salen del `LONG_COMMON_NAME` tal cual, sin tocar (ADR-0009).
"""

from __future__ import annotations

import csv
import re
from collections.abc import Iterable
from pathlib import Path

URL = "http://loinc.org"

AVISO = (
    "Este material contiene contenido de LOINC (http://loinc.org). LOINC es copyright "
    "© 1995-2026, Regenstrief Institute, Inc. y el Logical Observation Identifiers Names and "
    "Codes (LOINC) Committee, y está disponible sin coste bajo la licencia de "
    "http://loinc.org/license. LOINC® es una marca registrada en EE. UU. de Regenstrief "
    "Institute, Inc."
)

_TABLA = Path("LoincTable") / "Loinc.csv"
_VARIANTES = Path("AccessoryFiles") / "LinguisticVariants"
_INDICE_DE_VARIANTES = _VARIANTES / "LinguisticVariants.csv"

# La release se archiva como `loinc-<versión>`: es de donde sale el número que se declara.
_VERSION_EN_EL_NOMBRE = re.compile(r"^loinc-(?P<version>\d+\.\d+)$")


class ReleaseDeLoincInvalidaError(RuntimeError):
    """La release archivada no está donde se dijo, o no se puede saber qué versión es."""


def version_de(raiz: Path) -> str:
    """La versión de la release, deducida de cómo está archivada.

    LOINC **no publica un fichero con el número de versión dentro** —lo lleva el nombre de la
    descarga—, así que se lee del directorio y se comprueba que tenga forma de versión en vez de
    aceptar cualquier cosa. Escribir «2.82» a mano en el código sería peor: un día se cambia la
    carpeta y el servidor sigue anunciando la versión vieja.

    Raises:
        ReleaseDeLoincInvalidaError: Si el directorio no existe o no se llama `loinc-<x.y>`.
    """
    if not (raiz / _TABLA).is_file():
        raise ReleaseDeLoincInvalidaError(
            f"No está «{raiz / _TABLA}». La release de LOINC no se versiona en este repositorio: "
            f"apunta HISPALIS_LOINC a la copia archivada en la biblioteca."
        )
    coincidencia = _VERSION_EN_EL_NOMBRE.match(raiz.name)
    if coincidencia is None:
        raise ReleaseDeLoincInvalidaError(
            f"«{raiz.name}» no dice qué versión de LOINC es. Se espera un directorio "
            f"«loinc-<mayor>.<menor>», que es como la archiva la biblioteca."
        )
    return coincidencia.group("version")


def codesystem_de(raiz: Path, codigos: Iterable[str]) -> dict:
    """Construye el `CodeSystem` fragmentario con los códigos que pide la guía.

    Args:
        raiz: Raíz de la release archivada (`…/loinc/loinc-2.82`).
        codigos: Los LOINC a incluir.

    Returns:
        Un `CodeSystem` R5 listo para subir por la API estándar.

    Raises:
        ReleaseDeLoincInvalidaError: Si falta la release o algún código no está en ella — que es un
            error del catálogo, no de la carga, y callarlo dejaría un `$translate` apuntando a un
            código que el servidor no conoce.
    """
    version = version_de(raiz)
    pedidos = {codigo: None for codigo in codigos}
    if not pedidos:
        return _envoltorio(version, [])

    en_espanol = _nombres_en_espanol(raiz, pedidos.keys())
    conceptos: list[dict] = []

    with (raiz / _TABLA).open(encoding="utf-8-sig", newline="") as tabla:
        for fila in csv.DictReader(tabla):
            codigo = fila["LOINC_NUM"]
            if codigo not in pedidos:
                continue
            conceptos.append(_concepto(fila, en_espanol.get(codigo)))

    faltan = pedidos.keys() - {concepto["code"] for concepto in conceptos}
    if faltan:
        raise ReleaseDeLoincInvalidaError(
            f"LOINC {version} no define {sorted(faltan)}. Lo referencia la guía, así que el error "
            f"está en el ConceptMap del catálogo, no aquí."
        )

    conceptos.sort(key=lambda concepto: concepto["code"])
    return _envoltorio(version, conceptos)


def _concepto(fila: dict[str, str], nombre_en_espanol: str | None) -> dict:
    concepto: dict = {
        "code": fila["LOINC_NUM"],
        # `LONG_COMMON_NAME` sin tocar: la licencia prohíbe alterar el contenido de los campos, y
        # ADR-0009 registra qué pasa cuando alguien lo «arregla».
        "display": fila["LONG_COMMON_NAME"],
    }
    if fila.get("SHORTNAME"):
        concepto.setdefault("designation", []).append(
            {"language": "en", "value": fila["SHORTNAME"]}
        )
    if nombre_en_espanol:
        concepto.setdefault("designation", []).append(
            {"language": "es", "value": nombre_en_espanol}
        )
    return concepto


def _nombres_en_espanol(raiz: Path, codigos: Iterable[str]) -> dict[str, str]:
    """Los nombres en español que publica **LOINC**, si publica alguno.

    La variante lingüística no se busca por nombre de fichero: se localiza por el índice
    `LinguisticVariants.csv`, que es quien dice qué identificador le corresponde al español de
    España. Así el día que cambie de número, esto sigue encontrándola.

    LOINC 2.82 traduce los ejes del término (`Glucosa`, `Suero o Plasma`) pero deja **vacío** el
    nombre largo de casi todo. No se compone uno a partir de los ejes: eso sería fabricar un nombre
    LOINC que Regenstrief no publica. El nombre en español que ve el usuario sale del catálogo
    local, que sí es nuestro.
    """
    fichero = _variante_espanola(raiz)
    if fichero is None:
        return {}

    pedidos = set(codigos)
    nombres: dict[str, str] = {}
    with fichero.open(encoding="utf-8-sig", newline="") as variante:
        for fila in csv.DictReader(variante):
            if fila["LOINC_NUM"] not in pedidos:
                continue
            nombre = fila.get("LinguisticVariantDisplayName") or fila.get("SHORTNAME")
            if nombre:
                nombres[fila["LOINC_NUM"]] = nombre
    return nombres


def _variante_espanola(raiz: Path) -> Path | None:
    indice = raiz / _INDICE_DE_VARIANTES
    if not indice.is_file():
        return None
    with indice.open(encoding="utf-8-sig", newline="") as fichero:
        for fila in csv.DictReader(fichero):
            if (fila["ISO_LANGUAGE"], fila["ISO_COUNTRY"]) == ("es", "ES"):
                candidato = raiz / _VARIANTES / f"esES{fila['ID']}LinguisticVariant.csv"
                return candidato if candidato.is_file() else None
    return None


def _envoltorio(version: str, conceptos: list[dict]) -> dict:
    return {
        "resourceType": "CodeSystem",
        "id": "loinc",
        "url": URL,
        "version": version,
        "name": "LOINC",
        "title": f"LOINC {version} — subconjunto usado por HispaLIS",
        "status": "active",
        "experimental": False,
        "publisher": "Regenstrief Institute, Inc.",
        "copyright": AVISO,
        "caseSensitive": True,
        # La palabra que evita la mentira: esto es un trozo de LOINC, no LOINC.
        "content": "fragment",
        "count": len(conceptos),
        "concept": conceptos,
    }
