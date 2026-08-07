"""El fragmento de SNOMED: la versión se lee de la release y el `display` va en español.

**La release de verdad no está en este equipo ni puede estarlo:** la Edición Española no se
redistribuye. Lo que se monta aquí es una release RF2 mínima con identificadores **inventados**
—no hay ni un concepto real de SNOMED en este fichero—, que es lo que hace falta para probar el
contrato con el formato: dónde se lee la versión, qué término acaba de `display` y qué pasa si
el concepto está retirado.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from cargador.snomed import ReleaseDeSnomedInvalidaError, codesystem_de, edicion_de

MODULO_DE_MODELO = "900000000000012004"
MODULO_DE_LA_EDICION = "111000000001"
FSN = "900000000000003001"
SINONIMO = "900000000000013009"
PREFERENTE = "900000000000548007"
ACEPTABLE = "900000000000549004"

MUESTRA = "222000000002"
RETIRADO = "333000000003"


def _tabla(ruta: Path, cabecera: list[str], filas: list[list[str]]) -> None:
    ruta.parent.mkdir(parents=True, exist_ok=True)
    lineas = ["\t".join(cabecera), *["\t".join(fila) for fila in filas]]
    ruta.write_text("\n".join(lineas) + "\n", encoding="utf-8")


def _release(raiz: Path, *, con_dependencias: bool = True, fecha: str = "20250430") -> Path:
    terminologia = raiz / "Snapshot" / "Terminology"
    refset = raiz / "Snapshot" / "Refset"

    _tabla(
        terminologia / f"sct2_Concept_Snapshot_ES_{fecha}.txt",
        ["id", "effectiveTime", "active", "moduleId", "definitionStatusId"],
        [
            [MUESTRA, fecha, "1", MODULO_DE_LA_EDICION, "900000000000074008"],
            [RETIRADO, fecha, "0", MODULO_DE_LA_EDICION, "900000000000074008"],
        ],
    )
    _tabla(
        terminologia / f"sct2_Description_Snapshot-es_ES_{fecha}.txt",
        [
            "id",
            "effectiveTime",
            "active",
            "moduleId",
            "conceptId",
            "languageCode",
            "typeId",
            "term",
            "caseSignificanceId",
        ],
        [
            [
                "1001",
                fecha,
                "1",
                MODULO_DE_LA_EDICION,
                MUESTRA,
                "es",
                FSN,
                "muestra de sangre (especimen)",
                "900000000000448009",
            ],
            [
                "1002",
                fecha,
                "1",
                MODULO_DE_LA_EDICION,
                MUESTRA,
                "es",
                SINONIMO,
                "Muestra de sangre",
                "900000000000448009",
            ],
            [
                "1003",
                fecha,
                "1",
                MODULO_DE_LA_EDICION,
                MUESTRA,
                "es",
                SINONIMO,
                "Espécimen sanguíneo",
                "900000000000448009",
            ],
        ],
    )
    _tabla(
        refset / "Language" / f"der2_cRefset_LanguageSnapshot-es_ES_{fecha}.txt",
        [
            "id",
            "effectiveTime",
            "active",
            "moduleId",
            "refsetId",
            "referencedComponentId",
            "acceptabilityId",
        ],
        [
            ["a1", fecha, "1", MODULO_DE_LA_EDICION, "450828004", "1002", PREFERENTE],
            ["a2", fecha, "1", MODULO_DE_LA_EDICION, "450828004", "1003", ACEPTABLE],
        ],
    )
    if con_dependencias:
        _tabla(
            refset / "Metadata" / f"der2_ssRefset_ModuleDependencySnapshot_ES_{fecha}.txt",
            [
                "id",
                "effectiveTime",
                "active",
                "moduleId",
                "refsetId",
                "referencedComponentId",
                "sourceEffectiveTime",
                "targetEffectiveTime",
            ],
            [
                [
                    "d1",
                    fecha,
                    "1",
                    MODULO_DE_LA_EDICION,
                    "900000000000534007",
                    MODULO_DE_MODELO,
                    fecha,
                    "20250101",
                ],
            ],
        )
    return raiz


def test_la_version_es_la_uri_canonica_de_snomed(tmp_path: Path) -> None:
    """`{módulo}/version/{fecha}`, con las dos mitades leídas de la propia release."""
    edicion = edicion_de(_release(tmp_path / "release-es"))

    assert edicion.version == f"http://snomed.info/sct/{MODULO_DE_LA_EDICION}/version/20250430"


def test_una_release_que_no_declara_su_edicion_no_se_carga(tmp_path: Path) -> None:
    raiz = _release(tmp_path / "release-es", con_dependencias=False)

    with pytest.raises(ReleaseDeSnomedInvalidaError):
        edicion_de(raiz)


def test_el_display_es_el_termino_preferente_en_espanol(tmp_path: Path) -> None:
    """D7: en un informe español el `display` va en español, y no es el FSN."""
    sistema = codesystem_de(_release(tmp_path / "release-es"), [MUESTRA])

    assert sistema["concept"][0]["display"] == "Muestra de sangre"


def test_el_nombre_completamente_especificado_va_como_designacion(tmp_path: Path) -> None:
    """El FSN lleva la etiqueta semántica entre paréntesis: sirve para desambiguar, no para leer."""
    sistema = codesystem_de(_release(tmp_path / "release-es"), [MUESTRA])

    designaciones = sistema["concept"][0]["designation"]
    assert designaciones[0]["value"] == "muestra de sangre (especimen)"
    assert designaciones[0]["use"]["code"] == FSN


def test_un_sinonimo_que_no_es_preferente_no_se_convierte_en_display(tmp_path: Path) -> None:
    sistema = codesystem_de(_release(tmp_path / "release-es"), [MUESTRA])

    assert sistema["concept"][0]["display"] != "Espécimen sanguíneo"


def test_un_concepto_retirado_para_la_carga(tmp_path: Path) -> None:
    """Si la guía referencia un código inactivo, hay que enterarse al cargar, no al informar."""
    with pytest.raises(ReleaseDeSnomedInvalidaError, match="retirado"):
        codesystem_de(_release(tmp_path / "release-es"), [MUESTRA, RETIRADO])


def test_se_declara_fragmento_con_su_version(tmp_path: Path) -> None:
    sistema = codesystem_de(_release(tmp_path / "release-es"), [MUESTRA])

    assert sistema["content"] == "fragment"
    assert sistema["url"] == "http://snomed.info/sct"
    assert sistema["version"].endswith("/version/20250430")
    assert "20250430" in sistema["title"]


def test_sin_release_se_explica_que_hay_que_descargarla(tmp_path: Path) -> None:
    with pytest.raises(ReleaseDeSnomedInvalidaError, match="HISPALIS_SNOMED"):
        edicion_de(tmp_path / "no-esta")
