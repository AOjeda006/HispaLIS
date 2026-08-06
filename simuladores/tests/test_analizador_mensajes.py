"""El simulador del analizador: que lo que emite es un `ORU^R01` V2.5.1 correcto, campo a campo.

El canal del motor tiene sus propios tests de extremo a extremo. Estos son los del emisor, y hacen
falta por separado por lo mismo que los del HIS: un arnés que compone mal el mensaje hace fallar el
canal por el motivo equivocado.
"""

from __future__ import annotations

import pytest

from analizador.__main__ import medidas_de
from analizador.mensajes import CATALOGO_LOCAL, CATALOGO_LOINC, Medida, Paciente, oru

PACIENTE = Paciente(nhc="70000001", apellidos="MUÑOZ DE LA TORRE", nombre="Begoña")


def _campos(mensaje: str, segmento: str, posicion: int = 0) -> list[str]:
    encontrados = [linea for linea in mensaje.split("\r") if linea.startswith(f"{segmento}|")]
    if len(encontrados) <= posicion:
        raise AssertionError(f"El mensaje no trae {segmento} nº {posicion}: {mensaje}")
    return encontrados[posicion].split("|")


def test_el_msh_declara_v251_y_la_estructura_de_la_tabla_0354():
    mensaje = oru(
        control_id="AN1",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL")],
    )

    msh = _campos(mensaje, "MSH")
    assert msh[8] == "ORU^R01^ORU_R01"
    assert msh[9] == "AN1"
    assert msh[11] == "2.5.1"


def test_el_analizador_informa_en_loinc_por_defecto():
    """Es lo realista: un analizador comercial no conoce el catálogo de este laboratorio."""
    mensaje = oru(
        control_id="AN2",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL")],
    )

    assert _campos(mensaje, "OBX")[3] == f"2345-7^^{CATALOGO_LOINC}"


def test_se_puede_emitir_en_el_dialecto_local_para_probar_el_otro_camino():
    mensaje = oru(
        control_id="AN3",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("GLU", "92", "mg/dL")],
        catalogo=CATALOGO_LOCAL,
    )

    assert _campos(mensaje, "OBX")[3] == f"GLU^^{CATALOGO_LOCAL}"


def test_el_numero_de_acceso_viaja_en_spm_2_y_en_obr_3():
    """Los dos sitios donde un analizador pone la etiqueta del tubo; el motor mira los dos."""
    mensaje = oru(
        control_id="AN4",
        paciente=PACIENTE,
        numero_de_acceso="ACC77",
        medidas=[Medida("2345-7", "92", "mg/dL")],
    )

    assert _campos(mensaje, "SPM")[2] == "ACC77^ACC77"
    assert _campos(mensaje, "OBR")[3] == "ACC77"


def test_el_apellido_viaja_entero_en_pid_5():
    mensaje = oru(
        control_id="AN5",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL")],
    )

    assert _campos(mensaje, "PID")[5] == "MUÑOZ DE LA TORRE^Begoña"


def test_un_panel_produce_un_obx_por_medida_numerado_en_orden():
    mensaje = oru(
        control_id="AN6",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL"), Medida("2160-0", "0.9", "mg/dL")],
    )

    assert _campos(mensaje, "OBX", 0)[1] == "1"
    assert _campos(mensaje, "OBX", 1)[1] == "2"
    assert _campos(mensaje, "OBX", 1)[5] == "0.9"


def test_obx_11_es_f_y_eso_no_significa_validado():
    """`F` es «final del analizador». La validación es de un facultativo y va por otro camino."""
    mensaje = oru(
        control_id="AN7",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL")],
    )

    assert _campos(mensaje, "OBX")[11] == "F"


def test_el_aparato_va_en_obx_18_y_no_en_obx_16():
    """`OBX-16` es una **persona**. El modelo del analizador ahí lo convierte en facultativo."""
    mensaje = oru(
        control_id="AN9",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL")],
        emisor="AU5800",
    )

    obx = _campos(mensaje, "OBX")
    assert obx[16] == ""
    assert obx[18] == "AU5800"


def test_la_unidad_va_en_obx_6_y_el_tipo_en_obx_2():
    mensaje = oru(
        control_id="AN8",
        paciente=PACIENTE,
        numero_de_acceso="ACC1",
        medidas=[Medida("2345-7", "92", "mg/dL")],
    )

    obx = _campos(mensaje, "OBX")
    assert obx[2] == "NM"
    assert obx[6] == "mg/dL"


@pytest.mark.parametrize(
    ("especificacion", "tipo", "unidad"),
    [("2345-7:92:mg/dL", "NM", "mg/dL"), ("31870-9:Negativo", "ST", "")],
)
def test_una_medida_sin_unidad_se_emite_como_texto(especificacion: str, tipo: str, unidad: str):
    """Sin unidad no hay cifra: es un cultivo o un antígeno, y `OBX-2` tiene que decirlo."""
    medida = medidas_de(especificacion)[0]

    assert medida.tipo == tipo
    assert medida.unidad == unidad
