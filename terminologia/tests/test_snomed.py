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

from cargador.snomed import Edicion, ReleaseDeSnomedInvalidaError, codesystem_de, edicion_de

MODULO_DE_MODELO = "900000000000012004"
MODULO_DE_LA_EDICION = "111000000001"
FSN = "900000000000003001"
SINONIMO = "900000000000013009"
PREFERENTE = "900000000000548007"
ACEPTABLE = "900000000000549004"

MUESTRA = "222000000002"
RETIRADO = "333000000003"

# Los tres módulos de una edición española compuesta. Son metadatos publicados, no contenido: el
# del núcleo y el de la Edición Española son los de siempre, y el de la extensión del SNS es el que
# ya declara `ig/input/fsh/aliases.fsh`.
MODULO_DEL_NUCLEO = "900000000000207008"
MODULO_DE_LA_ESPANOLA = "450829007"
MODULO_DE_LA_EXTENSION = "900000001000122104"

#: Un concepto de la Edición Internacional: el concepto está en un paquete y su término español, en
#: otro. Inventado, como todos los de este fichero.
INTERNACIONAL = "888000000008"
#: Un concepto creado por la extensión del SNS: concepto y término viven juntos en su paquete.
DEL_SNS = "999000000009"


CABECERA_CONCEPTO = ["id", "effectiveTime", "active", "moduleId", "definitionStatusId"]
CABECERA_DESCRIPCION = [
    "id",
    "effectiveTime",
    "active",
    "moduleId",
    "conceptId",
    "languageCode",
    "typeId",
    "term",
    "caseSignificanceId",
]
CABECERA_IDIOMA = [
    "id",
    "effectiveTime",
    "active",
    "moduleId",
    "refsetId",
    "referencedComponentId",
    "acceptabilityId",
]
CABECERA_DEPENDENCIA = [
    "id",
    "effectiveTime",
    "active",
    "moduleId",
    "refsetId",
    "referencedComponentId",
    "sourceEffectiveTime",
    "targetEffectiveTime",
]


def _tabla(ruta: Path, cabecera: list[str], filas: list[list[str]]) -> None:
    ruta.parent.mkdir(parents=True, exist_ok=True)
    lineas = ["\t".join(cabecera), *["\t".join(fila) for fila in filas]]
    ruta.write_text("\n".join(lineas) + "\n", encoding="utf-8")


def _release(raiz: Path, *, con_dependencias: bool = True, fecha: str = "20250430") -> Path:
    terminologia = raiz / "Snapshot" / "Terminology"
    refset = raiz / "Snapshot" / "Refset"

    _tabla(
        terminologia / f"sct2_Concept_Snapshot_ES_{fecha}.txt",
        CABECERA_CONCEPTO,
        [
            [MUESTRA, fecha, "1", MODULO_DE_LA_EDICION, "900000000000074008"],
            [RETIRADO, fecha, "0", MODULO_DE_LA_EDICION, "900000000000074008"],
        ],
    )
    _tabla(
        terminologia / f"sct2_Description_Snapshot-es_ES_{fecha}.txt",
        CABECERA_DESCRIPCION,
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
        CABECERA_IDIOMA,
        [
            ["a1", fecha, "1", MODULO_DE_LA_EDICION, "450828004", "1002", PREFERENTE],
            ["a2", fecha, "1", MODULO_DE_LA_EDICION, "450828004", "1003", ACEPTABLE],
        ],
    )
    if con_dependencias:
        _tabla(
            refset / "Metadata" / f"der2_ssRefset_ModuleDependencySnapshot_ES_{fecha}.txt",
            CABECERA_DEPENDENCIA,
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


def _paquete(raiz: Path, nombre: str, modulo: str, fecha: str) -> tuple[Path, Path]:
    """Las dos carpetas de un paquete RF2 y su declaración de dependencia del módulo de modelo."""
    paquete = raiz / nombre
    _tabla(
        paquete
        / "Snapshot"
        / "Refset"
        / "Metadata"
        / f"der2_ssRefset_ModuleDependencySnapshot_{fecha}.txt",
        CABECERA_DEPENDENCIA,
        [["d-" + modulo, fecha, "1", modulo, "900000000000534007", MODULO_DE_MODELO, fecha, fecha]],
    )
    return paquete / "Snapshot" / "Terminology", paquete / "Snapshot" / "Refset" / "Language"


def _compuesta(
    raiz: Path,
    *,
    nombres: tuple[str, str, str] = ("internacional", "espanola", "extension"),
    fecha_de_la_extension: str = "20260601",
) -> Path:
    """Una edición española como la de verdad: tres paquetes bajo una raíz y a tres fechas.

    Es la forma en que se distribuye, y no un montaje de este test: la Edición Internacional trae
    los conceptos con sus términos en inglés, la *Spanish Edition* trae **solo descripciones** en
    español de esos mismos conceptos, y la extensión del SNS trae sus propios conceptos con los
    suyos. Ninguno de los tres, por separado, sabe decir qué es `INTERNACIONAL` en español.
    """
    internacional, espanola, extension = nombres

    terminologia, _ = _paquete(raiz, internacional, MODULO_DEL_NUCLEO, "20260401")
    _tabla(
        terminologia / "sct2_Concept_Snapshot_INT_20260401.txt",
        CABECERA_CONCEPTO,
        [[INTERNACIONAL, "20260401", "1", MODULO_DEL_NUCLEO, "900000000000074008"]],
    )
    _tabla(
        terminologia / "sct2_Description_Snapshot-en_INT_20260401.txt",
        CABECERA_DESCRIPCION,
        [
            [
                "2001",
                "20260401",
                "1",
                MODULO_DEL_NUCLEO,
                INTERNACIONAL,
                "en",
                FSN,
                "x (specimen)",
                "900000000000448009",
            ],
        ],
    )

    terminologia, idioma = _paquete(raiz, espanola, MODULO_DE_LA_ESPANOLA, "20260510")
    _tabla(
        terminologia / "sct2_Description_Snapshot-es_ES_20260510.txt",
        CABECERA_DESCRIPCION,
        [
            [
                "2002",
                "20260510",
                "1",
                MODULO_DE_LA_ESPANOLA,
                INTERNACIONAL,
                "es",
                FSN,
                "muestra de suero (especimen)",
                "900000000000448009",
            ],
            [
                "2003",
                "20260510",
                "1",
                MODULO_DE_LA_ESPANOLA,
                INTERNACIONAL,
                "es",
                SINONIMO,
                "Muestra de suero",
                "900000000000448009",
            ],
        ],
    )
    _tabla(
        idioma / "der2_cRefset_LanguageSnapshot-es_ES_20260510.txt",
        CABECERA_IDIOMA,
        [["b1", "20260510", "1", MODULO_DE_LA_ESPANOLA, "450828004", "2003", PREFERENTE]],
    )

    fecha = fecha_de_la_extension
    terminologia, idioma = _paquete(raiz, extension, MODULO_DE_LA_EXTENSION, fecha)
    _tabla(
        terminologia / f"sct2_Concept_Snapshot_ES1000122_{fecha}.txt",
        CABECERA_CONCEPTO,
        [[DEL_SNS, fecha, "1", MODULO_DE_LA_EXTENSION, "900000000000074008"]],
    )
    _tabla(
        terminologia / f"sct2_Description_Snapshot-es_ES1000122_{fecha}.txt",
        CABECERA_DESCRIPCION,
        [
            [
                "3001",
                fecha,
                "1",
                MODULO_DE_LA_EXTENSION,
                DEL_SNS,
                "es",
                FSN,
                "espécimen de la extensión (especimen)",
                "900000000000448009",
            ],
            [
                "3002",
                fecha,
                "1",
                MODULO_DE_LA_EXTENSION,
                DEL_SNS,
                "es",
                SINONIMO,
                "Espécimen de la extensión",
                "900000000000448009",
            ],
        ],
    )
    _tabla(
        idioma / f"der2_cRefset_LanguageSnapshot-es_ES1000122_{fecha}.txt",
        CABECERA_IDIOMA,
        [["c1", fecha, "1", MODULO_DE_LA_EXTENSION, "450828004", "3002", PREFERENTE]],
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


def test_el_termino_espanol_de_un_concepto_internacional_esta_en_otro_paquete(
    tmp_path: Path,
) -> None:
    """El concepto lo publica la Edición Internacional y su nombre en español, la *Spanish Edition*.

    Es el caso que no puede resolver quien lea un fichero por patrón: el `display` sale de cruzar
    dos paquetes distintos, descargados por separado y a fechas distintas.
    """
    sistema = codesystem_de(_compuesta(tmp_path / "es"), [INTERNACIONAL])

    assert sistema["concept"][0]["display"] == "Muestra de suero"


def test_los_conceptos_propios_de_la_extension_se_cargan_con_los_internacionales(
    tmp_path: Path,
) -> None:
    """Los códigos del ítem 42 son de la extensión y los tipos de muestra, internacionales."""
    sistema = codesystem_de(_compuesta(tmp_path / "es"), [INTERNACIONAL, DEL_SNS])

    assert [concepto["code"] for concepto in sistema["concept"]] == sorted([INTERNACIONAL, DEL_SNS])
    assert sistema["concept"][1]["display"] == "Espécimen de la extensión"


def test_la_edicion_declarada_no_depende_de_como_se_llamen_las_carpetas(tmp_path: Path) -> None:
    """La misma edición descomprimida con otros nombres es la misma edición.

    Quien descarga los tres paquetes los descomprime donde y como quiere, así que el orden
    alfabético de las carpetas es aleatorio respecto al contenido. La edición que se declara es la
    del **paquete que depende de los otros dos** —la extensión, publicada después y alineada con
    ellos—, porque es la que nombra al conjunto: es la URI que `aliases.fsh` llama `$SCT_ES`.
    """
    de_una_forma = _compuesta(tmp_path / "a", nombres=("1-int", "2-esp", "3-ext"))
    del_reves = _compuesta(tmp_path / "b", nombres=("3-int", "2-esp", "1-ext"))

    assert edicion_de(de_una_forma) == Edicion(modulo=MODULO_DE_LA_EXTENSION, fecha="20260601")
    assert edicion_de(del_reves) == edicion_de(de_una_forma)


def test_dos_paquetes_a_la_misma_fecha_no_dicen_que_edicion_es(tmp_path: Path) -> None:
    """Sin fecha que desempate no hay forma de saber cuál depende de cuál: se para y se dice."""
    empatados = _compuesta(tmp_path / "es", fecha_de_la_extension="20260510")

    with pytest.raises(ReleaseDeSnomedInvalidaError, match="misma fecha"):
        edicion_de(empatados)
