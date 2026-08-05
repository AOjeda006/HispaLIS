"""Pacientes sintéticos españoles: los nombres, los documentos y el domicilio.

Dos decisiones gobiernan este módulo:

**Los apellidos no se parten por el espacio, nunca.** `family` lleva el nombre familiar completo y
las dos extensiones estándar lo descomponen. Por eso el corpus incluye a propósito «de la Torre
Gómez» y «Fernández de Córdoba Ruiz»: son los que rompen el heurístico de cortar por el primer
espacio, y si no están en los datos, el heurístico pasa los tests y falla con pacientes reales.

**Los casos obligatorios se garantizan, no se esperan.** `MUÑOZ`, `ÁLVAREZ` y `PEÑA` tienen que
salir en cualquier ejecución, y de un generador aleatorio no se obtiene una garantía: se obtiene una
probabilidad. Por eso los primeros pacientes de toda ejecución son los casos obligatorios y solo el
resto sale al azar.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from datetime import date, timedelta

from faker import Faker

from generador.identificadores import cip_sns, dni_o_nie, nhc, nuhsa

#: Provincia de Sevilla en el registro del INE.
PROVINCIA_SEVILLA = "41"

#: Pares (primer apellido, segundo apellido) que TIENEN que aparecer en toda ejecución.
#:
#: Los tres primeros traen `ñ` y tilde, que es lo que revienta cuando alguien se deja un `charset`
#: sin declarar en una conexión, un fichero o una cabecera. Los dos últimos son apellidos
#: compuestos: su primer apellido lleva espacios, así que cualquier código que corte por el primer
#: espacio los parte mal y convierte a un paciente en otro.
CASOS_OBLIGATORIOS = (
    ("Muñoz", "Álvarez"),
    ("Peña", "Muñoz"),
    ("Álvarez", "Peña"),
    ("de la Torre", "Gómez"),
    ("Fernández de Córdoba", "Ruiz"),
)

#: Apellidos compuestos que se mezclan con los de Faker para que el corpus no sea todo simple.
APELLIDOS_COMPUESTOS = (
    "de la Torre",
    "de la Fuente",
    "del Río",
    "de los Santos",
    "Fernández de Córdoba",
    "Ruiz de Alda",
    "Martínez de la Rosa",
)

#: Apellidos con `ñ` o tilde, para que el corpus aleatorio también los traiga y no solo los casos
#: obligatorios.
APELLIDOS_CON_ACENTO = (
    "Muñoz",
    "Álvarez",
    "Peña",
    "Núñez",
    "Ibáñez",
    "Ordóñez",
    "Yáñez",
    "Mínguez",
)


@dataclass(frozen=True, slots=True)
class Municipio:
    """Un municipio de la provincia de Sevilla, con su código INE y su código postal.

    Attributes:
        nombre: Nombre del municipio.
        codigo_ine: Código INE del municipio dentro de la provincia (tres dígitos).
        codigos_postales: Códigos postales del municipio, entre los que se elige uno.
    """

    nombre: str
    codigo_ine: str
    codigos_postales: tuple[str, ...]


#: Municipios donde vive la clientela del laboratorio. Es una muestra corta y no el registro
#: completo: lo que hace falta es que el código INE viaje de verdad en la dirección, no cubrir los
#: 105 municipios de la provincia.
MUNICIPIOS = (
    Municipio("Sevilla", "091", ("41001", "41003", "41004", "41005", "41008", "41013")),
    Municipio("Dos Hermanas", "038", ("41700",)),
    Municipio("Alcalá de Guadaíra", "004", ("41500",)),
    Municipio("Utrera", "095", ("41710",)),
    Municipio("Écija", "039", ("41400",)),
    Municipio("Carmona", "024", ("41410",)),
    Municipio("Mairena del Aljarafe", "059", ("41927",)),
    Municipio("Lebrija", "052", ("41740",)),
)

#: Qué parte de los pacientes NO trae NUHSA. En un laboratorio privado, mutualistas (MUFACE, MUGEJU,
#: ISFAS) y pacientes privados con frecuencia ni lo conocen. Si el corpus los trajera todos con
#: NUHSA, el sistema nunca se probaría contra el caso que más se da en la puerta.
PROPORCION_SIN_NUHSA = 0.35

#: Qué parte de los pacientes trae CIP-SNS. Es el que menos se conoce de los tres.
PROPORCION_CON_CIP_SNS = 0.25

EDAD_MINIMA = 0
EDAD_MAXIMA = 95


@dataclass(frozen=True, slots=True)
class Paciente:
    """Un paciente sintético, ya resuelto y listo para proyectarse a FHIR.

    Attributes:
        nhc: Número de historia clínica del laboratorio, ocho dígitos.
        nombre: Nombre de pila.
        apellido_padre: Primer apellido, que puede llevar espacios («de la Torre»).
        apellido_madre: Segundo apellido.
        sexo: `male` o `female`, tal y como los codifica FHIR.
        fecha_nacimiento: Fecha de nacimiento.
        documento: DNI o NIE, con su letra de control correcta.
        nuhsa: CIP autonómico andaluz, o `None` si el paciente no lo conoce.
        cip_sns: Código nacional, o `None` si no consta.
        municipio: Municipio de residencia.
        codigo_postal: Código postal, coherente con el municipio.
        via: Línea de dirección.
        telefono: Teléfono de contacto.
    """

    nhc: str
    nombre: str
    apellido_padre: str
    apellido_madre: str
    sexo: str
    fecha_nacimiento: date
    documento: str
    nuhsa: str | None
    cip_sns: str | None
    municipio: Municipio
    codigo_postal: str
    via: str
    telefono: str

    @property
    def apellidos(self) -> str:
        """El nombre familiar COMPLETO, que es lo que va en `HumanName.family`."""
        return f"{self.apellido_padre} {self.apellido_madre}"

    @property
    def edad_en_anios(self) -> int:
        """Edad cumplida a día de hoy, que es lo que decide el rango de referencia."""
        hoy = date.today()
        cumplido = (hoy.month, hoy.day) >= (self.fecha_nacimiento.month, self.fecha_nacimiento.day)
        return hoy.year - self.fecha_nacimiento.year - (0 if cumplido else 1)


def generar_pacientes(cuantos: int, azar: random.Random, faker: Faker) -> tuple[Paciente, ...]:
    """Genera el corpus de pacientes de una ejecución.

    Los primeros son los casos obligatorios, en orden, para que estén incluso en una ejecución
    pequeña; el resto sale al azar.

    Args:
        cuantos: Número de pacientes a generar.
        azar: Fuente de aleatoriedad ya sembrada.
        faker: Generador de localización española, ya sembrado.

    Returns:
        Los pacientes, con NHC correlativo desde el 1.
    """
    return tuple(_paciente(orden, azar, faker) for orden in range(1, cuantos + 1))


def _paciente(orden: int, azar: random.Random, faker: Faker) -> Paciente:
    apellido_padre, apellido_madre = _apellidos(orden, azar, faker)
    sexo = azar.choice(("male", "female"))
    municipio = azar.choice(MUNICIPIOS)

    return Paciente(
        nhc=nhc(orden),
        nombre=faker.first_name_male() if sexo == "male" else faker.first_name_female(),
        apellido_padre=apellido_padre,
        apellido_madre=apellido_madre,
        sexo=sexo,
        fecha_nacimiento=_fecha_de_nacimiento(azar),
        documento=dni_o_nie(azar),
        nuhsa=None if azar.random() < PROPORCION_SIN_NUHSA else nuhsa(azar),
        cip_sns=cip_sns(azar) if azar.random() < PROPORCION_CON_CIP_SNS else None,
        municipio=municipio,
        codigo_postal=azar.choice(municipio.codigos_postales),
        via=faker.street_address(),
        telefono=f"+346{azar.randrange(10**8):08d}",
    )


def _apellidos(orden: int, azar: random.Random, faker: Faker) -> tuple[str, str]:
    if orden <= len(CASOS_OBLIGATORIOS):
        return CASOS_OBLIGATORIOS[orden - 1]
    return (_un_apellido(azar, faker), _un_apellido(azar, faker))


def _un_apellido(azar: random.Random, faker: Faker) -> str:
    sorteo = azar.random()
    if sorteo < 0.15:
        return azar.choice(APELLIDOS_COMPUESTOS)
    if sorteo < 0.40:
        return azar.choice(APELLIDOS_CON_ACENTO)
    return faker.last_name()


def _fecha_de_nacimiento(azar: random.Random) -> date:
    edad = azar.randint(EDAD_MINIMA, EDAD_MAXIMA)
    # El día se desplaza desde el 1 de enero en vez de sortear mes y día por separado: sorteados por
    # separado salen un 31 de febrero cada tantos pacientes, y ese fallo no aparece hasta que el
    # tamaño del corpus lo destapa.
    dia_del_anio = azar.randint(0, 364)
    return date(date.today().year - edad, 1, 1) + timedelta(days=dia_del_anio)
