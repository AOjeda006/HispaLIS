"""Carga el servidor de terminología y dice exactamente qué ha cargado y con qué versión.

python -m cargador --servidor http://localhost:8090/fhir

En el `compose` es un servicio de arranque: corre, carga y termina. Quien depende de la
terminología espera a que **termine bien**, no a que el servidor conteste — un servidor
levantado y vacío responde `$validate-code` con «no» a todo, que es la peor forma de estar
disponible.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from cargador import loinc, snomed, tho
from cargador.curado import Curado, GuiaNoCompiladaError, leer_de_la_guia
from cargador.publicacion import ServidorNoDisponibleError, publicar

LOG = logging.getLogger("cargador")

#: Dónde está cada cosa. Las tres releases viven FUERA del repositorio, en la biblioteca.
VARIABLES = {
    "servidor": "HISPALIS_TERMINOLOGIA",
    "guia": "HISPALIS_GUIA",
    "loinc": "HISPALIS_LOINC",
    "tho": "HISPALIS_THO",
    "snomed": "HISPALIS_SNOMED",
}


def main(argumentos: list[str] | None = None) -> int:
    """Punto de entrada. Devuelve el código de salida del proceso."""
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s : %(message)s")
    opciones = _opciones(argumentos)

    try:
        _esperar_al_servidor(opciones.servidor, opciones.espera_arranque)
        curado = leer_de_la_guia(Path(opciones.guia))
        recursos = _todo_lo_que_hay_que_cargar(curado, opciones)
        publicados = publicar(opciones.servidor, recursos)
    except (GuiaNoCompiladaError, ServidorNoDisponibleError, RuntimeError) as fallo:
        LOG.error("%s", fallo)
        return 1

    for subido in publicados:
        LOG.info("Cargado %s/%s — %s", subido.tipo, subido.identidad, subido.etiqueta)
    LOG.info("Terminología cargada: %d recursos en %s", len(publicados), opciones.servidor)
    return 0


def _todo_lo_que_hay_que_cargar(curado: Curado, opciones: argparse.Namespace) -> list[dict]:
    """Las tres terminologías externas primero y el catálogo propio después.

    El orden no lo exige la integridad referencial —está apagada— sino la expansión: un `ValueSet`
    que enumera códigos SNOMED se pre-expande al subirlo, y si el `CodeSystem` todavía no está, se
    pre-expande vacío.
    """
    recursos: list[dict] = []

    recursos.append(loinc.codesystem_de(Path(opciones.loinc), curado.loinc))
    recursos.extend(tho.codesystems_de(Path(opciones.tho), curado.sistemas_hl7))
    LOG.info(
        "HL7 Terminology %s — %d sistemas", tho.version_de(Path(opciones.tho)), len(recursos) - 1
    )

    if opciones.snomed:
        recursos.append(snomed.codesystem_de(Path(opciones.snomed), curado.snomed))
    elif curado.snomed:
        # No es un aviso decorativo: sin la Edición Española, `$lookup` de un tipo de muestra no
        # devuelve término y `$validate-code` sobre `tipos-muestra` dice que no a códigos buenos.
        LOG.warning(
            "SNOMED CT NO se carga: falta %s. La guía referencia %d conceptos (%s) y el servidor "
            "no podrá resolverlos. La Edición Española es gratuita en España previo registro ante "
            "el SNS, pero NO se puede redistribuir: descárgala y apunta la variable a la release.",
            VARIABLES["snomed"],
            len(curado.snomed),
            ", ".join(curado.snomed[:3]) + ("…" if len(curado.snomed) > 3 else ""),
        )

    # Los propios, ordenados por tipo: los sistemas antes que los conjuntos que los enumeran y que
    # los mapas que los traducen.
    orden = {"CodeSystem": 0, "ValueSet": 1, "ConceptMap": 2}
    recursos.extend(sorted(curado.propios, key=lambda recurso: orden[recurso["resourceType"]]))
    return recursos


def _esperar_al_servidor(servidor: str, segundos: int) -> None:
    """Espera a que el servidor conteste su `metadata`.

    Raises:
        ServidorNoDisponibleError: Si no contesta a tiempo.
    """
    limite = time.monotonic() + segundos
    ultimo: Exception | None = None
    while time.monotonic() < limite:
        try:
            with urllib.request.urlopen(
                f"{servidor.rstrip('/')}/metadata", timeout=10
            ) as respuesta:
                if respuesta.status == 200:
                    LOG.info("El servidor de terminología responde en %s", servidor)
                    return
        except (urllib.error.URLError, OSError) as todavia_no:
            ultimo = todavia_no
        time.sleep(2)
    raise ServidorNoDisponibleError(
        f"«{servidor}» no ha contestado en {segundos} s. Último intento: {ultimo}"
    )


def _opciones(argumentos: list[str] | None) -> argparse.Namespace:
    analizador = argparse.ArgumentParser(
        prog="cargador", description="Carga el servidor de terminología de HispaLIS."
    )
    analizador.add_argument(
        "--servidor",
        default=os.environ.get(VARIABLES["servidor"], "http://localhost:8090/fhir"),
        help="Base FHIR del servidor de terminología.",
    )
    analizador.add_argument(
        "--guia",
        default=os.environ.get(VARIABLES["guia"], "/guia"),
        help="Recursos que produce SUSHI (ig/fsh-generated/resources).",
    )
    analizador.add_argument(
        "--loinc",
        default=os.environ.get(VARIABLES["loinc"], "/releases/loinc-2.82"),
        help="Raíz de la release de LOINC archivada, fuera del repositorio.",
    )
    analizador.add_argument(
        "--tho",
        default=os.environ.get(VARIABLES["tho"], "/releases/hl7.terminology.r5.tgz"),
        help="Paquete FHIR de HL7 Terminology para R5.",
    )
    analizador.add_argument(
        "--snomed",
        default=os.environ.get(VARIABLES["snomed"], ""),
        help="Raíz de la release RF2 de la Edición Española. Sin ella, SNOMED no se carga.",
    )
    analizador.add_argument(
        "--espera-arranque",
        type=int,
        default=int(os.environ.get("HISPALIS_ESPERA_TERMINOLOGIA", "300")),
        help="Segundos que se espera a que el servidor arranque.",
    )
    return analizador.parse_args(argumentos)


if __name__ == "__main__":
    sys.exit(main())
