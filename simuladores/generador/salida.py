"""Escritura de lo generado en disco.

Los recursos se separan del manifiesto en dos sitios distintos y no es cosmética: el validador
oficial de HL7 recorre el directorio entero y **falla con cualquier JSON que no sea un recurso
FHIR**. Un manifiesto suelto entre los recursos convierte una comprobación de conformidad en un
error de parseo, y de esos se tarda un rato en salir.
"""

from __future__ import annotations

import json
from pathlib import Path

from generador.configuracion import ConfiguracionGeneracion
from generador.escenario import Corpus

#: Subdirectorio con los recursos FHIR y nada más.
CARPETA_RECURSOS = "recursos"

NOMBRE_MANIFIESTO = "manifiesto.json"


def escribir(corpus: Corpus, configuracion: ConfiguracionGeneracion) -> Path:
    """Vuelca el corpus y su manifiesto.

    Args:
        corpus: Lo generado.
        configuracion: La ejecución que lo produjo.

    Returns:
        El directorio con los recursos FHIR.
    """
    recursos = configuracion.salida / CARPETA_RECURSOS
    recursos.mkdir(parents=True, exist_ok=True)

    for recurso in corpus.recursos:
        nombre = f"{recurso['resourceType']}-{recurso['id']}.json"
        _escribir_json(recursos / nombre, recurso)

    _escribir_json(configuracion.salida / NOMBRE_MANIFIESTO, _manifiesto(corpus, configuracion))
    return recursos


def _manifiesto(corpus: Corpus, configuracion: ConfiguracionGeneracion) -> dict:
    """Describe la ejecución: con qué se generó y qué salió.

    Sirve para reproducirla —trae los tres parámetros que fijan la salida— y para ver de un
    vistazo si el corpus tiene lo que tiene que tener: sin muestras rechazadas ni reflejas, no
    ejercita ni el invariante C6 ni `triggeredBy`.
    """
    return {
        "generadoCon": {
            "semilla": configuracion.semilla,
            "pacientes": configuracion.pacientes,
            "fecha": configuracion.fecha.isoformat(),
        },
        "recuento": {
            tipo: len(corpus.de_tipo(tipo))
            for tipo in (
                "Organization",
                "Practitioner",
                "Patient",
                "ServiceRequest",
                "Specimen",
                "Observation",
                "DiagnosticReport",
            )
        },
        "episodios": corpus.episodios,
        "muestrasRechazadas": corpus.muestras_rechazadas,
        "pruebasReflejas": corpus.reflejas,
        "pacientesSinNuhsa": sum(1 for paciente in corpus.pacientes if paciente.nuhsa is None),
    }


def _escribir_json(ruta: Path, contenido: dict) -> None:
    # `ensure_ascii=False` no es una preferencia: con la opción por defecto, «MUÑOZ» se escribe
    # `MUÑOZ` y el fichero deja de servir como caso de prueba de charset, que es justo para lo
    # que está. `sort_keys` mantiene el volcado estable entre ejecuciones.
    ruta.write_text(
        json.dumps(contenido, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
