"""Generador de datos sintéticos españoles para HispaLIS.

Produce pacientes, peticiones, especímenes y resultados verosímiles para un laboratorio clínico
privado de Sevilla. **Nunca datos reales de pacientes**, en ningún entorno y bajo ningún concepto:
todo lo que sale de aquí es sintético.
"""

from generador.configuracion import (
    ConfiguracionGeneracion,
    ConfiguracionInvalidaError,
    crear_configuracion,
)

__all__ = [
    "ConfiguracionGeneracion",
    "ConfiguracionInvalidaError",
    "crear_configuracion",
]
