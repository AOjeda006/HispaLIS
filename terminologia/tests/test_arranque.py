"""Qué carga el cargador cuando una release externa no está donde se dijo.

LOINC, THO y SNOMED viven **fuera del repositorio** y ninguna de las tres se puede dar por
presente: la del Ministerio no se redistribuye y las otras dos están archivadas en la biblioteca,
que es una carpeta hermana y no una dependencia. Un cargador que muere porque falta una release
deja el servidor **vacío**, y un servidor de terminología vacío contesta que no a todo — que es
justo la forma de estar disponible que más caro sale.

La regla que se prueba aquí es la distinción que importa:

- **La release no está** → se avisa en voz alta, listando lo que se queda sin resolver, y se carga
  lo demás. Es lo que ya hacía SNOMED desde el primer día.
- **La release está y está rota** → se falla. Una release a medias no es una release ausente: si
  el fichero existe pero le falta un código que la guía pide, callarse produce un `$lookup` que no
  devuelve nombre y nadie sabe por qué.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from cargador import loinc, tho
from cargador.__main__ import main

CATALOGO = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas"


@pytest.fixture
def guia(tmp_path: Path) -> Path:
    """Una guía compilada mínima: un `CodeSystem` propio que referencia un LOINC y un THO."""
    recursos = tmp_path / "guia"
    recursos.mkdir()
    (recursos / "CodeSystem-catalogo-pruebas.json").write_text(
        json.dumps(
            {
                "resourceType": "CodeSystem",
                "id": "catalogo-pruebas",
                "url": CATALOGO,
                "concept": [{"code": "GLU", "display": "Glucosa"}],
            }
        ),
        encoding="utf-8",
    )
    (recursos / "ConceptMap-catalogo-a-loinc.json").write_text(
        json.dumps(
            {
                "resourceType": "ConceptMap",
                "id": "catalogo-a-loinc",
                "url": "https://aojeda006.github.io/HispaLIS/fhir/ConceptMap/catalogo-a-loinc",
                "group": [
                    {
                        "source": CATALOGO,
                        "target": "http://loinc.org",
                        "element": [
                            {
                                "code": "GLU",
                                "target": [{"code": "2345-7", "relationship": "equivalent"}],
                            }
                        ],
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    return recursos


def test_una_release_ausente_no_impide_cargar_lo_propio(
    guia: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """Sin LOINC ni THO archivados, el catálogo del laboratorio se carga igual."""
    subidos: list[dict] = []
    monkeypatch.setattr("cargador.__main__._esperar_al_servidor", lambda *_: None)
    monkeypatch.setattr(
        "cargador.__main__.publicar",
        lambda _servidor, recursos: [_subido(r) for r in subidos_de(recursos, subidos)],
    )

    codigo = main(
        [
            "--servidor",
            "http://no-se-llama/fhir",
            "--guia",
            str(guia),
            "--loinc",
            str(tmp_path / "no-esta" / "loinc-2.82"),
            "--tho",
            str(tmp_path / "no-esta" / "hl7.terminology.r5.tgz"),
        ]
    )

    assert codigo == 0
    assert [r["url"] for r in subidos if r["resourceType"] == "CodeSystem"] == [CATALOGO]
    avisos = "\n".join(r.getMessage() for r in caplog.records if r.levelname == "WARNING")
    assert "LOINC" in avisos and "2345-7" in avisos, "el aviso tiene que decir qué se queda fuera"


def test_una_release_presente_pero_rota_sigue_siendo_un_fallo(
    guia: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Un LOINC archivado al que le falta el código que la guía pide **no** se ignora."""
    monkeypatch.setattr("cargador.__main__._esperar_al_servidor", lambda *_: None)
    release = tmp_path / "loinc-2.82"
    (release / "LoincTable").mkdir(parents=True)
    (release / "LoincTable" / "Loinc.csv").write_text(
        "LOINC_NUM,COMPONENT,LONG_COMMON_NAME,SHORTNAME,STATUS\n", encoding="utf-8"
    )

    codigo = main(
        [
            "--servidor",
            "http://no-se-llama/fhir",
            "--guia",
            str(guia),
            "--loinc",
            str(release),
            "--tho",
            str(tmp_path / "no-esta.tgz"),
        ]
    )

    assert codigo == 1, "una release rota no es una release ausente"


def test_saber_si_una_release_esta_archivada_no_exige_leerla(tmp_path: Path) -> None:
    """La distinción «está / no está» es una pregunta barata, y se hace antes de abrir nada."""
    assert not loinc.esta_archivada(tmp_path / "loinc-2.82")
    assert not tho.esta_archivado(tmp_path / "hl7.terminology.r5.tgz")

    release = tmp_path / "loinc-2.82"
    (release / "LoincTable").mkdir(parents=True)
    (release / "LoincTable" / "Loinc.csv").write_text("LOINC_NUM\n", encoding="utf-8")
    paquete = tmp_path / "hl7.terminology.r5.tgz"
    paquete.write_bytes(b"")

    assert loinc.esta_archivada(release)
    assert tho.esta_archivado(paquete)


def subidos_de(recursos: list[dict], acumulador: list[dict]) -> list[dict]:
    acumulador.extend(recursos)
    return recursos


def _subido(recurso: dict) -> object:
    from cargador.publicacion import Subido

    return Subido(
        tipo=recurso["resourceType"], identidad=recurso.get("id", "?"), etiqueta=recurso["url"]
    )
