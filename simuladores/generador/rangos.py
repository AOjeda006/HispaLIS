"""Los rangos de referencia del laboratorio, leídos del fichero que los publica.

**Este fichero es la fuente única y lo comparten dos componentes.** El backend lo lee en Java para
publicar `Observation.referenceRange`; el generador lo lee aquí para sortear valores verosímiles.

Antes estaban escritos dos veces —en una migración de Flyway y en una tabla de este paquete— y nada
comprobaba que coincidieran. Si divergen, el generador produce resultados que el laboratorio
interpreta de otra manera, y el corpus sigue validando tan campante: los dos ficheros son válidos
por separado, y el fallo solo se ve comparando un valor con el rango que el laboratorio publica.

Es el mismo patrón que la terminología con la guía (D15), con una diferencia que importa: los rangos
**no** van en la IG. Los códigos de prueba son vocabulario compartido con quien hable con este
laboratorio, pero los rangos dependen de su método y de su analizador, y dos laboratorios que usen
el mismo código `CREA` publican rangos distintos sin contradecirse.

Las **unidades** siguen saliendo del catálogo de la guía y no de aquí, aunque el fichero también
las lleve: la unidad en la que se emite una prueba es terminología. Que las dos coincidan lo
comprueba un test del backend contra el FSH.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

#: Permite apuntar a otro fichero sin tocar código.
VARIABLE_ENTORNO = "HISPALIS_RANGOS"

#: Dónde publica el laboratorio sus rangos, relativo a la raíz del repositorio.
RUTA_EN_EL_REPOSITORIO = (
    Path("backend") / "src" / "main" / "resources" / "laboratorio" / "rangos-de-referencia.json"
)

#: Sexos a los que se puede atar un rango. Son los códigos de `Patient.gender` que lo discriminan.
SEXOS = frozenset({"male", "female"})


class RangosNoDisponiblesError(RuntimeError):
    """No se encuentra —o no es coherente— el fichero de rangos del laboratorio."""


@dataclass(frozen=True, slots=True)
class RangoDeReferencia:
    """Los límites entre los que un resultado se considera normal.

    Attributes:
        bajo: Límite inferior.
        alto: Límite superior.
        sexo: Sexo al que aplica el rango, o `None` si aplica a cualquiera.
    """

    bajo: float
    alto: float
    sexo: str | None = None


def cargar_rangos(fichero: Path | str | None = None) -> dict[str, tuple[RangoDeReferencia, ...]]:
    """Lee los rangos de referencia que publica el laboratorio, indexados por código de prueba.

    Args:
        fichero: Dónde está el fichero. Por defecto, el del propio repositorio, o lo que indique la
            variable de entorno `HISPALIS_RANGOS`.

    Returns:
        Los rangos de cada prueba, con el común —si lo hay— por delante de los de sexo.

    Raises:
        RangosNoDisponiblesError: Si falta el fichero o define un rango imposible.
    """
    ruta = Path(fichero) if fichero is not None else _ruta_por_defecto()
    try:
        contenido = json.loads(ruta.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise RangosNoDisponiblesError(
            f"No se encuentra «{ruta}». Los rangos de referencia no se copian aquí: se leen del "
            f"fichero que publica el laboratorio, el mismo que consume el backend. Apunta a otro "
            f"con la variable de entorno {VARIABLE_ENTORNO}."
        ) from error

    por_prueba: dict[str, list[RangoDeReferencia]] = {}
    for fila in contenido["rangos"]:
        prueba = fila["prueba"]
        por_prueba.setdefault(prueba, []).append(_a_rango(prueba, fila))

    return {prueba: _ordenados(prueba, rangos) for prueba, rangos in por_prueba.items()}


def _a_rango(prueba: str, fila: dict) -> RangoDeReferencia:
    sexo = fila.get("sexo")
    if sexo is not None and sexo not in SEXOS:
        raise RangosNoDisponiblesError(
            f"«{sexo}» no es un sexo al que atar el rango de {prueba}: "
            f"{' o '.join(sorted(SEXOS))} o nada."
        )
    if fila["bajo"] > fila["alto"]:
        raise RangosNoDisponiblesError(
            f"El rango de {prueba} tiene el límite inferior por encima del superior "
            f"({fila['bajo']} > {fila['alto']}); con él, todo resultado saldría alterado."
        )
    return RangoDeReferencia(bajo=fila["bajo"], alto=fila["alto"], sexo=sexo)


def _ordenados(prueba: str, rangos: list[RangoDeReferencia]) -> tuple[RangoDeReferencia, ...]:
    # Dos rangos que aplican al mismo paciente harían ambiguo cuál se usa, y `rango_aplicable` se
    # quedaría con el primero sin que nadie lo notara.
    sexos = [rango.sexo for rango in rangos]
    if len(set(sexos)) != len(sexos):
        raise RangosNoDisponiblesError(
            f"El fichero define dos veces el mismo rango de {prueba}. Con dos rangos para el mismo "
            f"paciente no se sabe con cuál interpretar el resultado."
        )
    # El común primero: `rango_aplicable` devuelve el primero que encaje, y si un rango de sexo se
    # adelantase al común se le aplicaría a quien no le toca.
    return tuple(sorted(rangos, key=lambda rango: (rango.sexo is not None, rango.sexo or "")))


def _ruta_por_defecto() -> Path:
    indicado = os.environ.get(VARIABLE_ENTORNO)
    if indicado:
        return Path(indicado)
    # `generador/rangos.py` → `generador/` → `simuladores/` → la raíz.
    return Path(__file__).resolve().parents[2] / RUTA_EN_EL_REPOSITORIO
