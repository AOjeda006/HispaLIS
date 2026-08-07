"""Subir terminología al servidor **por la API estándar de FHIR**, y por ninguna otra vía.

Es la prueba de que el servidor es intercambiable (D14). HAPI tiene su propia operación de carga
masiva —`$upload-external-code-system`, que traga la release de LOINC entera— y no se usa: es
suya, no la tiene Snowstorm ni Ontoserver, y atarse a ella convertiría «cambiar una URL» en
«reescribir el cargador». Aquí solo hay `PUT [tipo]/[id]`, que es FHIR REST del capítulo 2.

Se sube con `PUT` y no con `POST` a propósito: el identificador lo pone el cargador, así que
volver a cargar sobrevía la versión anterior en vez de acumular copias. Un arranque del
`compose` que repite la carga tiene que dejar el servidor igual, no con dos LOINC.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from collections.abc import Iterable
from dataclasses import dataclass

TIPO_DE_CONTENIDO = "application/fhir+json"


class ServidorNoDisponibleError(RuntimeError):
    """El servidor de terminología no contesta, o rechaza lo que se le sube."""


@dataclass(frozen=True, slots=True)
class Subido:
    """Lo que quedó publicado.

    Attributes:
        tipo: Tipo de recurso.
        identidad: `id` lógico con el que quedó.
        etiqueta: Qué es, para el resumen de la carga.
    """

    tipo: str
    identidad: str
    etiqueta: str


def publicar(servidor: str, recursos: Iterable[dict], espera: float = 120.0) -> list[Subido]:
    """Sube cada recurso a su sitio con `PUT`, en el orden en que llegan.

    Args:
        servidor: Base FHIR del servidor de terminología, sin barra final.
        recursos: Los recursos a publicar. Cada uno tiene que traer `resourceType` e `id`.
        espera: Segundos que se espera por respuesta. Generoso: indexar un `CodeSystem` recién
            subido puede tardar en un servidor recién arrancado.

    Returns:
        Lo publicado, en el mismo orden.

    Raises:
        ServidorNoDisponibleError: Al primer fallo. No se sigue subiendo: una terminología a medias
            es peor que ninguna, porque el sistema arrancaría validando contra un catálogo
            incompleto y rechazando códigos buenos.
    """
    base = servidor.rstrip("/")
    publicados: list[Subido] = []
    for recurso in recursos:
        tipo, identidad = recurso["resourceType"], recurso["id"]
        _put(f"{base}/{tipo}/{identidad}", recurso, espera)
        publicados.append(Subido(tipo, identidad, _etiqueta(recurso)))
    return publicados


def _put(url: str, recurso: dict, espera: float) -> None:
    peticion = urllib.request.Request(
        url,
        data=json.dumps(recurso, ensure_ascii=False).encode("utf-8"),
        method="PUT",
        headers={"Content-Type": TIPO_DE_CONTENIDO, "Accept": TIPO_DE_CONTENIDO},
    )
    try:
        with urllib.request.urlopen(peticion, timeout=espera) as respuesta:
            if respuesta.status not in (200, 201):
                raise ServidorNoDisponibleError(f"{url} contestó {respuesta.status}")
    except urllib.error.HTTPError as rechazo:
        raise ServidorNoDisponibleError(
            f"{url} contestó {rechazo.code}: {_diagnostico(rechazo.read())}"
        ) from rechazo
    except OSError as sin_respuesta:
        raise ServidorNoDisponibleError(
            f"No se puede hablar con «{url}»: {sin_respuesta}. ¿Está levantado el servicio de "
            f"terminología?"
        ) from sin_respuesta


def _diagnostico(cuerpo: bytes) -> str:
    """El `OperationOutcome` del servidor, resumido a lo que se lee en un log."""
    try:
        problema = json.loads(cuerpo.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return cuerpo[:300].decode("utf-8", errors="replace")
    if problema.get("resourceType") != "OperationOutcome":
        return str(problema)[:300]
    return " · ".join(
        incidencia.get("diagnostics", incidencia.get("code", "?"))
        for incidencia in problema.get("issue", [])
    )[:500]


def _etiqueta(recurso: dict) -> str:
    version = recurso.get("version")
    nombre = recurso.get("url", recurso["id"])
    conceptos = len(recurso.get("concept", []))
    detalle = f" · {conceptos} conceptos" if conceptos else ""
    return f"{nombre}{f' | {version}' if version else ''}{detalle}"
