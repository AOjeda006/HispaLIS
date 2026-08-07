"""El subconjunto de SNOMED CT Edición Española, extraído de la release RF2.

**La release NO se versiona ni se redistribuye.** La Edición Española es gratuita en España
previo registro ante la Unidad de Terminología del SNS, pero su licencia **no permite
redistribuirla**: el material vive fuera del repositorio y se lee en el arranque del servicio,
apuntando con `HISPALIS_SNOMED`. Sin esa variable el cargador no carga SNOMED y lo dice; no
falla ni inventa nada.

## La versión se lee de la release, no se escribe aquí

Un `display` de SNOMED depende de la edición y de la fecha: sin declarar cuál, los términos
dejan de ser reproducibles. La versión se compone en la forma canónica que exige la
especificación de URI de SNOMED —`http://snomed.info/sct/{módulo}/version/{AAAAMMDD}`— y sus dos
mitades **salen de la propia release**: el módulo y la fecha, del *refset* de dependencias de
módulo, que es donde la edición declara qué es. Escribirlas a mano garantizaría que un día digan
una cosa y el contenido sea otro.
"""

from __future__ import annotations

import csv
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path

URL = "http://snomed.info/sct"

AVISO = (
    "Este material contiene un subconjunto de SNOMED CT®, Edición Española, distribuida en España "
    "por el Ministerio de Sanidad. SNOMED CT® es una marca registrada de SNOMED International. "
    "Su uso requiere licencia; este material no se redistribuye con el código fuente."
)

#: Tipo de descripción: nombre completamente especificado.
_FSN = "900000000000003001"
#: Tipo de descripción: sinónimo.
_SINONIMO = "900000000000013009"
#: Aceptabilidad: término preferente dentro de un *refset* de idioma.
_PREFERENTE = "900000000000548007"
#: Módulo del componente de modelo. Toda edición declara depender de él, y esa fila es la que dice
#: cuál es el módulo de la edición y de qué fecha es.
_MODULO_DE_MODELO = "900000000000012004"


class ReleaseDeSnomedInvalidaError(RuntimeError):
    """La release RF2 no está donde se dijo, o no declara de qué edición y fecha es."""


@dataclass(frozen=True, slots=True)
class Edicion:
    """La identidad exacta de la release cargada.

    Attributes:
        modulo: Identificador del módulo de la edición.
        fecha: Fecha de la release, en `AAAAMMDD`.
    """

    modulo: str
    fecha: str

    @property
    def version(self) -> str:
        """La versión en la forma canónica de la especificación de URI de SNOMED CT."""
        return f"{URL}/{self.modulo}/version/{self.fecha}"


def edicion_de(raiz: Path) -> Edicion:
    """Lee de la release qué edición es y de qué fecha.

    Raises:
        ReleaseDeSnomedInvalidaError: Si no hay *refset* de dependencias, o no declara dependencia
            del módulo de modelo — sin eso no se puede afirmar qué edición se ha cargado, y una
            edición sin declarar es peor que no cargar nada.
    """
    dependencias = _fichero(raiz, "der2_ssRefset_ModuleDependency*Snapshot*.txt")
    for fila in _filas(dependencias):
        if fila["active"] == "1" and fila["referencedComponentId"] == _MODULO_DE_MODELO:
            return Edicion(modulo=fila["moduleId"], fecha=fila["sourceEffectiveTime"])
    raise ReleaseDeSnomedInvalidaError(
        f"«{dependencias.name}» no declara dependencia del módulo de modelo, así que no dice qué "
        f"edición es. Sin versión declarada no se carga: los `display` dejarían de ser fiables."
    )


def codesystem_de(raiz: Path, codigos: Iterable[str]) -> dict:
    """Construye el `CodeSystem` fragmentario con los conceptos que pide la guía.

    El `display` es el **término preferente en español** y el nombre completamente especificado va
    como `designation`: es el orden correcto para un informe español (D7), donde el FSN
    —«(especimen)» incluido— no se le enseña a nadie.

    Args:
        raiz: Raíz de la release RF2 (la que contiene `Snapshot/`).
        codigos: Los identificadores de concepto a incluir.

    Returns:
        Un `CodeSystem` R5 listo para subir por la API estándar.

    Raises:
        ReleaseDeSnomedInvalidaError: Si falta algún fichero de la release, o algún concepto pedido
            no está activo en ella.
    """
    edicion = edicion_de(raiz)
    pedidos = {codigo for codigo in codigos}
    if not pedidos:
        return _envoltorio(edicion, [])

    activos = _conceptos_activos(raiz, pedidos)
    faltan = pedidos - activos
    if faltan:
        raise ReleaseDeSnomedInvalidaError(
            f"La edición {edicion.version} no tiene activos {sorted(faltan)}. Los referencia la "
            f"guía: o el código está retirado o la release no es la que se cree."
        )

    terminos = _terminos(raiz, pedidos, _descripciones_preferentes(raiz))
    conceptos = [
        _concepto(codigo, *terminos.get(codigo, (None, None))) for codigo in sorted(pedidos)
    ]
    return _envoltorio(edicion, conceptos)


def _concepto(codigo: str, display: str | None, fsn: str | None) -> dict:
    concepto: dict = {"code": codigo, "display": display or fsn or codigo}
    if fsn and fsn != concepto["display"]:
        concepto["designation"] = [
            {
                "language": "es",
                "use": {
                    "system": URL,
                    "code": _FSN,
                    "display": "Nombre completamente especificado",
                },
                "value": fsn,
            }
        ]
    return concepto


def _conceptos_activos(raiz: Path, pedidos: set[str]) -> set[str]:
    fichero = _fichero(raiz, "sct2_Concept_Snapshot*.txt")
    return {
        fila["id"] for fila in _filas(fichero) if fila["id"] in pedidos and fila["active"] == "1"
    }


def _descripciones_preferentes(raiz: Path) -> set[str]:
    """Los identificadores de descripción marcados como preferentes en algún *refset* de idioma.

    No se filtra por un *refset* concreto: cuál usa la Edición Española es cosa suya y puede haber
    más de uno (nacional y de extensión). Lo que este cargador necesita saber es si el término es el
    preferente, y eso lo dice la aceptabilidad.
    """
    fichero = _fichero(raiz, "der2_cRefset_Language*Snapshot*.txt")
    return {
        fila["referencedComponentId"]
        for fila in _filas(fichero)
        if fila["active"] == "1" and fila["acceptabilityId"] == _PREFERENTE
    }


def _terminos(
    raiz: Path, pedidos: set[str], preferentes: set[str]
) -> dict[str, tuple[str | None, str | None]]:
    """El término preferente en español y el FSN de cada concepto pedido.

    De una sola pasada sobre el fichero de descripciones: es el más grande de la release —millones
    de filas en una edición completa— y recorrerlo una vez por concepto convertiría una carga de
    segundos en una de horas.
    """
    encontrados: dict[str, tuple[str | None, str | None]] = {}
    for fila in _filas(_fichero(raiz, "sct2_Description_Snapshot*.txt")):
        concepto = fila["conceptId"]
        if concepto not in pedidos or fila["active"] != "1" or fila["languageCode"] != "es":
            continue
        display, fsn = encontrados.get(concepto, (None, None))
        if fila["typeId"] == _FSN:
            fsn = fila["term"]
        elif fila["typeId"] == _SINONIMO and fila["id"] in preferentes:
            display = fila["term"]
        encontrados[concepto] = (display, fsn)
    return encontrados


def _fichero(raiz: Path, patron: str) -> Path:
    encontrados = sorted(raiz.rglob(patron))
    if not encontrados:
        raise ReleaseDeSnomedInvalidaError(
            f"No hay ningún «{patron}» bajo «{raiz}». Se espera una release RF2 con su carpeta "
            f"«Snapshot»; apunta HISPALIS_SNOMED a la Edición Española descargada del SNS."
        )
    return encontrados[-1]


def _filas(fichero: Path) -> Iterator[dict[str, str]]:
    """Las filas de un fichero RF2: TSV en UTF-8, con cabecera."""
    with fichero.open(encoding="utf-8", newline="") as abierto:
        yield from csv.DictReader(abierto, delimiter="\t")


def _envoltorio(edicion: Edicion, conceptos: list[dict]) -> dict:
    return {
        "resourceType": "CodeSystem",
        "id": "snomed-ct-es",
        "url": URL,
        "version": edicion.version,
        "name": "SNOMED_CT_Edicion_Espanola",
        "title": f"SNOMED CT Edición Española ({edicion.fecha}) — subconjunto usado por HispaLIS",
        "status": "active",
        "experimental": False,
        "publisher": "SNOMED International / Ministerio de Sanidad",
        "copyright": AVISO,
        "caseSensitive": False,
        "content": "fragment",
        "count": len(conceptos),
        "concept": conceptos,
    }
