"""El servicio de declaraciones del SVEA, por el lado que recibe."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import pytest

from svea import (
    ENFERMEDADES,
    DeclaracionRechazadaError,
    LibroDeRegistro,
    leer_la_declaracion,
    registrar,
)

AHORA = datetime(2026, 8, 4, 18, 0, tzinfo=UTC)


def _declaracion(
    *,
    identificador: str = "8f1a0e2c-0000-4000-8000-000000000001",
    enfermedad: str = "LEGIONELOSIS",
    vence: datetime | None = None,
    urgente: bool = True,
    extra: dict | None = None,
) -> str:
    """Un `Task` como el que produce el laboratorio, con lo justo para poder registrarlo."""
    tarea: dict = {
        "resourceType": "Task",
        "id": identificador,
        "status": "requested",
        "intent": "order",
        "priority": "stat" if urgente else "routine",
        "code": {"text": "Declaración de enfermedad de declaración obligatoria"},
        "reason": [
            {
                "concept": {
                    "coding": [
                        {
                            "system": ENFERMEDADES,
                            "code": enfermedad,
                            "display": "Legionelosis",
                        }
                    ]
                }
            }
        ],
        "focus": {"reference": "Observation/17"},
        "for": {"reference": "Patient/9"},
        "restriction": {"period": {"end": (vence or AHORA + timedelta(hours=6)).isoformat()}},
    }
    tarea.update(extra or {})
    return json.dumps(tarea)


class TestLoQueSeExige:
    """Lo que un destinatario tiene que comprobar, comprobado desde el lado que recibe."""

    def test_una_declaracion_completa_se_lee(self) -> None:
        declaracion = leer_la_declaracion(_declaracion())

        assert declaracion.enfermedad == "LEGIONELOSIS"
        assert declaracion.urgente
        assert declaracion.caso == "Observation/17"

    def test_sin_plazo_no_se_registra(self) -> None:
        # Una urgente sin priorizar es una ordinaria, y aquí no hay forma de saber cuál es cuál.
        with pytest.raises(DeclaracionRechazadaError) as rechazo:
            leer_la_declaracion(_declaracion(extra={"restriction": {}}))

        assert rechazo.value.codigo_http == 422
        assert "plazo" in str(rechazo.value)

    def test_un_codigo_de_otro_catalogo_no_vale(self) -> None:
        de_otro_sitio = {
            "reason": [
                {"concept": {"coding": [{"system": "http://example.org/otras", "code": "X"}]}}
            ]
        }

        with pytest.raises(DeclaracionRechazadaError) as rechazo:
            leer_la_declaracion(_declaracion(extra=de_otro_sitio))

        assert rechazo.value.codigo_http == 422

    def test_lo_que_no_es_un_task_no_es_una_declaracion(self) -> None:
        with pytest.raises(DeclaracionRechazadaError) as rechazo:
            leer_la_declaracion(json.dumps({"resourceType": "Observation"}))

        assert rechazo.value.codigo_http == 400


class TestNadaDeFiliacion:
    """El invariante 6, exigido por el que recibe. Aquí es donde sale barato romperlo."""

    def test_un_paciente_dentro_se_rechaza(self) -> None:
        con_paciente = {
            "contained": [
                {
                    "resourceType": "Patient",
                    "id": "p1",
                    "name": [{"family": "MUÑOZ ÁLVAREZ", "given": ["Rocío"]}],
                }
            ]
        }

        with pytest.raises(DeclaracionRechazadaError) as rechazo:
            leer_la_declaracion(_declaracion(extra=con_paciente))

        assert rechazo.value.codigo_http == 400
        assert "personas" in str(rechazo.value)

    def test_el_nombre_escrito_en_la_referencia_se_rechaza(self) -> None:
        # Es la fuga fácil: la referencia sigue siendo seudónima y el `display` lleva el nombre.
        con_nombre = {"for": {"reference": "Patient/9", "display": "Rocío MUÑOZ ÁLVAREZ"}}

        with pytest.raises(DeclaracionRechazadaError) as rechazo:
            leer_la_declaracion(_declaracion(extra=con_nombre))

        assert rechazo.value.codigo_http == 400

    def test_una_fecha_de_nacimiento_suelta_se_rechaza(self) -> None:
        with pytest.raises(DeclaracionRechazadaError) as rechazo:
            leer_la_declaracion(_declaracion(extra={"birthDate": "1978-03-04"}))

        assert rechazo.value.codigo_http == 400

    def test_el_display_del_codigo_de_enfermedad_no_es_filiacion(self) -> None:
        # Control: la comprobación tiene que dejar pasar los `display` de terminología, o el
        # laboratorio no podría mandar el nombre de la enfermedad en español.
        leer_la_declaracion(_declaracion())


class TestElRegistro:
    """El acuse, la deduplicación y el plazo."""

    def test_registrar_devuelve_un_numero(self) -> None:
        numero, a_tiempo = registrar(leer_la_declaracion(_declaracion()), LibroDeRegistro(), AHORA)

        assert numero == "SVEA-2026-000001"
        assert a_tiempo

    def test_el_mismo_task_dos_veces_es_una_declaracion(self) -> None:
        # El laboratorio reintenta hasta que hay acuse: tiene que hacerlo. Un destinatario que no
        # deduplica convierte cada reintento en un caso nuevo en la estadística epidemiológica.
        libro = LibroDeRegistro()
        declaracion = leer_la_declaracion(_declaracion())

        primero, _ = registrar(declaracion, libro, AHORA)
        segundo, _ = registrar(declaracion, libro, AHORA)

        assert primero == segundo
        assert len(libro.declaraciones) == 1

    def test_dos_casos_distintos_son_dos_declaraciones(self) -> None:
        libro = LibroDeRegistro()

        registrar(leer_la_declaracion(_declaracion(identificador="a")), libro, AHORA)
        registrar(leer_la_declaracion(_declaracion(identificador="b")), libro, AHORA)

        assert len(libro.declaraciones) == 2
        assert libro.por_tarea["b"] == "SVEA-2026-000002"

    def test_lo_que_llega_tarde_se_registra_igual_y_se_apunta(self) -> None:
        # El plazo no extingue la obligación: la hace tardía. Rechazarla dejaría el caso sin
        # declarar, que es infinitamente peor que declararlo tarde.
        libro = LibroDeRegistro()
        vencida = leer_la_declaracion(_declaracion(vence=AHORA - timedelta(hours=1)))

        numero, a_tiempo = registrar(vencida, libro, AHORA)

        assert not a_tiempo
        assert libro.fuera_de_plazo == [numero]
        assert len(libro.declaraciones) == 1
