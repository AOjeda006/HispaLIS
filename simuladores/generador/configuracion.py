"""Configuración de una ejecución del generador de datos sintéticos.

La reproducibilidad es un requisito, no una comodidad: el generador hace doble función como juego de
datos de prueba y como arnés de carga, y un arnés cuya salida cambia entre ejecuciones no sirve para
comparar nada. Por eso la configuración es inmutable y la semilla es obligatoria.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

SEMILLA_POR_DEFECTO = 42
PACIENTES_POR_DEFECTO = 100

#: Cota superior de pacientes por ejecución. No es una limitación técnica: es una salvaguarda contra
#: el error de tecleo que llena el disco al pedir un millón de pacientes en vez de mil.
PACIENTES_MAXIMO = 100_000


class ConfiguracionInvalidaError(ValueError):
    """Los parámetros de la ejecución no describen una generación posible."""


@dataclass(frozen=True, slots=True)
class ConfiguracionGeneracion:
    """Parámetros de una ejecución del generador.

    Attributes:
        semilla: Raíz del generador aleatorio. Dos ejecuciones con la misma semilla y los mismos
            parámetros producen exactamente la misma salida.
        pacientes: Número de pacientes sintéticos a generar.
        salida: Directorio donde se escriben los recursos generados.
    """

    semilla: int
    pacientes: int
    salida: Path

    def __post_init__(self) -> None:
        if self.pacientes < 1:
            raise ConfiguracionInvalidaError(
                f"Hay que generar al menos un paciente; se pidieron {self.pacientes}."
            )
        if self.pacientes > PACIENTES_MAXIMO:
            raise ConfiguracionInvalidaError(
                f"Se pidieron {self.pacientes} pacientes y el máximo por ejecución es "
                f"{PACIENTES_MAXIMO}."
            )


def crear_configuracion(
    semilla: int = SEMILLA_POR_DEFECTO,
    pacientes: int = PACIENTES_POR_DEFECTO,
    salida: Path | str = Path("salida-generador"),
) -> ConfiguracionGeneracion:
    """Construye la configuración de una ejecución, validando los parámetros.

    Args:
        semilla: Raíz del generador aleatorio.
        pacientes: Número de pacientes a generar.
        salida: Directorio de salida.

    Returns:
        La configuración inmutable de la ejecución.

    Raises:
        ConfiguracionInvalidaError: Si el número de pacientes está fuera del rango admitido.
    """
    return ConfiguracionGeneracion(
        semilla=semilla,
        pacientes=pacientes,
        salida=Path(salida),
    )
