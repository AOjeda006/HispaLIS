"""El fragmento de LOINC: qué se extrae, qué versión se declara y qué no se toca.

La release de verdad son 446 MB fuera del repositorio, así que aquí se monta una release
**mínima** con la misma forma. Lo que se prueba es el contrato con el formato, no el contenido
de Regenstrief.
"""

from __future__ import annotations

import csv
from pathlib import Path

import pytest

from cargador.loinc import ReleaseDeLoincInvalidaError, codesystem_de, version_de

CABECERA = ["LOINC_NUM", "COMPONENT", "LONG_COMMON_NAME", "SHORTNAME", "STATUS"]

FILAS = [
    [
        "2345-7",
        "Glucose",
        "Glucose [Mass/volume] in Serum or Plasma",
        "Glucose SerPl-mCnc",
        "ACTIVE",
    ],
    ["718-7", "Hemoglobin", "Hemoglobin [Mass/volume] in Blood", "Hgb Bld-mCnc", "ACTIVE"],
]


def _release(raiz: Path, *, con_variante_espanola: bool = True) -> Path:
    (raiz / "LoincTable").mkdir(parents=True)
    with (raiz / "LoincTable" / "Loinc.csv").open("w", encoding="utf-8", newline="") as tabla:
        escritor = csv.writer(tabla)
        escritor.writerow(CABECERA)
        escritor.writerows(FILAS)

    variantes = raiz / "AccessoryFiles" / "LinguisticVariants"
    variantes.mkdir(parents=True)
    with (variantes / "LinguisticVariants.csv").open("w", encoding="utf-8", newline="") as indice:
        escritor = csv.writer(indice)
        escritor.writerow(["ID", "ISO_LANGUAGE", "ISO_COUNTRY", "LANGUAGE_NAME", "PRODUCER"])
        escritor.writerow(["28", "es", "MX", "Spanish (MEXICO)", "—"])
        escritor.writerow(["12", "es", "ES", "Spanish (SPAIN)", "—"])

    if con_variante_espanola:
        with (variantes / "esES12LinguisticVariant.csv").open(
            "w", encoding="utf-8", newline=""
        ) as variante:
            escritor = csv.writer(variante)
            escritor.writerow(
                ["LOINC_NUM", "COMPONENT", "SHORTNAME", "LinguisticVariantDisplayName"]
            )
            # Como en LOINC 2.82 de verdad: los ejes traducidos y el nombre largo VACÍO.
            escritor.writerow(["2345-7", "Glucosa", "", ""])
            escritor.writerow(["718-7", "Hemoglobina", "Hemoglobina Sangre", ""])
    return raiz


def test_la_version_se_lee_del_nombre_con_que_esta_archivada(tmp_path: Path) -> None:
    raiz = _release(tmp_path / "loinc-2.82")

    assert version_de(raiz) == "2.82"


def test_una_carpeta_que_no_dice_su_version_no_se_carga(tmp_path: Path) -> None:
    """Cargar sin saber la versión deja los `display` sin poder reproducirse."""
    raiz = _release(tmp_path / "loinc-descarga-nueva")

    with pytest.raises(ReleaseDeLoincInvalidaError, match="no dice qué versión"):
        version_de(raiz)


def test_solo_entran_los_codigos_que_pide_la_guia(tmp_path: Path) -> None:
    raiz = _release(tmp_path / "loinc-2.82")

    sistema = codesystem_de(raiz, ["2345-7"])

    assert [concepto["code"] for concepto in sistema["concept"]] == ["2345-7"]
    assert sistema["count"] == 1


def test_se_declara_fragmento_y_no_sistema_completo(tmp_path: Path) -> None:
    """21 conceptos de cien mil son un trozo, y FHIR tiene una palabra para decirlo."""
    raiz = _release(tmp_path / "loinc-2.82")

    sistema = codesystem_de(raiz, ["2345-7", "718-7"])

    assert sistema["content"] == "fragment"
    assert sistema["url"] == "http://loinc.org"
    assert sistema["version"] == "2.82"


def test_el_display_es_el_nombre_oficial_sin_alterar(tmp_path: Path) -> None:
    """La licencia prohíbe cambiar el contenido de los campos; ADR-0009 dice qué pasa si se hace."""
    raiz = _release(tmp_path / "loinc-2.82")

    sistema = codesystem_de(raiz, ["2345-7"])

    assert sistema["concept"][0]["display"] == "Glucose [Mass/volume] in Serum or Plasma"


def test_el_aviso_de_copyright_y_la_version_viajan_con_el_fragmento(tmp_path: Path) -> None:
    """Es condición de la licencia: cada copia lleva el aviso y la versión."""
    raiz = _release(tmp_path / "loinc-2.82")

    sistema = codesystem_de(raiz, ["2345-7"])

    assert "Regenstrief" in sistema["copyright"]
    assert "2.82" in sistema["title"]


def test_el_nombre_en_espanol_solo_entra_si_LOINC_lo_publica(tmp_path: Path) -> None:  # noqa: N802
    """No se compone un nombre a partir de los ejes traducidos: sería inventar un término LOINC."""
    raiz = _release(tmp_path / "loinc-2.82")

    sistema = codesystem_de(raiz, ["2345-7", "718-7"])
    por_codigo = {concepto["code"]: concepto for concepto in sistema["concept"]}

    en_espanol = [
        designacion
        for designacion in por_codigo["2345-7"].get("designation", [])
        if designacion["language"] == "es"
    ]
    assert en_espanol == [], "el nombre largo en español está vacío en la release"
    assert {"language": "es", "value": "Hemoglobina Sangre"} in por_codigo["718-7"]["designation"]


def test_la_variante_espanola_se_localiza_por_el_indice_no_por_el_nombre(tmp_path: Path) -> None:
    """El identificador de la variante puede cambiar; el índice dice cuál es el de España."""
    raiz = _release(tmp_path / "loinc-2.82", con_variante_espanola=False)

    sistema = codesystem_de(raiz, ["718-7"])

    assert all(
        designacion["language"] != "es"
        for designacion in sistema["concept"][0].get("designation", [])
    )


def test_un_codigo_que_la_release_no_tiene_para_la_carga(tmp_path: Path) -> None:
    """El error está en el ConceptMap y el mensaje lo dice: callarlo rompería `$translate`."""
    raiz = _release(tmp_path / "loinc-2.82")

    with pytest.raises(ReleaseDeLoincInvalidaError, match="ConceptMap"):
        codesystem_de(raiz, ["2345-7", "0000-0"])


def test_sin_release_se_explica_donde_esta(tmp_path: Path) -> None:
    with pytest.raises(ReleaseDeLoincInvalidaError, match="HISPALIS_LOINC"):
        version_de(tmp_path / "loinc-2.82")
