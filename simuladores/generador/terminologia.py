"""El catálogo de pruebas del laboratorio, leído de la guía de implementación.

**Esto es el invariante D15 hecho código.** El generador consume el *mismo* `CodeSystem` y el
*mismo* `ConceptMap` que publica la IG y que usa el backend, no una lista paralela de códigos
escrita aquí. La diferencia no es de estilo: con una lista propia, el generador produce datos que
solo valen para sí mismo, el `ConceptMap` deja de estar probado por nadie, y el día que se añada una
prueba al catálogo los datos sintéticos siguen tan campantes con el catálogo viejo — que es la forma
más silenciosa de que un juego de pruebas deje de probar lo que dice probar.

Los artefactos se leen de `ig/fsh-generated/`, que **no está versionado**: lo produce SUSHI a
partir del FSH. Si falta, esto falla de inmediato y diciendo qué hay que ejecutar, en vez de
arrancar con un catálogo a medias.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

#: Permite apuntar a otro directorio de recursos (la CI lo usa tras ejecutar SUSHI).
VARIABLE_ENTORNO = "HISPALIS_TERMINOLOGIA"

NOMBRE_CODESYSTEM = "CodeSystem-catalogo-pruebas.json"
NOMBRE_CONCEPTMAP = "ConceptMap-catalogo-a-loinc.json"
NOMBRE_TIPOS_DE_MUESTRA = "ValueSet-tipos-muestra.json"

SYSTEM_UCUM = "http://unitsofmeasure.org"
SYSTEM_LOINC = "http://loinc.org"

#: Propiedad del `CodeSystem` que declara en qué unidad emite el laboratorio cada prueba.
PROPIEDAD_UNIDAD = "unidad-ucum"

#: Cómo se escribe una unidad en un informe español cuando no coincide con su código UCUM.
#:
#: No es una lista paralela de terminología —la que prohíbe D15—: los códigos siguen viniendo todos
#: del `CodeSystem`, y esto solo dice cómo se *imprime* uno de ellos. Se queda corta a propósito y
#: cae con elegancia: una unidad que no esté aquí se imprime con su propio código UCUM, así que
#: añadir una prueba al catálogo nunca rompe nada.
UNIDADES_IMPRESAS = {
    "u[IU]/mL": "µUI/mL",
    "10*3/uL": "10³/µL",
}


class TerminologiaNoDisponibleError(RuntimeError):
    """No se encuentran los artefactos de terminología que publica la guía."""


@dataclass(frozen=True, slots=True)
class Prueba:
    """Una prueba del catálogo local, con su unidad y su equivalente LOINC.

    Attributes:
        codigo: Código del catálogo local (`GLU`, `TSH`…).
        display: Nombre de la prueba, tal y como lo publica la guía.
        unidad_ucum: Código UCUM de la unidad, o `None` si la prueba es cualitativa.
        loinc: Código LOINC equivalente según el `ConceptMap`, si lo tiene.
        loinc_display: Nombre oficial LOINC, que va en inglés porque LOINC lo publica así.
    """

    codigo: str
    display: str
    unidad_ucum: str | None
    loinc: str | None
    loinc_display: str | None

    @property
    def es_cuantitativa(self) -> bool:
        """Indica si la prueba se informa con una cifra y una unidad.

        Lo decide la unidad declarada en el catálogo y no una lista de códigos: una prueba nueva
        queda clasificada sola, sin tocar este módulo.
        """
        return self.unidad_ucum is not None

    @property
    def unidad_impresa(self) -> str | None:
        """La unidad tal y como se escribe en el informe."""
        if self.unidad_ucum is None:
            return None
        return UNIDADES_IMPRESAS.get(self.unidad_ucum, self.unidad_ucum)


@dataclass(frozen=True, slots=True)
class Catalogo:
    """El catálogo de pruebas completo, en el orden en que lo publica la guía.

    El orden importa: es una tupla y no un conjunto porque el generador elige pruebas al azar sobre
    ella, y un recorrido con orden indefinido haría irreproducible la salida aun con la misma
    semilla.
    """

    system: str
    pruebas: tuple[Prueba, ...]

    def __getitem__(self, codigo: str) -> Prueba:
        for prueba in self.pruebas:
            if prueba.codigo == codigo:
                return prueba
        raise KeyError(f"«{codigo}» no está en el catálogo de pruebas del laboratorio.")

    def __contains__(self, codigo: object) -> bool:
        return any(prueba.codigo == codigo for prueba in self.pruebas)

    def __len__(self) -> int:
        return len(self.pruebas)

    @property
    def codigos(self) -> tuple[str, ...]:
        """Los códigos de todas las pruebas, en el orden del catálogo."""
        return tuple(prueba.codigo for prueba in self.pruebas)

    @property
    def cuantitativas(self) -> tuple[Prueba, ...]:
        """Las pruebas que se informan con cifra y unidad."""
        return tuple(prueba for prueba in self.pruebas if prueba.es_cuantitativa)

    @property
    def cualitativas(self) -> tuple[Prueba, ...]:
        """Las pruebas que se informan con un concepto codificado, no con una cifra."""
        return tuple(prueba for prueba in self.pruebas if not prueba.es_cuantitativa)


def cargar_catalogo(directorio: Path | str | None = None) -> Catalogo:
    """Lee el catálogo de pruebas de los artefactos que genera SUSHI.

    Args:
        directorio: Dónde están los recursos de la guía. Por defecto, `ig/fsh-generated/resources`
            del propio repositorio, o lo que indique la variable de entorno
            `HISPALIS_TERMINOLOGIA`.

    Returns:
        El catálogo completo, con la traducción a LOINC ya resuelta.

    Raises:
        TerminologiaNoDisponibleError: Si falta alguno de los dos artefactos.
    """
    recursos = Path(directorio) if directorio is not None else _directorio_por_defecto()

    codesystem = _leer(recursos / NOMBRE_CODESYSTEM)
    conceptmap = _leer(recursos / NOMBRE_CONCEPTMAP)
    equivalencias = _equivalencias_loinc(conceptmap)

    pruebas = tuple(
        Prueba(
            codigo=concepto["code"],
            display=concepto["display"],
            unidad_ucum=_unidad_de(concepto),
            loinc=equivalencias.get(concepto["code"], (None, None))[0],
            loinc_display=equivalencias.get(concepto["code"], (None, None))[1],
        )
        for concepto in codesystem["concept"]
    )
    return Catalogo(system=codesystem["url"], pruebas=pruebas)


def cargar_tipos_de_muestra(directorio: Path | str | None = None) -> frozenset[str]:
    """Lee del `ValueSet` de la guía los tipos de muestra que el laboratorio acepta.

    Existe para que un test pueda comprobar que los tipos que usa el generador salen de la guía y no
    de la cabeza de nadie. Es la misma razón que el catálogo: lo que no se cruza contra la fuente,
    se desvía.

    Returns:
        Los códigos SNOMED admitidos.

    Raises:
        TerminologiaNoDisponibleError: Si falta el artefacto.
    """
    recursos = Path(directorio) if directorio is not None else _directorio_por_defecto()
    valueset = _leer(recursos / NOMBRE_TIPOS_DE_MUESTRA)

    return frozenset(
        concepto["code"]
        for inclusion in valueset["compose"]["include"]
        for concepto in inclusion.get("concept", [])
    )


def _directorio_por_defecto() -> Path:
    indicado = os.environ.get(VARIABLE_ENTORNO)
    if indicado:
        return Path(indicado)
    return _raiz_del_repositorio() / "ig" / "fsh-generated" / "resources"


def _raiz_del_repositorio() -> Path:
    # `generador/terminologia.py` → `generador/` → `simuladores/` → la raíz.
    return Path(__file__).resolve().parents[2]


def _leer(ruta: Path) -> dict:
    try:
        return json.loads(ruta.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise TerminologiaNoDisponibleError(
            f"No se encuentra «{ruta}». La terminología no se copia aquí: se lee de lo que "
            f"produce la guía. Ejecuta «npx fsh-sushi .» dentro de «ig/», o apunta a otro "
            f"directorio con la variable de entorno {VARIABLE_ENTORNO}."
        ) from error


def _unidad_de(concepto: dict) -> str | None:
    for propiedad in concepto.get("property", []):
        if propiedad.get("code") == PROPIEDAD_UNIDAD:
            return propiedad["valueCoding"]["code"]
    return None


def _equivalencias_loinc(conceptmap: dict) -> dict[str, tuple[str, str]]:
    equivalencias: dict[str, tuple[str, str]] = {}
    for grupo in conceptmap.get("group", []):
        for elemento in grupo.get("element", []):
            destinos = elemento.get("target", [])
            if destinos:
                equivalencias[elemento["code"]] = (destinos[0]["code"], destinos[0]["display"])
    return equivalencias
