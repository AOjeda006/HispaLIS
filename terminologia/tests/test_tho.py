"""Del paquete de HL7 Terminology solo salen los sistemas que la guía nombra."""

from __future__ import annotations

import io
import json
import tarfile
from pathlib import Path

import pytest

from cargador.tho import PaqueteDeThoInvalidoError, codesystems_de, version_de

RECHAZOS = "http://terminology.hl7.org/CodeSystem/rejection-criteria"
CONDICION = "http://terminology.hl7.org/CodeSystem/v2-0493"
AYUNO = "http://terminology.hl7.org/CodeSystem/v2-0916"


def _paquete(destino: Path, *, version: str = "7.3.0") -> Path:
    contenido = {
        "package/package.json": {"name": "hl7.terminology.r5", "version": version},
        "package/CodeSystem-rejection-criteria.json": {
            "resourceType": "CodeSystem",
            "id": "rejection-criteria",
            "url": RECHAZOS,
            "concept": [{"code": "broken"}],
        },
        "package/CodeSystem-v2-0493.json": {
            "resourceType": "CodeSystem",
            "id": "v2-0493",
            "url": CONDICION,
            "concept": [{"code": "CON"}],
        },
        "package/CodeSystem-v2-0916.json": {
            "resourceType": "CodeSystem",
            "id": "v2-0916",
            "url": AYUNO,
        },
        # Un recurso que no es CodeSystem, para comprobar que no se cuela.
        "package/ValueSet-alguno.json": {"resourceType": "ValueSet", "id": "alguno", "url": AYUNO},
    }
    with tarfile.open(destino, "w:gz") as archivo:
        for nombre, recurso in contenido.items():
            crudo = json.dumps(recurso).encode("utf-8")
            info = tarfile.TarInfo(nombre)
            info.size = len(crudo)
            archivo.addfile(info, io.BytesIO(crudo))
    return destino


def test_la_version_la_declara_el_propio_paquete(tmp_path: Path) -> None:
    """No se deduce del nombre del fichero: el manifiesto la lleva dentro."""
    paquete = _paquete(tmp_path / "hl7.terminology.r5.tgz")

    assert version_de(paquete) == "7.3.0"


def test_solo_se_extraen_los_sistemas_pedidos(tmp_path: Path) -> None:
    """El paquete R5 trae 928 sistemas; cargarlos todos es ruido que parece terminología en uso."""
    paquete = _paquete(tmp_path / "hl7.terminology.r5.tgz")

    sistemas = codesystems_de(paquete, [RECHAZOS, CONDICION])

    assert [sistema["url"] for sistema in sistemas] == sorted([RECHAZOS, CONDICION])


def test_un_valueset_con_la_misma_url_no_se_confunde_con_el_sistema(tmp_path: Path) -> None:
    paquete = _paquete(tmp_path / "hl7.terminology.r5.tgz")

    sistemas = codesystems_de(paquete, [AYUNO])

    assert [sistema["resourceType"] for sistema in sistemas] == ["CodeSystem"]


def test_una_url_que_el_paquete_no_define_para_la_carga(tmp_path: Path) -> None:
    """El síntoma tardío sería un `$validate-code` que dice que no a todo."""
    paquete = _paquete(tmp_path / "hl7.terminology.r5.tgz")

    with pytest.raises(PaqueteDeThoInvalidoError, match=r"aliases\.fsh"):
        codesystems_de(paquete, ["http://terminology.hl7.org/CodeSystem/inventado"])


def test_sin_paquete_se_explica_donde_esta(tmp_path: Path) -> None:
    with pytest.raises(PaqueteDeThoInvalidoError, match="HISPALIS_THO"):
        version_de(tmp_path / "no-esta.tgz")


def test_sin_sistemas_pedidos_no_se_abre_el_paquete(tmp_path: Path) -> None:
    assert codesystems_de(tmp_path / "ni-existe.tgz", []) == []
