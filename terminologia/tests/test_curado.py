"""El subconjunto sale de la guía, no de una lista escrita a mano."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from cargador.curado import GuiaNoCompiladaError, leer_de_la_guia

BASE = "https://aojeda006.github.io/HispaLIS/fhir"


def _guia(directorio: Path, *recursos: dict) -> Path:
    recursos_dir = directorio / "resources"
    recursos_dir.mkdir()
    for indice, recurso in enumerate(recursos):
        ruta = recursos_dir / f"{recurso['resourceType']}-{indice}.json"
        ruta.write_text(json.dumps(recurso), encoding="utf-8")
    return recursos_dir


CATALOGO = {
    "resourceType": "CodeSystem",
    "id": "catalogo-pruebas",
    "url": f"{BASE}/CodeSystem/catalogo-pruebas",
    "concept": [{"code": "GLU", "display": "Glucosa"}],
}

MAPA = {
    "resourceType": "ConceptMap",
    "id": "catalogo-a-loinc",
    "url": f"{BASE}/ConceptMap/catalogo-a-loinc",
    "group": [
        {
            "source": f"{BASE}/CodeSystem/catalogo-pruebas",
            "target": "http://loinc.org",
            "element": [
                {"code": "GLU", "target": [{"code": "2345-7", "relationship": "equivalent"}]}
            ],
        }
    ],
}

MUESTRAS = {
    "resourceType": "ValueSet",
    "id": "tipos-muestra",
    "url": f"{BASE}/ValueSet/tipos-muestra",
    "compose": {
        "include": [
            {"system": "http://snomed.info/sct", "concept": [{"code": "119297000"}]},
            {
                "system": "http://terminology.hl7.org/CodeSystem/v2-0493",
                "concept": [{"code": "CON"}],
            },
        ]
    },
}


def test_los_loinc_salen_de_los_destinos_del_conceptmap(tmp_path: Path) -> None:
    curado = leer_de_la_guia(_guia(tmp_path, CATALOGO, MAPA))

    assert curado.loinc == ("2345-7",)


def test_los_snomed_salen_de_los_valueset(tmp_path: Path) -> None:
    curado = leer_de_la_guia(_guia(tmp_path, MUESTRAS))

    assert curado.snomed == ("119297000",)


def test_los_sistemas_de_hl7_terminology_se_recogen_de_donde_se_citen(tmp_path: Path) -> None:
    curado = leer_de_la_guia(_guia(tmp_path, MUESTRAS))

    assert curado.sistemas_hl7 == ("http://terminology.hl7.org/CodeSystem/v2-0493",)


def test_los_artefactos_propios_se_suben_tal_cual(tmp_path: Path) -> None:
    curado = leer_de_la_guia(_guia(tmp_path, CATALOGO, MAPA, MUESTRAS))

    assert {recurso["id"] for recurso in curado.propios} == {
        "catalogo-pruebas",
        "catalogo-a-loinc",
        "tipos-muestra",
    }


def test_los_ejemplos_de_la_guia_no_se_suben(tmp_path: Path) -> None:
    """Un `Patient` de ejemplo comparte carpeta con la terminología y no es terminología."""
    paciente = {"resourceType": "Patient", "id": "paciente-ejemplo"}

    curado = leer_de_la_guia(_guia(tmp_path, CATALOGO, paciente))

    assert [recurso["resourceType"] for recurso in curado.propios] == ["CodeSystem"]


def test_un_codigo_fijado_en_un_perfil_tambien_cuenta(tmp_path: Path) -> None:
    """El LOINC del informe no está en el `ConceptMap`: está clavado en `InformeLab`."""
    perfil = {
        "resourceType": "StructureDefinition",
        "id": "informe-lab",
        "differential": {
            "element": [
                {
                    "id": "DiagnosticReport.code",
                    "patternCodeableConcept": {
                        "coding": [{"system": "http://loinc.org", "code": "11502-2"}]
                    },
                }
            ]
        },
    }

    curado = leer_de_la_guia(_guia(tmp_path, CATALOGO, MAPA, perfil))

    assert curado.loinc == ("11502-2", "2345-7")
    assert all(recurso["resourceType"] != "StructureDefinition" for recurso in curado.propios)


def test_un_sistema_de_hl7_solo_nombrado_por_un_ejemplo_tambien_se_carga(tmp_path: Path) -> None:
    """Sin su `CodeSystem`, `$validate-code` contestaría que no a un código bueno."""
    ejemplo = {
        "resourceType": "Specimen",
        "id": "especimen-ejemplo",
        "collection": {
            "fastingStatusCodeableConcept": {
                "coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0916", "code": "F"}]
            }
        },
    }

    curado = leer_de_la_guia(_guia(tmp_path, CATALOGO, ejemplo))

    assert "http://terminology.hl7.org/CodeSystem/v2-0916" in curado.sistemas_hl7


def test_sin_guia_compilada_se_dice_que_hay_que_ejecutar_sushi(tmp_path: Path) -> None:
    with pytest.raises(GuiaNoCompiladaError, match="fsh-sushi"):
        leer_de_la_guia(tmp_path / "no-existe")


def test_una_carpeta_sin_terminologia_no_pasa_por_buena(tmp_path: Path) -> None:
    """Apuntar a la carpeta equivocada tiene que doler aquí, no en el primer `$lookup`."""
    with pytest.raises(GuiaNoCompiladaError, match="ningún CodeSystem"):
        leer_de_la_guia(_guia(tmp_path, {"resourceType": "Patient", "id": "x"}))
