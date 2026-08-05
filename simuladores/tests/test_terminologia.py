"""Pruebas de la terminología leída de la guía.

Lo que se comprueba aquí no es que el código funcione, sino que **la fuente sea la correcta**: que
el catálogo salga de los artefactos que publica la IG y no de una lista escrita en Python. Es el
invariante D15, y su forma de romperse es silenciosa —todo sigue pasando, con el catálogo de hace
tres meses—, así que el test tiene que mirar la procedencia y no solo el resultado.
"""

from __future__ import annotations

import pytest

from generador.terminologia import (
    UNIDADES_IMPRESAS,
    TerminologiaNoDisponibleError,
    cargar_catalogo,
    cargar_tipos_de_muestra,
)


def test_el_catalogo_sale_de_los_artefactos_de_la_guia() -> None:
    catalogo = cargar_catalogo()

    assert catalogo.system.endswith("/CodeSystem/catalogo-pruebas")
    assert len(catalogo) >= 20, "el catálogo de la guía tiene 21 pruebas; esto lee bastantes menos"


def test_una_prueba_trae_su_unidad_y_su_loinc() -> None:
    glucosa = cargar_catalogo()["GLU"]

    assert glucosa.display == "Glucosa"
    assert glucosa.unidad_ucum == "mg/dL"
    assert glucosa.es_cuantitativa
    # El LOINC no se escribe en el generador: lo resuelve el `ConceptMap` de la guía.
    assert glucosa.loinc == "2345-7"


def test_una_prueba_sin_unidad_es_cualitativa() -> None:
    legionella = cargar_catalogo()["LEGIOAG"]

    assert legionella.unidad_ucum is None
    assert not legionella.es_cuantitativa
    assert legionella.unidad_impresa is None


def test_las_cuantitativas_y_las_cualitativas_suman_el_catalogo_entero() -> None:
    catalogo = cargar_catalogo()

    assert len(catalogo.cuantitativas) + len(catalogo.cualitativas) == len(catalogo)


def test_un_codigo_que_no_esta_en_el_catalogo_es_un_error() -> None:
    with pytest.raises(KeyError, match="INVENTADO"):
        cargar_catalogo()["INVENTADO"]


def test_la_unidad_impresa_cae_en_el_codigo_ucum_cuando_no_hay_forma_castellana() -> None:
    catalogo = cargar_catalogo()

    assert catalogo["GLU"].unidad_impresa == "mg/dL"
    assert catalogo["TSH"].unidad_impresa == "µUI/mL"


def test_toda_unidad_con_forma_impresa_existe_en_el_catalogo() -> None:
    # Guarda contra la deriva: si una prueba cambia de unidad en la guía, la forma impresa que se
    # quedó aquí deja de aplicarse a nada y hay que borrarla. Sin este test, se queda para siempre.
    unidades_del_catalogo = {
        prueba.unidad_ucum for prueba in cargar_catalogo().cuantitativas if prueba.unidad_ucum
    }

    assert set(UNIDADES_IMPRESAS) <= unidades_del_catalogo


def test_los_tipos_de_muestra_salen_del_valueset_de_la_guia() -> None:
    tipos = cargar_tipos_de_muestra()

    assert len(tipos) == 10
    assert "122555007" in tipos, "sangre venosa, que es de la que sale casi todo"


def test_sin_los_artefactos_falla_diciendo_que_hay_que_ejecutar_sushi(tmp_path) -> None:
    # Si esto devolviera un catálogo vacío en vez de fallar, el generador produciría un corpus sin
    # una sola prueba y nadie se enteraría hasta mirarlo.
    with pytest.raises(TerminologiaNoDisponibleError, match="fsh-sushi"):
        cargar_catalogo(tmp_path)
