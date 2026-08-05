"""Generador de datos sintéticos españoles para HispaLIS.

Produce pacientes, peticiones, especímenes, resultados e informes verosímiles para un laboratorio
clínico privado de Sevilla. **Nunca datos reales de pacientes**, en ningún entorno y bajo ningún
concepto: todo lo que sale de aquí es sintético.

La terminología no se escribe aquí, se lee de la guía de implementación (D15): ver
`generador.terminologia`.
"""

from generador.configuracion import (
    ConfiguracionGeneracion,
    ConfiguracionInvalidaError,
    crear_configuracion,
)
from generador.escenario import Corpus, generar
from generador.terminologia import (
    Catalogo,
    Prueba,
    TerminologiaNoDisponibleError,
    cargar_catalogo,
)

__all__ = [
    "Catalogo",
    "ConfiguracionGeneracion",
    "ConfiguracionInvalidaError",
    "Corpus",
    "Prueba",
    "TerminologiaNoDisponibleError",
    "cargar_catalogo",
    "crear_configuracion",
    "generar",
]
