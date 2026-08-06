"""El simulador del HIS: que lo que emite es un `ADT` V2.5.1 correcto, campo a campo.

El canal del motor tiene sus propios tests de extremo a extremo. Estos son los del emisor, y hacen
falta por separado: un arnés que compone mal el mensaje hace fallar el canal por el motivo
equivocado, y se pierde media tarde buscando el fallo donde no está.
"""

from __future__ import annotations

import pytest

from his.mensajes import CHARSET_LATIN1, CHARSET_UTF8, Paciente, adt
from his.mllp import CharsetNoSoportadoError, _codificacion_de

MUNOZ = "MUÑOZ DE LA TORRE"
FERNANDEZ = "FERNÁNDEZ DE CÓRDOBA RUIZ"
PENA = "PEÑA ÁLVAREZ"


def _paciente(apellidos: str = MUNOZ) -> Paciente:
    return Paciente(
        nhc="70000001",
        apellidos=apellidos,
        nombre="Begoña",
        segundo_nombre="María",
        dni="12345678Z",
        nuhsa="AN0123456789",
    )


def _campos(mensaje: str, segmento: str) -> list[str]:
    for linea in mensaje.split("\r"):
        if linea.startswith(f"{segmento}|"):
            return linea.split("|")
    raise AssertionError(f"El mensaje no trae segmento {segmento}: {mensaje}")


def test_el_msh_declara_v251_y_su_charset():
    mensaje = adt(evento="A01", control_id="MSG1", paciente=_paciente())

    msh = _campos(mensaje, "MSH")
    assert msh[8] == "ADT^A01^ADT_A01"
    assert msh[9] == "MSG1"
    assert msh[11] == "2.5.1"
    assert msh[17] == CHARSET_LATIN1


def test_el_a08_usa_la_misma_estructura_que_el_a01():
    """`ADT_A08` no existe en la tabla 0354. Ver `docs/adr/adr-0018-…`."""
    mensaje = adt(evento="A08", control_id="MSG2", paciente=_paciente())

    assert _campos(mensaje, "MSH")[8] == "ADT^A08^ADT_A01"


@pytest.mark.parametrize("apellidos", [MUNOZ, FERNANDEZ, PENA])
def test_el_apellido_viaja_entero_en_pid_5(apellidos: str):
    """Sin trocear: «de la Torre Gómez» son dos apellidos, y partirlos confunde pacientes."""
    mensaje = adt(evento="A01", control_id="MSG3", paciente=_paciente(apellidos))

    assert _campos(mensaje, "PID")[5] == f"{apellidos}^Begoña^María"


def test_cada_identificador_lleva_su_tipo_de_la_tabla_0203():
    mensaje = adt(evento="A01", control_id="MSG4", paciente=_paciente())

    assert _campos(mensaje, "PID")[3] == (
        "70000001^^^HISPALIS^MR~12345678Z^^^MJU^NI~AN0123456789^^^SAS^JHN"
    )


def test_un_paciente_sin_nuhsa_no_manda_el_campo_vacio():
    """En un laboratorio privado el NUHSA falta a diario, y ausente no es lo mismo que vacío."""
    sin_nuhsa = Paciente(nhc="70000002", apellidos=PENA, nombre="Rocío", dni="12345678Z")

    mensaje = adt(evento="A01", control_id="MSG5", paciente=sin_nuhsa)

    assert "JHN" not in _campos(mensaje, "PID")[3]


def test_el_episodio_va_en_pv1_19():
    mensaje = adt(evento="A01", control_id="MSG6", paciente=_paciente(), episodio="EP20260806001")

    assert _campos(mensaje, "PV1")[19] == "EP20260806001"


def test_sin_episodio_no_hay_pv1():
    mensaje = adt(evento="A01", control_id="MSG7", paciente=_paciente())

    assert "PV1|" not in mensaje


@pytest.mark.parametrize(
    ("charset", "esperada"), [(CHARSET_LATIN1, "iso-8859-1"), (CHARSET_UTF8, "utf-8")]
)
def test_el_cable_se_codifica_con_lo_que_declara_msh_18(charset: str, esperada: str):
    mensaje = adt(evento="A01", control_id="MSG8", paciente=_paciente(), charset=charset)

    assert _codificacion_de(mensaje) == esperada


def test_un_charset_que_no_sabemos_codificar_se_avisa_antes_de_enviar():
    mensaje = adt(evento="A01", control_id="MSG9", paciente=_paciente()).replace(
        f"|{CHARSET_LATIN1}", "|8859/8"
    )

    with pytest.raises(CharsetNoSoportadoError, match="8859/8"):
        _codificacion_de(mensaje)
