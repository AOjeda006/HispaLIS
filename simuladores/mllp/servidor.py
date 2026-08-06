"""Recepción MLLP, para el extremo que además de mandar **escucha**.

El HIS no solo emite: recibe el `ORU^R01` que el laboratorio le devuelve cuando el informe se
valida. Un simulador que solo sabe mandar deja ese camino sin poder probarse de extremo a extremo,
que es justo la mitad interesante.

El sobre es el mismo que en `cliente`, y por el mismo motivo se escribe a mano. Lo que cambia es
quién abre la conexión y quién acusa.
"""

from __future__ import annotations

import socket
import socketserver
import ssl
import threading
from dataclasses import dataclass
from datetime import datetime

from mllp.cliente import (
    FIN_DE_BLOQUE,
    INICIO_DE_BLOQUE,
    RETORNO,
    _codificacion_de,
)


@dataclass(frozen=True)
class Escucha:
    """Dónde y cómo se espera.

    `certificado` y `clave` son rutas a ficheros PEM. Sin ellas no hay TLS que valga: se escucha en
    claro, que es lo que se hace para depurar en local y nada más.
    """

    puerto: int = 2576
    interfaz: str = "localhost"
    certificado: str = ""
    clave: str = ""

    @property
    def tls(self) -> bool:
        """Hay cifrado si hay con qué cifrar."""
        return bool(self.certificado and self.clave)


def acuse_de(mensaje: str, codigo: str = "AA") -> str:
    """Compone el `ACK` que se devuelve al laboratorio.

    Va aquí y no en `his/mensajes.py` porque acusar recibo es cosa del transporte, no del HIS: el
    analizador acusaría exactamente igual.

    Args:
        mensaje: el mensaje recibido, del que se copian emisor, receptor y `MSH-10`.
        codigo: `MSA-1`. `AA` aceptado, `AE` error de aplicación, `AR` rechazo.

    Returns:
        El acuse con `\\r` de separador de segmento.
    """
    campos = mensaje.split("\r", 1)[0].split("|")

    def campo(indice: int) -> str:
        return campos[indice] if len(campos) > indice else ""

    sello = datetime.now().strftime("%Y%m%d%H%M%S")
    control = campo(9)
    # El índice de la lista es el número de campo MENOS UNO: `MSH-1` es el propio separador y no
    # ocupa hueco. Contarlo mal es cómo se devuelve el charset en el campo del país.
    cabecera = [""] * 18
    cabecera[0] = "MSH"
    cabecera[1] = "^~\\&"
    cabecera[2] = campo(4) or "HIS_VIRGEN"
    cabecera[3] = campo(5) or "H_VIRGEN_MACARENA"
    cabecera[4] = campo(2)
    cabecera[5] = campo(3)
    cabecera[6] = sello
    cabecera[8] = "ACK^R01^ACK"
    cabecera[9] = f"ACK{control}"
    cabecera[10] = "P"
    cabecera[11] = campo(11) or "2.5.1"
    cabecera[16] = campo(16)
    cabecera[17] = campo(17)
    return "|".join(cabecera) + "\r" + f"MSA|{codigo}|{control}"


class ServidorDelHis:
    """Un extremo MLLP que recibe mensajes, los guarda y acusa recibo.

    Se usa como gestor de contexto para que el hilo y el socket se cierren aunque la prueba falle:

    ```python
    with ServidorDelHis(Escucha(puerto=0)) as servidor:
        ...
        recibidos = servidor.esperar(1)
    ```

    `puerto=0` pide uno libre al sistema y lo publica en `servidor.puerto`. Es lo que evita que dos
    pruebas simultáneas se peleen por el 2576.
    """

    def __init__(self, escucha: Escucha, codigo_de_acuse: str = "AA") -> None:
        """Prepara el servidor sin arrancarlo todavía.

        Args:
            escucha: puerto, interfaz y, si lo hay, el certificado con el que cifrar.
            codigo_de_acuse: qué se contesta en `MSA-1`. `AE` sirve para probar qué hace el
                laboratorio cuando el HIS rechaza lo que le mandan.
        """
        self._escucha = escucha
        self._codigo = codigo_de_acuse
        self._recibidos: list[str] = []
        self._candado = threading.Lock()
        self._llegada = threading.Event()
        self._servidor: socketserver.TCPServer | None = None
        self._hilo: threading.Thread | None = None

    def __enter__(self) -> ServidorDelHis:
        """Arranca el servidor en su propio hilo."""
        self.arrancar()
        return self

    def __exit__(self, *_: object) -> None:
        """Para el servidor y espera a que su hilo termine."""
        self.parar()

    def arrancar(self) -> None:
        """Levanta el socket y empieza a atender."""
        servidor = socketserver.ThreadingTCPServer(
            (self._escucha.interfaz, self._escucha.puerto),
            self._manejador(),
            bind_and_activate=False,
        )
        servidor.allow_reuse_address = True
        servidor.daemon_threads = True
        servidor.server_bind()
        servidor.server_activate()
        if self._escucha.tls:
            contexto = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            contexto.load_cert_chain(self._escucha.certificado, self._escucha.clave)
            servidor.socket = contexto.wrap_socket(servidor.socket, server_side=True)
        self._servidor = servidor
        self._hilo = threading.Thread(target=servidor.serve_forever, daemon=True)
        self._hilo.start()

    def parar(self) -> None:
        """Cierra el socket. Idempotente: pararlo dos veces no es un error."""
        if self._servidor is None:
            return
        self._servidor.shutdown()
        self._servidor.server_close()
        if self._hilo is not None:
            self._hilo.join(timeout=5)
        self._servidor = None
        self._hilo = None

    @property
    def puerto(self) -> int:
        """El puerto real, que con `puerto=0` no se sabe hasta haber arrancado."""
        if self._servidor is None:
            raise RuntimeError("El servidor todavía no ha arrancado")
        return self._servidor.server_address[1]

    @property
    def recibidos(self) -> list[str]:
        """Copia de lo recibido hasta ahora, en orden de llegada."""
        with self._candado:
            return list(self._recibidos)

    def esperar(self, cuantos: int = 1, tiempo_max: float = 15.0) -> list[str]:
        """Bloquea hasta que hayan llegado `cuantos` mensajes.

        Args:
            cuantos: cuántos mensajes se esperan.
            tiempo_max: segundos que se aguanta antes de rendirse.

        Returns:
            Los mensajes recibidos.

        Raises:
            TimeoutError: si no llegan a tiempo. Con el número que sí llegó, que es el dato que
                hace falta para saber si el fallo es «ninguno» o «uno menos de la cuenta».
        """
        limite = datetime.now().timestamp() + tiempo_max
        while len(self.recibidos) < cuantos:
            restante = limite - datetime.now().timestamp()
            if restante <= 0:
                raise TimeoutError(
                    f"Se esperaban {cuantos} mensajes y llegaron {len(self.recibidos)}"
                )
            self._llegada.wait(timeout=min(restante, 0.25))
            self._llegada.clear()
        return self.recibidos

    def _anotar(self, mensaje: str) -> None:
        with self._candado:
            self._recibidos.append(mensaje)
        self._llegada.set()

    def _manejador(self) -> type[socketserver.BaseRequestHandler]:
        recibir = self._anotar
        codigo = self._codigo

        class Manejador(socketserver.BaseRequestHandler):
            def handle(self) -> None:
                while True:
                    crudo = _leer_sobre(self.request)
                    if crudo is None:
                        return
                    mensaje = _decodificar(crudo)
                    recibir(mensaje)
                    respuesta = acuse_de(mensaje, codigo)
                    self.request.sendall(
                        INICIO_DE_BLOQUE
                        + respuesta.encode(_codificacion_de(respuesta))
                        + FIN_DE_BLOQUE
                        + RETORNO
                    )

        return Manejador


def _leer_sobre(conexion: socket.socket) -> bytes | None:
    """Lee un mensaje entero. Devuelve `None` si el otro extremo cerró sin mandar nada más."""
    recibido = b""
    while not recibido.endswith(FIN_DE_BLOQUE + RETORNO):
        trozo = conexion.recv(4096)
        if not trozo:
            return None
        recibido += trozo
    return recibido.removeprefix(INICIO_DE_BLOQUE).removesuffix(FIN_DE_BLOQUE + RETORNO)


def _decodificar(crudo: bytes) -> str:
    """Se lee dos veces: la primera para averiguar el `MSH-18`, la segunda para leerlo de verdad."""
    tentativa = crudo.decode("iso-8859-1", errors="replace")
    return crudo.decode(_codificacion_de(tentativa), errors="replace")
