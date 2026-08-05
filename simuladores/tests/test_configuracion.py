"""Pruebas de la configuración del generador."""

from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from generador import ConfiguracionInvalidaError, crear_configuracion
from generador.__main__ import main, resolver_configuracion


def test_la_configuracion_por_defecto_usa_la_semilla_42() -> None:
    configuracion = crear_configuracion()

    assert configuracion.semilla == 42
    assert configuracion.pacientes == 100


def test_la_configuracion_es_inmutable() -> None:
    configuracion = crear_configuracion()

    with pytest.raises(AttributeError):
        configuracion.semilla = 7  # type: ignore[misc]


def test_pedir_cero_pacientes_es_un_error() -> None:
    with pytest.raises(ConfiguracionInvalidaError, match="al menos un paciente"):
        crear_configuracion(pacientes=0)


def test_pedir_mas_pacientes_del_maximo_es_un_error() -> None:
    with pytest.raises(ConfiguracionInvalidaError, match="máximo por ejecución"):
        crear_configuracion(pacientes=100_001)


def test_los_argumentos_de_linea_de_ordenes_se_traducen_a_la_configuracion() -> None:
    configuracion = resolver_configuracion(
        ["--seed", "7", "--pacientes", "3", "--salida", "/tmp/prueba"]
    )

    assert configuracion.semilla == 7
    assert configuracion.pacientes == 3
    assert configuracion.salida == Path("/tmp/prueba")


def test_la_configuracion_por_defecto_toma_la_fecha_de_hoy() -> None:
    assert crear_configuracion().fecha == date.today()


def test_la_fecha_se_puede_fijar_para_que_la_salida_sea_reproducible_entre_dias() -> None:
    assert crear_configuracion(fecha="2026-08-05").fecha == date(2026, 8, 5)


def test_una_fecha_que_no_es_una_fecha_es_un_error() -> None:
    with pytest.raises(ConfiguracionInvalidaError, match="no es una fecha"):
        crear_configuracion(fecha="el martes")


def test_la_orden_termina_bien_con_los_valores_por_defecto(tmp_path: Path) -> None:
    # Se le da destino aunque el test no lo mire: sin él, la orden escribiría cien pacientes en el
    # directorio de trabajo cada vez que alguien corre los tests.
    assert main(["--salida", str(tmp_path)]) == 0


def test_una_configuracion_invalida_termina_con_codigo_de_error() -> None:
    assert main(["--pacientes", "0"]) == 2
