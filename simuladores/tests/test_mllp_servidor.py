"""El extremo que escucha: que recibe el sobre entero y acusa recibo como se espera.

El `ORU^R01` saliente del laboratorio no se puede probar de extremo a extremo sin un HIS que
escuche. Si este receptor se equivoca —parte el mensaje por tamaño, decodifica con el charset que no
es, o compone mal el `ACK`—, el fallo aparece en el motor y se busca donde no está.
"""

from __future__ import annotations

import pytest

from mllp import Destino, Escucha, ServidorDelHis, acuse_de, enviar

ORU = (
    "MSH|^~\\&|HISPALIS|LAB_SEVILLA|HIS_VIRGEN|H_VIRGEN_MACARENA|20260806120000||"
    "ORU^R01^ORU_R01|LAB000123|P|2.5.1|||||ES|8859/1\r"
    "PID|1||70000001^^^HISPALIS^MR||MUÑOZ DE LA TORRE^Begoña\r"
    "OBX|1|NM|2345-7^Glucosa^LN||92|mg/dL|||||F"
)


@pytest.fixture
def his():
    """Un HIS escuchando en un puerto que pide el sistema, para que dos pruebas no se peleen."""
    with ServidorDelHis(Escucha(puerto=0)) as servidor:
        yield servidor


def _destino(servidor: ServidorDelHis) -> Destino:
    return Destino(servidor="localhost", puerto=servidor.puerto, tls=False)


def test_lo_recibido_es_el_mensaje_entero_y_no_un_trozo(his):
    enviar(ORU, _destino(his))

    assert his.esperar(1)[0] == ORU


def test_la_ene_sobrevive_al_viaje(his):
    """El charset se declara en `MSH-18` y el receptor lo respeta: si no, llega «MU?OZ»."""
    enviar(ORU, _destino(his))

    assert "MUÑOZ DE LA TORRE" in his.esperar(1)[0]


def test_el_acuse_devuelve_el_msh_10_del_mensaje_acusado():
    """Sin el `MSH-10` de vuelta, el emisor no sabe qué mensaje le están acusando."""
    acuse = acuse_de(ORU)

    assert acuse.split("\r")[1] == "MSA|AA|LAB000123"


def test_el_acuse_intercambia_emisor_y_receptor():
    campos = acuse_de(ORU).split("\r")[0].split("|")

    assert campos[2] == "HIS_VIRGEN"
    assert campos[3] == "H_VIRGEN_MACARENA"
    assert campos[4] == "HISPALIS"
    assert campos[5] == "LAB_SEVILLA"


def test_el_his_puede_rechazar_para_probar_el_camino_de_error():
    with ServidorDelHis(Escucha(puerto=0), codigo_de_acuse="AE") as servidor:
        respuesta = enviar(ORU, _destino(servidor))

    assert respuesta.split("\r")[1].startswith("MSA|AE|")


def test_dos_mensajes_por_la_misma_conexion_se_reciben_los_dos(his):
    enviar(ORU, _destino(his))
    enviar(ORU.replace("LAB000123", "LAB000124"), _destino(his))

    assert len(his.esperar(2)) == 2


def test_esperar_mas_de_lo_que_llega_avisa_de_cuantos_faltan(his):
    enviar(ORU, _destino(his))

    with pytest.raises(TimeoutError, match="llegaron 1"):
        his.esperar(2, tiempo_max=0.5)


def test_parar_dos_veces_no_es_un_error():
    """El gestor de contexto ya para al salir; una parada explícita antes no puede reventar."""
    servidor = ServidorDelHis(Escucha(puerto=0))
    servidor.arrancar()
    servidor.parar()

    servidor.parar()
