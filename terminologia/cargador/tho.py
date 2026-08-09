"""Los `CodeSystem` de HL7 Terminology que la guía usa, sacados del paquete oficial.

A diferencia de LOINC y SNOMED, aquí **no hay que fragmentar nada**: el paquete THO trae cada
`CodeSystem` completo y pequeño, así que se suben enteros y tal cual vienen. Lo que sí se hace
es subir **solo los que la guía nombra**: el paquete R5 trae 928, y cargar los 928 en un
servidor de un laboratorio es ruido que alguien confundirá algún día con terminología en uso.

El paquete se lee de la copia archivada en la biblioteca, fuera del repositorio, por la misma
razón que las otras dos: la terminología no se copia dentro del código.
"""

from __future__ import annotations

import io
import json
import tarfile
from collections.abc import Iterable
from pathlib import Path


class PaqueteDeThoInvalidoError(RuntimeError):
    """El paquete no está donde se dijo, o no es el que se cree."""


def esta_archivado(paquete: Path) -> bool:
    """Dice si el paquete está donde se dijo, sin abrirlo.

    La misma distinción que en LOINC: ausente se avisa, presente y roto se falla.
    """
    return paquete.is_file()


def version_de(paquete: Path) -> str:
    """La versión que el propio paquete declara en su `package.json`.

    Raises:
        PaqueteDeThoInvalidoError: Si el fichero no existe o no lleva `package.json`.
    """
    return _manifiesto(paquete)["version"]


def codesystems_de(paquete: Path, urls: Iterable[str]) -> list[dict]:
    """Extrae del paquete los `CodeSystem` cuyas `url` se piden.

    Args:
        paquete: El `.tgz` del paquete FHIR de HL7 Terminology para R5.
        urls: Las `url` canónicas de los sistemas que la guía referencia.

    Returns:
        Los recursos encontrados, ordenados por `url` para que la carga sea reproducible.

    Raises:
        PaqueteDeThoInvalidoError: Si alguna `url` pedida no está en el paquete. No se ignora en
            silencio: un `system` de HL7 que el paquete no define suele ser una URI mal escrita en
            un alias, y el síntoma tardío es un `$validate-code` que dice que no a todo.
    """
    pedidas = {url for url in urls}
    if not pedidas:
        return []

    encontrados: dict[str, dict] = {}
    with tarfile.open(paquete, "r:gz") as archivo:
        for miembro in archivo.getmembers():
            if not miembro.name.startswith("package/CodeSystem-") or not miembro.name.endswith(
                ".json"
            ):
                continue
            extraido = archivo.extractfile(miembro)
            if extraido is None:
                continue
            recurso = json.load(io.TextIOWrapper(extraido, encoding="utf-8"))
            if recurso.get("url") in pedidas:
                encontrados[recurso["url"]] = recurso

    faltan = pedidas - encontrados.keys()
    if faltan:
        raise PaqueteDeThoInvalidoError(
            f"HL7 Terminology {version_de(paquete)} no define {sorted(faltan)}. La guía los "
            f"referencia: probablemente sea una URI mal escrita en «ig/input/fsh/aliases.fsh»."
        )
    return [encontrados[url] for url in sorted(encontrados)]


def _manifiesto(paquete: Path) -> dict:
    if not paquete.is_file():
        raise PaqueteDeThoInvalidoError(
            f"No está «{paquete}». El paquete de HL7 Terminology no se versiona en este "
            f"repositorio: apunta HISPALIS_THO a la copia archivada en la biblioteca."
        )
    with tarfile.open(paquete, "r:gz") as archivo:
        try:
            extraido = archivo.extractfile("package/package.json")
        except KeyError as sin_manifiesto:
            raise PaqueteDeThoInvalidoError(
                f"«{paquete.name}» no lleva «package/package.json»: no es un paquete FHIR."
            ) from sin_manifiesto
        if extraido is None:
            raise PaqueteDeThoInvalidoError(f"«{paquete.name}» tiene el manifiesto vacío.")
        return json.load(io.TextIOWrapper(extraido, encoding="utf-8"))
