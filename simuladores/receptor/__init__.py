"""Receptor de notificaciones de `Subscription`: el HIS del hospital, por el lado que escucha.

Es el tercero al que el laboratorio entrega, y está aquí —y no como un doble dentro del backend—
porque el contrato de una notificación tiene dos lados y solo se demuestra desde los dos. Lo que
este módulo comprueba es exactamente lo que un receptor de verdad tendría que comprobar:

* que la **firma** cuadra con el secreto compartido, y que la marca de tiempo no es de ayer;
* que la notificación **no trae dentro** el recurso, que es lo que promete `content = id-only`;
* que **no falta ningún número de evento**, que es para lo que sirve `eventNumber`.

Lo tercero es la razón de que el número exista: con una entrega «al menos una vez» y un canal que
puede caerse, el receptor no tiene otra forma de saber que se ha perdido algo.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import time
from dataclasses import dataclass, field

#: Cabeceras con las que el laboratorio firma. Ver `EntregaFirmada` en el backend.
CABECERA_MOMENTO = "X-HispaLIS-Momento"
CABECERA_FIRMA = "X-HispaLIS-Firma"

#: Cuánto se admite de desfase entre la firma y la recepción. Más allá, se trata como reenvío.
VENTANA_EN_SEGUNDOS = 300


class NotificacionRechazadaError(Exception):
    """La notificación no se acepta, y el motivo importa: se le devuelve al laboratorio."""

    def __init__(self, codigo_http: int, motivo: str) -> None:
        super().__init__(motivo)
        self.codigo_http = codigo_http


@dataclass
class Recibidas:
    """Lo que este receptor ha ido viendo, para poder contarlo al final.

    `ultimo_evento` no es decorativo: es lo que permite detectar el hueco. Un receptor que solo
    procesa lo que le llega no se entera nunca de lo que no le llegó.
    """

    focos: list[str] = field(default_factory=list)
    ultimo_evento: int = 0
    huecos: list[tuple[int, int]] = field(default_factory=list)


def firma_esperada(secreto: str, momento: str, cuerpo: str) -> str:
    """El HMAC-SHA256 de `<momento>.<cuerpo>`, en hexadecimal.

    La marca de tiempo entra en lo firmado a propósito: sin ella, una notificación capturada se
    puede reenviar mañana con la misma firma válida.
    """
    mensaje = f"{momento}.{cuerpo}".encode()
    return hmac.new(secreto.encode(), mensaje, hashlib.sha256).hexdigest()


def comprobar_la_firma(
    secreto: str, cabeceras: dict[str, str | None], cuerpo: str, ahora: float | None = None
) -> None:
    """Exige que la notificación venga del laboratorio y no sea un reenvío.

    Raises:
        NotificacionRechazadaError: `401` si falta la firma, si no cuadra o si la marca de
            tiempo está fuera de ventana.
    """
    momento = cabeceras.get(CABECERA_MOMENTO)
    firma = cabeceras.get(CABECERA_FIRMA)
    if not momento or not firma:
        raise NotificacionRechazadaError(401, "La notificación no viene firmada.")

    try:
        desfase = abs((ahora if ahora is not None else time.time()) - int(momento))
    except ValueError as no_es_un_instante:
        raise NotificacionRechazadaError(
            401, "La marca de tiempo no es un instante."
        ) from no_es_un_instante
    if desfase > VENTANA_EN_SEGUNDOS:
        raise NotificacionRechazadaError(
            401, f"La firma es de hace {desfase:.0f} s: se trata como reenvío."
        )

    # `compare_digest` y no `==`: comparar cadena a cadena filtra por el tiempo de respuesta
    # cuántos caracteres iniciales acertó quien lo intenta, que es como se adivina una firma
    # probando.
    _, _, calculada = firma.partition("sha256:")
    if not hmac.compare_digest(calculada, firma_esperada(secreto, momento, cuerpo)):
        raise NotificacionRechazadaError(401, "La firma no cuadra con la clave compartida.")


def procesar(cuerpo: str, visto: Recibidas) -> list[str]:
    """Lee la notificación, comprueba que es `id-only` y devuelve las referencias que trae.

    Raises:
        NotificacionRechazadaError: `400` si no es un `Bundle` de notificación, si la primera
            entrada no es un `SubscriptionStatus` —lo exige la invariante `bdl-13` de R5— o si
            alguna entrada trae el recurso dentro.
    """
    try:
        notificacion = json.loads(cuerpo)
    except json.JSONDecodeError as ilegible:
        raise NotificacionRechazadaError(400, "El cuerpo no es JSON.") from ilegible

    if notificacion.get("resourceType") != "Bundle":
        raise NotificacionRechazadaError(400, "Esto no es un Bundle.")
    if notificacion.get("type") != "subscription-notification":
        raise NotificacionRechazadaError(
            400, f"Tipo de Bundle inesperado: {notificacion.get('type')}."
        )

    entradas = notificacion.get("entry") or []
    if not entradas:
        raise NotificacionRechazadaError(400, "Una notificación sin entradas no dice nada.")

    estado = (entradas[0] or {}).get("resource") or {}
    if estado.get("resourceType") != "SubscriptionStatus":
        raise NotificacionRechazadaError(
            400, "La primera entrada tiene que ser un SubscriptionStatus."
        )

    for entrada in entradas[1:]:
        # El invariante 6, comprobado por el que RECIBE. Un receptor que se traga un recurso
        # completo que no pidió acaba almacenando historia clínica sin saber de dónde salió.
        if entrada.get("resource"):
            raise NotificacionRechazadaError(
                400,
                "Esta notificación trae el recurso dentro. Se pidió `id-only`: por este canal "
                "viajan identidades, no historia clínica.",
            )

    _anotar_los_eventos(estado, visto)
    referencias = [entrada["fullUrl"] for entrada in entradas[1:] if entrada.get("fullUrl")]
    visto.focos.extend(referencias)
    return referencias


def _anotar_los_eventos(estado: dict, visto: Recibidas) -> None:
    """Apunta los números de evento y detecta lo que no llegó."""
    for evento in estado.get("notificationEvent") or []:
        numero = int(evento.get("eventNumber", 0))
        if numero > visto.ultimo_evento + 1 and visto.ultimo_evento > 0:
            visto.huecos.append((visto.ultimo_evento + 1, numero - 1))
        visto.ultimo_evento = max(visto.ultimo_evento, numero)
