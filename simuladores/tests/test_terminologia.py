"""Pruebas de la terminología preguntada al servidor.

Lo que se comprueba aquí no es que el código funcione, sino que **la fuente sea la correcta**: que
el catálogo salga de la misma autoridad que usan el backend y el motor y no de una lista escrita en
Python. Es el invariante D15, y su forma de romperse es silenciosa —todo sigue pasando, con el
catálogo de hace tres meses—, así que el test tiene que mirar la procedencia y no solo el resultado.

El servidor contra el que corren estos tests lo levanta `conftest.py` y responde las cuatro
operaciones estándar. Que sea uno de prueba no cambia lo que se prueba: el generador habla FHIR por
HTTP y no abre un solo fichero.
"""

from __future__ import annotations

import pytest

from generador.terminologia import (
    UNIDADES_IMPRESAS,
    TerminologiaNoDisponibleError,
    admite_tipo_de_muestra,
    cargar_catalogo,
    cargar_tipos_de_muestra,
)


def test_el_catalogo_sale_del_servidor_de_terminologia() -> None:
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


def test_un_tipo_de_muestra_se_puede_comprobar_de_uno_en_uno() -> None:
    # `$validate-code` en vez de traerse la expansión: es lo que hay que hacer con un conjunto que
    # algún día se defina por filtro, y no obliga al servidor a expandir nada.
    assert admite_tipo_de_muestra("122555007")
    assert not admite_tipo_de_muestra("000000")


def test_el_display_de_una_prueba_llega_en_espanol_y_el_de_loinc_en_ingles() -> None:
    # No es un detalle de presentación: un informe español con «Glucose [Mass/volume] in Serum or
    # Plasma» donde debería decir «Glucosa» es un fallo de producto (D7). Y el de LOINC va en inglés
    # porque su licencia prohíbe alterar el contenido del campo.
    glucosa = cargar_catalogo()["GLU"]

    assert glucosa.display == "Glucosa"
    assert glucosa.loinc_display == "Glucose [Mass/volume] in Serum or Plasma"


def test_sin_servidor_falla_diciendo_como_levantarlo() -> None:
    # Si esto devolviera un catálogo vacío en vez de fallar, el generador produciría un corpus sin
    # una sola prueba y nadie se enteraría hasta mirarlo. El puerto es uno que nadie escucha.
    with pytest.raises(TerminologiaNoDisponibleError, match="docker compose"):
        cargar_catalogo("http://127.0.0.1:1/fhir")
