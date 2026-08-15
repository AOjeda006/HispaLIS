"""El subconjunto de SNOMED CT Edición Española, extraído de la release RF2.

**La release NO se versiona ni se redistribuye.** La Edición Española es gratuita en España
previo registro ante la Unidad de Terminología del SNS, pero su licencia **no permite
redistribuirla**: el material vive fuera del repositorio y se lee en el arranque del servicio,
apuntando con `HISPALIS_SNOMED`. Sin esa variable el cargador no carga SNOMED y lo dice; no
falla ni inventa nada.

## Una edición española no se descarga: se compone de tres paquetes

`HISPALIS_SNOMED` apunta a una raíz con **tres** *releases* RF2 descomprimidas, no a una: la
**Edición Internacional** (los conceptos y sus términos en inglés), la ***Spanish Edition***
(**solo descripciones** en español de esos conceptos) y la **extensión del SNS** (sus propios
conceptos, y descripciones en español complementarias). Van a cadencias distintas —mensual,
trimestral y semestral—, así que **no valen las últimas de cada una**: el Área de Descarga publica
las versiones ancladas de las que depende la extensión vigente, que son las entradas
*Dependencia EE SNS*.

De ahí que aquí se lea **todo** fichero que case con cada patrón y no uno solo. El `display` en
español de un concepto internacional sale de cruzar la tabla de conceptos de un paquete con la de
descripciones de otro y con el *refset* de idioma de un tercero.

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

    Cada paquete declara la suya, así que hay tantas candidatas como paquetes. La que nombra al
    conjunto es la del **que depende de los demás**, y esa es la de fecha más reciente: la extensión
    del SNS se alinea con las ediciones internacionales que ya están publicadas, nunca al revés.

    Raises:
        ReleaseDeSnomedInvalidaError: Si no hay *refset* de dependencias, si ninguno declara
            dependencia del módulo de modelo, o si dos módulos distintos declaran la **misma
            fecha** — entonces no se sabe cuál depende de cuál, y una edición mal declarada es peor
            que no cargar nada.
    """
    candidatas = {
        Edicion(modulo=fila["moduleId"], fecha=fila["sourceEffectiveTime"])
        for dependencias in _ficheros(raiz, "der2_ssRefset_ModuleDependency*Snapshot*.txt")
        for fila in _filas(dependencias)
        if fila["active"] == "1" and fila["referencedComponentId"] == _MODULO_DE_MODELO
    }
    if not candidatas:
        raise ReleaseDeSnomedInvalidaError(
            "Ningún «der2_ssRefset_ModuleDependency…Snapshot…» declara dependencia del módulo de "
            "modelo, así que la release no dice qué edición es. Sin versión declarada no se carga: "
            "los `display` dejarían de ser fiables."
        )

    ultima = max(candidatas, key=lambda edicion: edicion.fecha)
    empatan = sorted(edicion.modulo for edicion in candidatas if edicion.fecha == ultima.fecha)
    if len(empatan) > 1:
        raise ReleaseDeSnomedInvalidaError(
            f"Los módulos {empatan} declaran la misma fecha ({ultima.fecha}), así que no se puede "
            f"saber cuál depende de cuál ni cuál nombra al conjunto. Descarga los paquetes que se "
            f"enlazan entre sí: en el Área de Descarga son las entradas «Dependencia EE SNS»."
        )
    return ultima


def codesystem_de(raiz: Path, codigos: Iterable[str]) -> dict:
    """Construye el `CodeSystem` fragmentario con los conceptos que pide la guía.

    El `display` es el **término preferente en español** y el nombre completamente especificado va
    como `designation`: es el orden correcto para un informe español (D7), donde el FSN
    —«(especimen)» incluido— no se le enseña a nadie.

    Args:
        raiz: Raíz bajo la que están descomprimidos los paquetes RF2, cada uno con su `Snapshot/`.
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
    """Los conceptos pedidos que estén activos en cualquiera de los paquetes.

    Los tipos de muestra son de la Edición Internacional y los tipos de identificador, de la
    extensión del SNS: leyendo un solo fichero, la mitad de la guía «no existe».
    """
    return {
        fila["id"]
        for fichero in _ficheros(raiz, "sct2_Concept_Snapshot*.txt")
        for fila in _filas(fichero)
        if fila["id"] in pedidos and fila["active"] == "1"
    }


def _descripciones_preferentes(raiz: Path) -> set[str]:
    """Los identificadores de descripción marcados como preferentes en algún *refset* de idioma.

    No se filtra por un *refset* concreto: cuál usa la Edición Española es cosa suya y hay más de
    uno —el de la *Spanish Edition* y el de la extensión—. Lo que este cargador necesita saber es si
    el término es el preferente, y eso lo dice la aceptabilidad.
    """
    return {
        fila["referencedComponentId"]
        for fichero in _ficheros(raiz, "der2_cRefset_Language*Snapshot*.txt")
        for fila in _filas(fichero)
        if fila["active"] == "1" and fila["acceptabilityId"] == _PREFERENTE
    }


def _terminos(
    raiz: Path, pedidos: set[str], preferentes: set[str]
) -> dict[str, tuple[str | None, str | None]]:
    """El término preferente en español y el FSN de cada concepto pedido.

    De una sola pasada por fichero de descripciones: es el más grande de la release —millones de
    filas en una edición completa— y recorrerlo una vez por concepto convertiría una carga de
    segundos en una de horas.

    Los ficheros son varios y se recorren todos, porque el término español de un concepto
    internacional **no está en el paquete donde está el concepto**: la *Spanish Edition* es
    exactamente eso, las descripciones en español de la edición que va aparte.
    """
    encontrados: dict[str, tuple[str | None, str | None]] = {}
    for fichero in _ficheros(raiz, "sct2_Description_Snapshot*.txt"):
        for fila in _filas(fichero):
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


def _ficheros(raiz: Path, patron: str) -> list[Path]:
    """**Todos** los ficheros que casan con el patrón, que en una edición compuesta son varios.

    Quedarse con uno solo era el fallo: el que sobreviviera dependía del orden alfabético de unas
    carpetas que elige quien descomprime, y el resto del contenido desaparecía sin un aviso. La
    forma más cara de notarlo es la callada — un `CodeSystem` publicado con el número del código
    puesto de nombre, porque el término estaba en el fichero que no se leyó.
    """
    encontrados = sorted(raiz.rglob(patron))
    if not encontrados:
        raise ReleaseDeSnomedInvalidaError(
            f"No hay ningún «{patron}» bajo «{raiz}». Se esperan las releases RF2 con su carpeta "
            f"«Snapshot»; apunta HISPALIS_SNOMED a la Edición Española descargada del SNS."
        )
    return encontrados


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
