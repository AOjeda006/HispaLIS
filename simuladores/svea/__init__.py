"""Servicio de declaraciones del SVEA: Salud Pública, por el lado que recibe.

El SVEA —Sistema de Vigilancia Epidemiológica de Andalucía— es el destinatario real de una
declaración EDO, y este módulo es una simulación suya. **Verosímil, no fiel**, y conviene que
quede escrito de una vez en vez de descubrirse leyendo el código:

* el contrato real de Redalerta **no es público**, así que el formato que se acepta aquí es el
  `Task` que publica la propia guía, no el suyo;
* una declaración de verdad **lleva filiación** —Salud Pública tiene que poder localizar al caso
  para la encuesta epidemiológica— y **esta no**, porque el destinatario es simulado y este
  proyecto no manda datos de persona a ningún sistema externo;
* el número de registro se lo inventa este servicio, con un formato que se le parece.

Lo que sí es fiel es **lo que un destinatario tiene que comprobar**, y por eso está aquí y no como
un doble dentro del backend: un contrato tiene dos lados y solo se demuestra desde los dos.

* Que la declaración **no trae filiación**. Es el invariante 6 comprobado desde el lado que menos
  se sospecha: aquí sale barato romperlo, porque el destinatario «necesita saber».
* Que trae **plazo**. Una declaración sin fecha límite no se puede priorizar, y una urgente sin
  priorizar es una ordinaria.
* Que **la misma declaración dos veces es una sola**. El laboratorio reintenta cuando no hay acuse
  —tiene que hacerlo—, y un destinatario que no deduplica convierte cada reintento en un caso nuevo
  en la estadística epidemiológica.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime

#: El `system` del catálogo de enfermedades de la guía. Un código de otro sitio no se acepta: este
#: servicio no sabe traducirlo y darlo por bueno registraría un caso de una enfermedad desconocida.
ENFERMEDADES = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/enfermedades-edo"

#: Los tipos de recurso cuyo `Reference.display` sería un nombre de persona. Ver `_sin_filiacion`.
REFERENCIAS_A_PERSONAS = ("Patient/", "Practitioner/", "RelatedPerson/", "Person/")

#: Claves que en FHIR solo aparecen colgando de una persona. Si alguna llega, llega filiación.
CLAVES_DE_FILIACION = ("name", "birthDate", "address", "telecom", "photo", "gender")


class DeclaracionRechazadaError(Exception):
    """La declaración no se acepta, y el código importa: el laboratorio lo distingue.

    Un `4xx` es Salud Pública diciendo que no —una respuesta, que no se reintenta— y un `5xx` es
    Salud Pública sin poder atender —una avería, que sí—. Contestar lo que no es hace que el
    laboratorio reintente para siempre algo que nunca van a aceptar, o que dé por perdida una
    declaración que solo pilló el servicio caído.
    """

    def __init__(self, codigo_http: int, motivo: str) -> None:
        super().__init__(motivo)
        self.codigo_http = codigo_http


@dataclass(frozen=True)
class Declaracion:
    """Lo que este servicio se queda de una declaración: hechos y referencias, nada más."""

    tarea: str
    enfermedad: str
    urgente: bool
    vencimiento: datetime
    caso: str


@dataclass
class LibroDeRegistro:
    """Las declaraciones registradas, con su número.

    `por_tarea` es lo que hace la deduplicación posible: el laboratorio reintenta hasta que hay
    acuse, así que el mismo `Task` llega varias veces y tiene que salir **el mismo** número.
    """

    secuencia: int = 0
    por_tarea: dict[str, str] = field(default_factory=dict)
    declaraciones: list[Declaracion] = field(default_factory=list)
    fuera_de_plazo: list[str] = field(default_factory=list)

    def numero_de(self, tarea: str, ahora: datetime) -> tuple[str, bool]:
        """Devuelve el número de registro de una declaración y si es la primera vez que se ve."""
        ya_estaba = self.por_tarea.get(tarea)
        if ya_estaba is not None:
            return ya_estaba, False
        self.secuencia += 1
        numero = f"SVEA-{ahora.year}-{self.secuencia:06d}"
        self.por_tarea[tarea] = numero
        return numero, True


def leer_la_declaracion(cuerpo: str) -> Declaracion:
    """Comprueba una declaración entrante y devuelve lo que este servicio se queda de ella.

    Raises:
        DeclaracionRechazadaError: `400` si no es un `Task` legible o si trae filiación; `422` si
            le falta algo que hace falta para registrarla —enfermedad conocida o plazo—. La
            diferencia no es cosmética: `400` es «esto está mal formado o no debería haber salido
            de ahí» y `422` es «está bien escrito y aun así no lo puedo registrar».
    """
    try:
        tarea = json.loads(cuerpo)
    except json.JSONDecodeError as ilegible:
        raise DeclaracionRechazadaError(400, "El cuerpo no es JSON.") from ilegible

    if tarea.get("resourceType") != "Task":
        raise DeclaracionRechazadaError(
            400, f"Una declaración es un Task, no un {tarea.get('resourceType')}."
        )

    _sin_filiacion(tarea)

    identificador = tarea.get("id")
    if not identificador:
        # Sin id no hay deduplicación posible, y sin deduplicación cada reintento del laboratorio
        # sería un caso nuevo en la estadística.
        raise DeclaracionRechazadaError(422, "La declaración no trae identificador.")

    return Declaracion(
        tarea=str(identificador),
        enfermedad=_enfermedad(tarea),
        urgente=tarea.get("priority") == "stat",
        vencimiento=_vencimiento(tarea),
        caso=((tarea.get("focus") or {}).get("reference") or "sin referencia al caso"),
    )


def registrar(
    declaracion: Declaracion, libro: LibroDeRegistro, ahora: datetime | None = None
) -> tuple[str, bool]:
    """Anota la declaración y devuelve su número de registro y si llegó dentro de plazo.

    Fuera de plazo **se registra igual**. El plazo no extingue la obligación: lo que hace es
    convertir la declaración en tardía, y eso se apunta —a alguien le tocará explicarlo— pero no
    se rechaza. Rechazarla dejaría el caso sin declarar, que es infinitamente peor.
    """
    momento = ahora or datetime.now(UTC)
    numero, primera_vez = libro.numero_de(declaracion.tarea, momento)
    a_tiempo = momento <= declaracion.vencimiento

    if primera_vez:
        libro.declaraciones.append(declaracion)
        if not a_tiempo:
            libro.fuera_de_plazo.append(numero)
    return numero, a_tiempo


def _enfermedad(tarea: dict) -> str:
    """El código de la enfermedad, de `Task.reason`, y solo si es del catálogo de la guía."""
    for razon in tarea.get("reason") or []:
        for codificacion in ((razon.get("concept") or {}).get("coding")) or []:
            if codificacion.get("system") == ENFERMEDADES and codificacion.get("code"):
                return str(codificacion["code"])
    raise DeclaracionRechazadaError(
        422, "La declaración no dice qué enfermedad se declara, o el código no está en vigor."
    )


def _vencimiento(tarea: dict) -> datetime:
    """El final del plazo, de `Task.restriction.period.end`."""
    fin = ((tarea.get("restriction") or {}).get("period") or {}).get("end")
    if not fin:
        raise DeclaracionRechazadaError(
            422,
            "La declaración no trae plazo. Sin fecha límite no se puede priorizar, y una urgente "
            "sin priorizar es una ordinaria.",
        )
    try:
        # FHIR escribe la `Z` que `fromisoformat` no aceptaba hasta 3.11; el proyecto pide 3.11+,
        # pero la sustitución cuesta nada y quita la sorpresa.
        return datetime.fromisoformat(str(fin).replace("Z", "+00:00"))
    except ValueError as no_es_una_fecha:
        raise DeclaracionRechazadaError(
            422, f"El plazo no es un instante legible: {fin}."
        ) from no_es_una_fecha


def _sin_filiacion(nodo: object, camino: str = "Task") -> None:
    """Recorre la declaración entera y rechaza cualquier rastro de datos de persona.

    Se comprueba **al recibir** y no solo al enviar, y ese es el sentido de que esté aquí: el
    laboratorio puede prometer que no manda filiación, pero la promesa solo es una garantía si el
    otro lado la exige. Lo que se busca es de tres clases:

    * un recurso `contained` — la forma más habitual de colar un `Patient` entero;
    * las claves que en FHIR solo cuelgan de una persona (`name`, `birthDate`, `address`…);
    * un `display` sobre una referencia a persona, que es el nombre con otro nombre.

    Lo que **no** se puede comprobar así es el texto libre: una nota puede llevar cualquier cosa.
    Por eso el laboratorio pone ahí motivos técnicos y nunca clínicos, y por eso esto es una capa
    más y no la única.

    Raises:
        DeclaracionRechazadaError: `400`, porque una declaración con filiación no debería haber
            salido del laboratorio.
    """
    if isinstance(nodo, list):
        for indice, hijo in enumerate(nodo):
            _sin_filiacion(hijo, f"{camino}[{indice}]")
        return
    if not isinstance(nodo, dict):
        return

    if nodo.get("contained"):
        raise DeclaracionRechazadaError(
            400, f"{camino} trae recursos dentro (`contained`): por aquí no viajan personas."
        )
    for clave in CLAVES_DE_FILIACION:
        if clave in nodo:
            raise DeclaracionRechazadaError(400, f"{camino}.{clave} es filiación, y aquí no entra.")
    referencia = nodo.get("reference")
    if (
        isinstance(referencia, str)
        and nodo.get("display")
        and referencia.startswith(REFERENCIAS_A_PERSONAS)
    ):
        raise DeclaracionRechazadaError(
            400,
            f"{camino}.display sobre {referencia} es el nombre de la persona escrito en la "
            "referencia.",
        )

    for clave, hijo in nodo.items():
        _sin_filiacion(hijo, f"{camino}.{clave}")
