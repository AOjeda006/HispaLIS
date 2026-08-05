"""Pruebas de los identificadores españoles sintéticos.

Un DNI con la letra mal es un dato que *parece* bueno: pasa el ojo y pasa el perfil —D16 prohíbe
validar el formato de lo que el laboratorio no emite—, y revienta el día que alguien lo valide de
verdad. Por eso se comprueba la letra sobre la cadena ya escrita y no sobre el cálculo intermedio.
"""

from __future__ import annotations

import random
import re

import pytest

from generador.identificadores import (
    dni,
    dni_o_nie,
    es_documento_valido,
    nhc,
    nie,
    nuhsa,
    numero_de_acceso,
    numero_de_peticion,
)

MUCHOS = 500


def azar() -> random.Random:
    return random.Random(42)


def test_todos_los_dni_generados_llevan_su_letra_correcta() -> None:
    fuente = azar()

    assert all(es_documento_valido(dni(fuente)) for _ in range(MUCHOS))


def test_todos_los_nie_generados_llevan_su_letra_correcta() -> None:
    fuente = azar()

    assert all(es_documento_valido(nie(fuente)) for _ in range(MUCHOS))


def test_el_nie_se_genera_con_las_tres_iniciales_y_todas_validan() -> None:
    fuente = azar()
    generados = [nie(fuente) for _ in range(MUCHOS)]

    # Las tres tienen que salir: la letra de control se calcula sustituyendo la inicial por su
    # dígito, y quien se salte ese paso acierta con las `X` y falla con las `Y` y las `Z`. Un test
    # que solo viera `X` daría el visto bueno a un generador roto en dos tercios de los casos.
    assert {documento[0] for documento in generados} == {"X", "Y", "Z"}


def test_una_letra_cambiada_se_detecta() -> None:
    correcto = dni(azar())
    distinta = "T" if correcto[-1] != "T" else "R"

    assert not es_documento_valido(correcto[:-1] + distinta)


@pytest.mark.parametrize("basura", ["", "12345678", "1234567890A", "ABCDEFGHZ"])
def test_lo_que_no_es_un_documento_no_valida(basura: str) -> None:
    assert not es_documento_valido(basura)


def test_el_documento_de_un_paciente_es_dni_o_nie_y_los_dos_salen() -> None:
    fuente = azar()
    generados = [dni_o_nie(fuente) for _ in range(MUCHOS)]

    assert all(es_documento_valido(documento) for documento in generados)
    assert any(documento[0] in "XYZ" for documento in generados), "el corpus no trae ningún NIE"
    assert any(documento[0].isdigit() for documento in generados)


def test_el_nuhsa_es_an_mas_diez_digitos() -> None:
    fuente = azar()

    assert all(re.fullmatch(r"AN\d{10}", nuhsa(fuente)) for _ in range(MUCHOS))


def test_el_nhc_son_exactamente_ocho_digitos() -> None:
    # Es el invariante `hlis-nhc-1` del perfil: el único identificador que emite el laboratorio y el
    # único con formato validado.
    assert nhc(1) == "00000001"
    assert re.fullmatch(r"\d{8}", nhc(99_999_999))


def test_un_nhc_que_no_cabe_en_ocho_digitos_es_un_error() -> None:
    with pytest.raises(ValueError, match="8 dígitos"):
        nhc(100_000_000)


def test_el_numero_de_peticion_y_el_de_acceso_llevan_el_anio() -> None:
    assert numero_de_peticion(2026, 4512) == "P-2026-004512"
    assert numero_de_acceso(2026, 198437) == "26-0198437"
