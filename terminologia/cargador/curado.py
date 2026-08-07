"""Qué códigos hace falta cargar, leído de la guía y no de una lista escrita aquí.

**Este módulo es el invariante 4 aplicado al propio subconjunto.** «Subconjunto curado» (D14)
suena a lista escrita a mano, y una lista escrita a mano es justo lo que el proyecto prohíbe: se
desviaría de la guía en cuanto alguien añadiera una prueba al catálogo, y nadie se enteraría
hasta que un `$lookup` devolviera vacío en producción.

Lo que se carga sale de lo que la guía **ya publica**, y se busca en **todos** sus artefactos,
no solo en los de terminología: el LOINC del informe (`11502-2`) no está en el `ConceptMap` sino
fijado en el perfil `InformeLab`, y el `v2-0916` del ayuno solo aparece como *binding* de
`EspecimenLab`. Mirar únicamente los `ValueSet` dejaba fuera justo los códigos que el
laboratorio escribe en cada recurso que publica. Añadir una prueba al catálogo amplía el
subconjunto solo con volver a ejecutar SUSHI.

Lo que **se sube** sí es solo terminología: los `CodeSystem`, `ValueSet` y `ConceptMap` propios.
Un servidor de terminología no es sitio para un perfil ni para un paciente de ejemplo.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

SISTEMA_LOINC = "http://loinc.org"
SISTEMA_SNOMED = "http://snomed.info/sct"
PREFIJO_THO = "http://terminology.hl7.org/"

#: Los artefactos de conformidad que el laboratorio publica y sube tal cual: son suyos.
TIPOS_PROPIOS = ("CodeSystem", "ValueSet", "ConceptMap")


class GuiaNoCompiladaError(RuntimeError):
    """No están los artefactos que produce SUSHI a partir del FSH."""


@dataclass(frozen=True, slots=True)
class Curado:
    """El subconjunto que hay que cargar en el servidor.

    Attributes:
        loinc: Códigos LOINC referenciados por la guía.
        snomed: Códigos SNOMED CT referenciados por la guía.
        sistemas_hl7: `url` de los `CodeSystem` de HL7 Terminology que la guía usa.
        propios: Los `CodeSystem`, `ValueSet` y `ConceptMap` del propio laboratorio.
    """

    loinc: tuple[str, ...]
    snomed: tuple[str, ...]
    sistemas_hl7: tuple[str, ...]
    propios: tuple[dict, ...]


def leer_de_la_guia(recursos: Path) -> Curado:
    """Deduce el subconjunto a cargar de los recursos que genera SUSHI.

    Args:
        recursos: `ig/fsh-generated/resources`, o donde se hayan dejado.

    Returns:
        El subconjunto, con los códigos en orden estable para que la carga sea reproducible.

    Raises:
        GuiaNoCompiladaError: Si el directorio no existe o no tiene artefactos de terminología.
    """
    if not recursos.is_dir():
        raise GuiaNoCompiladaError(
            f"No existe «{recursos}». La terminología no se escribe aquí: sale de la guía. "
            f"Ejecuta «npx fsh-sushi .» dentro de «ig/»."
        )

    loinc: dict[str, None] = {}
    snomed: dict[str, None] = {}
    sistemas: dict[str, None] = {}
    propios: list[dict] = []

    for fichero in sorted(recursos.glob("*.json")):
        recurso = json.loads(fichero.read_text(encoding="utf-8"))
        if recurso.get("resourceType") in TIPOS_PROPIOS:
            propios.append(recurso)
        for sistema, codigo in _codigos_citados(recurso):
            if sistema == SISTEMA_LOINC:
                loinc[codigo] = None
            elif sistema.startswith(SISTEMA_SNOMED):
                snomed[codigo] = None
        for sistema in _sistemas_citados(recurso):
            if sistema.startswith(PREFIJO_THO):
                sistemas[sistema] = None

    if not propios:
        raise GuiaNoCompiladaError(
            f"«{recursos}» no tiene ningún CodeSystem, ValueSet ni ConceptMap. "
            f"¿Se ha ejecutado SUSHI sobre la guía correcta?"
        )

    return Curado(
        loinc=tuple(sorted(loinc)),
        snomed=tuple(sorted(snomed)),
        sistemas_hl7=tuple(sorted(sistemas)),
        propios=tuple(propios),
    )


def _codigos_citados(recurso: dict) -> Iterator[tuple[str, str]]:
    """Los pares (system, code) que un recurso nombra, esté donde esté.

    Tres formas, y las tres cuentan: cualquier `Coding` suelto —incluidos los `patternCoding` de un
    perfil y los de un ejemplo—, la enumeración de un `ValueSet` y las dos puntas de un
    `ConceptMap`. Se recorre el recurso entero en vez de mirar sitios concretos porque los sitios
    concretos se quedan cortos en cuanto alguien fija un código en un elemento nuevo.
    """
    for nodo in _recorrer(recurso):
        sistema, codigo = nodo.get("system"), nodo.get("code")
        if isinstance(sistema, str) and isinstance(codigo, str):
            yield sistema, codigo

        # `ValueSet.compose.include`: el `system` está en el nodo y los códigos, un nivel debajo.
        if isinstance(sistema, str):
            for concepto in nodo.get("concept", []) or []:
                if isinstance(concepto, dict) and isinstance(concepto.get("code"), str):
                    yield sistema, concepto["code"]

    for grupo in recurso.get("group", []):
        origen, destino = grupo.get("source"), grupo.get("target")
        for elemento in grupo.get("element", []):
            if origen:
                yield origen, elemento["code"]
            for objetivo in elemento.get("target", []):
                if destino and objetivo.get("code"):
                    yield destino, objetivo["code"]


def _sistemas_citados(recurso: dict) -> Iterator[str]:
    """Los `system` que el recurso nombra, tengan o no códigos debajo.

    Un *binding* a un `ValueSet` de HL7 nombra el sistema sin enumerar ni un código, y aun así el
    servidor lo necesita para poder contestar `$validate-code`.
    """
    for nodo in _recorrer(recurso):
        if isinstance(nodo.get("system"), str):
            yield nodo["system"]
    for grupo in recurso.get("group", []):
        for extremo in (grupo.get("source"), grupo.get("target")):
            if extremo:
                yield extremo


def _recorrer(nodo: object) -> Iterator[dict]:
    """Todos los objetos JSON de un recurso, a cualquier profundidad."""
    if isinstance(nodo, dict):
        yield nodo
        for valor in nodo.values():
            yield from _recorrer(valor)
    elif isinstance(nodo, list):
        for elemento in nodo:
            yield from _recorrer(elemento)
