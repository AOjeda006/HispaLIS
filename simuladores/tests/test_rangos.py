"""Pruebas del fichero de rangos que el generador comparte con el backend.

El fichero es la fuente única de los dos, así que ya no pueden divergir en su contenido. Lo que sí
puede romperse es el **acuerdo sobre dónde está**: el fichero vive en el árbol del backend, que es
quien lo publica, y una reorganización de recursos allí dejaría al generador sin rangos. Con el
`RANGOS` de antes eso no pasaba, pero a cambio los números se desviaban en silencio; el primer test
es lo que cambia un fallo silencioso por uno ruidoso.
"""

from __future__ import annotations

import json

import pytest

from generador.rangos import (
    RUTA_EN_EL_REPOSITORIO,
    RangosNoDisponiblesError,
    _ruta_por_defecto,
    cargar_rangos,
)


def test_el_fichero_esta_donde_el_generador_lo_busca() -> None:
    ruta = _ruta_por_defecto()

    assert ruta.is_file(), (
        f"no está «{ruta}». Los rangos los publica el backend en {RUTA_EN_EL_REPOSITORIO} y el "
        f"generador los lee de ahí: si se han movido, hay que moverlos en los dos sitios."
    )


def test_se_cargan_los_rangos_que_publica_el_laboratorio() -> None:
    rangos = cargar_rangos()

    # Uno común y dos por sexo: el caso que de verdad hay que sostener, porque una hemoglobina de
    # 13 g/dL es normal en una mujer y baja en un hombre.
    assert [rango.sexo for rango in rangos["GLU"]] == [None]
    assert [rango.sexo for rango in rangos["HB"]] == ["female", "male"]


def test_el_rango_comun_va_delante_de_los_de_sexo(tmp_path) -> None:
    # `rango_aplicable` devuelve el primero que encaja. Si un rango de sexo se adelantase al común,
    # se le aplicaría a quien no le toca y no lo notaría nadie.
    fichero = tmp_path / "rangos.json"
    fichero.write_text(
        json.dumps(
            {
                "rangos": [
                    {"prueba": "X", "bajo": 1, "alto": 2, "unidad": "g/dL", "sexo": "male"},
                    {"prueba": "X", "bajo": 0, "alto": 3, "unidad": "g/dL"},
                ]
            }
        ),
        encoding="utf-8",
    )

    assert [rango.sexo for rango in cargar_rangos(fichero)["X"]] == [None, "male"]


def test_dos_rangos_para_el_mismo_paciente_se_rechazan(tmp_path) -> None:
    fichero = tmp_path / "rangos.json"
    fichero.write_text(
        json.dumps(
            {
                "rangos": [
                    {"prueba": "GLU", "bajo": 70, "alto": 100, "unidad": "mg/dL"},
                    {"prueba": "GLU", "bajo": 80, "alto": 110, "unidad": "mg/dL"},
                ]
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(RangosNoDisponiblesError, match="GLU"):
        cargar_rangos(fichero)


def test_un_rango_con_los_limites_al_reves_se_rechaza(tmp_path) -> None:
    # Sortear dentro de un rango invertido no falla: `uniform(100, 70)` devuelve un número tan
    # tranquilo. Lo que sale es un corpus entero de valores marcados como alterados.
    fichero = tmp_path / "rangos.json"
    fichero.write_text(
        json.dumps({"rangos": [{"prueba": "GLU", "bajo": 100, "alto": 70, "unidad": "mg/dL"}]}),
        encoding="utf-8",
    )

    with pytest.raises(RangosNoDisponiblesError):
        cargar_rangos(fichero)


def test_un_sexo_que_no_es_un_sexo_se_rechaza(tmp_path) -> None:
    fichero = tmp_path / "rangos.json"
    fichero.write_text(
        json.dumps(
            {
                "rangos": [
                    {"prueba": "HB", "bajo": 12, "alto": 16, "unidad": "g/dL", "sexo": "mujer"}
                ]
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(RangosNoDisponiblesError, match="mujer"):
        cargar_rangos(fichero)


def test_si_falta_el_fichero_se_dice_donde_estaba_y_no_se_arranca(tmp_path) -> None:
    with pytest.raises(RangosNoDisponiblesError, match="HISPALIS_RANGOS"):
        cargar_rangos(tmp_path / "no-existe.json")
