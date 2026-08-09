"""El receptor de notificaciones, por el lado que escucha."""

from __future__ import annotations

import json

import pytest

from receptor import (
    CABECERA_FIRMA,
    CABECERA_MOMENTO,
    NotificacionRechazadaError,
    Recibidas,
    comprobar_la_firma,
    firma_esperada,
    procesar,
)

SECRETO = "un-secreto-de-pruebas"


def _cabeceras(cuerpo: str, momento: str = "1000", secreto: str = SECRETO) -> dict[str, str]:
    return {
        CABECERA_MOMENTO: momento,
        CABECERA_FIRMA: "his-2026=sha256:" + firma_esperada(secreto, momento, cuerpo),
    }


def _notificacion(*, eventos: list[int], con_recurso_dentro: bool = False) -> str:
    entradas: list[dict] = [
        {
            "fullUrl": "urn:uuid:1",
            "resource": {
                "resourceType": "SubscriptionStatus",
                "status": "active",
                "type": "event-notification",
                "notificationEvent": [
                    {"eventNumber": numero, "focus": {"reference": f"Observation/{numero}"}}
                    for numero in eventos
                ],
            },
        }
    ]
    for numero in eventos:
        entrada: dict = {
            "fullUrl": f"https://laboratorio.example/fhir/Observation/{numero}",
            "request": {"method": "GET", "url": f"Observation/{numero}"},
        }
        if con_recurso_dentro:
            entrada["resource"] = {"resourceType": "Observation", "valueQuantity": {"value": 7.5}}
        entradas.append(entrada)
    return json.dumps(
        {"resourceType": "Bundle", "type": "subscription-notification", "entry": entradas}
    )


class TestLaFirma:
    """Sin firma no se acepta nada: aceptar sin firmar es aceptar de cualquiera."""

    def test_una_notificacion_firmada_pasa(self) -> None:
        cuerpo = _notificacion(eventos=[1])

        comprobar_la_firma(SECRETO, _cabeceras(cuerpo), cuerpo, ahora=1000)

    def test_sin_firma_se_rechaza(self) -> None:
        with pytest.raises(NotificacionRechazadaError) as rechazo:
            comprobar_la_firma(SECRETO, {}, "{}", ahora=1000)

        assert rechazo.value.codigo_http == 401

    def test_con_otra_clave_no_cuadra(self) -> None:
        cuerpo = _notificacion(eventos=[1])

        with pytest.raises(NotificacionRechazadaError):
            comprobar_la_firma(SECRETO, _cabeceras(cuerpo, secreto="otra"), cuerpo, ahora=1000)

    def test_el_cuerpo_manipulado_no_cuadra(self) -> None:
        cabeceras = _cabeceras(_notificacion(eventos=[1]))

        with pytest.raises(NotificacionRechazadaError):
            comprobar_la_firma(SECRETO, cabeceras, _notificacion(eventos=[2]), ahora=1000)

    def test_una_notificacion_de_ayer_se_trata_como_reenvio(self) -> None:
        # La marca de tiempo va dentro de lo firmado justamente para esto: sin ella, una
        # notificación capturada vale para siempre.
        cuerpo = _notificacion(eventos=[1])

        with pytest.raises(NotificacionRechazadaError):
            comprobar_la_firma(SECRETO, _cabeceras(cuerpo), cuerpo, ahora=100_000)


class TestLaCarga:
    """`id-only` se comprueba también en el que recibe, no solo en el que envía."""

    def test_se_leen_las_referencias(self) -> None:
        visto = Recibidas()

        referencias = procesar(_notificacion(eventos=[1, 2]), visto)

        assert len(referencias) == 2
        assert visto.ultimo_evento == 2

    def test_un_recurso_dentro_se_rechaza(self) -> None:
        with pytest.raises(NotificacionRechazadaError) as rechazo:
            procesar(_notificacion(eventos=[1], con_recurso_dentro=True), Recibidas())

        assert rechazo.value.codigo_http == 400
        assert "historia clínica" in str(rechazo.value)

    def test_sin_subscriptionstatus_delante_no_es_una_notificacion(self) -> None:
        sin_estado = json.dumps(
            {
                "resourceType": "Bundle",
                "type": "subscription-notification",
                "entry": [{"fullUrl": "urn:uuid:1", "resource": {"resourceType": "Observation"}}],
            }
        )

        with pytest.raises(NotificacionRechazadaError):
            procesar(sin_estado, Recibidas())


class TestLosHuecos:
    """Para esto sirve `eventNumber`: para enterarse de lo que NO llegó."""

    def test_una_secuencia_seguida_no_tiene_huecos(self) -> None:
        visto = Recibidas()

        procesar(_notificacion(eventos=[1]), visto)
        procesar(_notificacion(eventos=[2]), visto)

        assert visto.huecos == []

    def test_lo_que_no_llego_se_detecta_sin_preguntar_nada(self) -> None:
        visto = Recibidas()

        procesar(_notificacion(eventos=[1]), visto)
        procesar(_notificacion(eventos=[5]), visto)

        assert visto.huecos == [(2, 4)]
