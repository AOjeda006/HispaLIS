"""Lo que hace verosímil a un juego de datos de laboratorio: paneles, rangos y reflejas.

La demografía es la parte fácil. Lo difícil —y lo que decide si estos datos sirven para probar algo—
es que los resultados se comporten como resultados:

* **Se piden por paneles, no de uno en uno.** Nadie pide una creatinina suelta: pide una bioquímica
  básica. El panel es también lo que fija de qué muestra sale todo, porque se extrae un tubo y de él
  salen las cinco determinaciones.
* **Un hemograma tiene que cuadrar consigo mismo.** El hematocrito ronda el triple de la
  hemoglobina. Sortear los dos por separado produce pacientes imposibles, y contra datos
  imposibles no se puede probar ninguna regla clínica.
* **El rango de referencia depende del sexo** en la serie roja, y sin él una hemoglobina de
  13,5 g/dL es normal en un hombre y alta-normal en una mujer sin que nada lo diga.
* **Una TSH alta dispara una T4 libre.** Es la prueba refleja de manual, y es la que ejercita
  `Observation.triggeredBy`, que es nuevo en R5 y que en R4 había que inventarse.

Los códigos de prueba **no se escriben aquí**: se nombran, y un test comprueba que cada uno de los
nombrados existe en el catálogo que publica la guía. Es lo que impide que esto se convierta en la
lista paralela que prohíbe D15.

Los **rangos de referencia** tampoco: se leen del fichero que publica el laboratorio, el mismo que
consume el backend (ver `rangos.py`). Aquí estuvieron escritos a mano, en paralelo a los del
laboratorio y sin nada que comprobara que coincidían.
"""

from __future__ import annotations

import random
from dataclasses import dataclass

from .rangos import RangoDeReferencia, cargar_rangos

SNOMED = "http://snomed.info/sct"
INTERPRETACION = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation"

#: Tipos de muestra, en SNOMED. Los códigos son los del `ValueSet/tipos-muestra` de la guía.
SANGRE_VENOSA = "122555007"
ORINA = "122575003"
ESPUTO = "119334006"
HECES = "119339001"

#: Resultado cualitativo, en SNOMED.
POSITIVO = "10828004"
NEGATIVO = "260385009"

#: Sexo al que aplica un rango de referencia, en SNOMED.
SEXO_SNOMED = {"male": "248153007", "female": "248152002"}


@dataclass(frozen=True, slots=True)
class Panel:
    """Un conjunto de determinaciones que se piden juntas sobre una misma muestra.

    Attributes:
        nombre: Cómo lo llama el peticionario.
        codigos: Códigos del catálogo local que componen el panel.
        tipo_de_muestra: Código SNOMED del tipo de muestra del que sale.
        peso: Frecuencia relativa con la que se pide, comparada con los demás paneles.
    """

    nombre: str
    codigos: tuple[str, ...]
    tipo_de_muestra: str
    peso: int


#: Los paneles que pide la clientela de un laboratorio privado, con su frecuencia relativa. La
#: bioquímica básica y el hemograma se llevan la mayor parte del trabajo real de un laboratorio, y
#: la microbiología es minoritaria: un corpus con la misma cantidad de cada cosa no se parece a
#: ningún laboratorio.
PANELES = (
    Panel("Bioquímica básica", ("GLU", "CREA", "UREA", "NA", "K"), SANGRE_VENOSA, 30),
    Panel("Hemograma", ("HB", "HTO", "LEU", "PLAQ"), SANGRE_VENOSA, 25),
    Panel("Perfil hepático", ("GOT", "GPT"), SANGRE_VENOSA, 12),
    Panel("Control diabético", ("GLU", "HBA1C"), SANGRE_VENOSA, 10),
    Panel("Perfil tiroideo", ("TSH",), SANGRE_VENOSA, 10),
    Panel("Perfil lipídico", ("COLT",), SANGRE_VENOSA, 6),
    Panel("Marcadores de inflamación", ("PCR",), SANGRE_VENOSA, 4),
    Panel("Serología vírica", ("VHAIGM", "SARIGM"), SANGRE_VENOSA, 4),
    Panel("Antígeno de Legionella", ("LEGIOAG",), ORINA, 3),
    Panel("Coprocultivo", ("COPROSALM",), HECES, 2),
    Panel("Tuberculosis por PCR", ("MTBPCR",), ESPUTO, 2),
)

#: Rangos de referencia del laboratorio, por código del catálogo. **No se escriben aquí**: se leen
#: del fichero que publica el laboratorio, que es el mismo que consume el backend.
#:
#: Se cargan al importar el módulo, y no la primera vez que se piden, a propósito: si el fichero
#: falta o define un rango imposible, el generador tiene que negarse a arrancar y no descubrirlo a
#: mitad del corpus, cuando ya ha escrito medio juego de datos con valores sorteados sin rango.
RANGOS = cargar_rangos()

#: Cuántos decimales lleva impreso cada resultado. Un recuento de plaquetas con dos decimales
#: delata que los datos son de mentira antes que cualquier otra cosa.
DECIMALES = {
    "GLU": 0,
    "CREA": 2,
    "UREA": 0,
    "NA": 0,
    "K": 2,
    "COLT": 0,
    "GOT": 0,
    "GPT": 0,
    "PCR": 1,
    "HBA1C": 1,
    "HB": 1,
    "HTO": 1,
    "LEU": 1,
    "PLAQ": 0,
    "TSH": 2,
    "T4L": 2,
}

#: Qué parte de los resultados cuantitativos sale fuera del rango. Un corpus todo normal no ejercita
#: ni la interpretación, ni la refleja, ni el aviso de valor crítico.
PROPORCION_ALTERADOS = 0.22

#: Qué parte de las pruebas cualitativas sale positiva. En microbiología la inmensa mayoría son
#: negativas, y un corpus mitad y mitad no se parece a ningún laboratorio.
PROPORCION_POSITIVOS = 0.08

#: Código de la prueba refleja y de la que la dispara.
PRUEBA_DISPARADORA = "TSH"
PRUEBA_REFLEJA = "T4L"

RAZON_DE_LA_REFLEJA = "TSH por encima del rango: se añade T4 libre según protocolo."


def elegir_panel(azar: random.Random) -> Panel:
    """Elige un panel respetando la frecuencia con la que se pide cada uno."""
    return azar.choices(PANELES, weights=[panel.peso for panel in PANELES], k=1)[0]


def rangos_de(codigo: str) -> tuple[RangoDeReferencia, ...]:
    """Devuelve los rangos de referencia de una prueba, o una tupla vacía si no tiene."""
    return RANGOS.get(codigo, ())


def rango_aplicable(codigo: str, sexo: str) -> RangoDeReferencia | None:
    """Devuelve el rango que le corresponde a un paciente por su sexo, si la prueba tiene rango."""
    for rango in rangos_de(codigo):
        if rango.sexo is None or rango.sexo == sexo:
            return rango
    return None


def valor_cuantitativo(codigo: str, sexo: str, azar: random.Random) -> float:
    """Sortea un valor verosímil para una prueba, dentro o fuera del rango.

    Fuera del rango se sale poco y sin exagerar —hasta un 40 % por encima o por debajo—: un corpus
    lleno de valores absurdos no prueba la interpretación, prueba el desbordamiento.
    """
    rango = rango_aplicable(codigo, sexo)
    if rango is None:
        return round(azar.uniform(1, 100), DECIMALES.get(codigo, 2))

    amplitud = rango.alto - rango.bajo
    if azar.random() < PROPORCION_ALTERADOS:
        if azar.random() < 0.5:
            crudo = rango.bajo - azar.uniform(0.05, 0.40) * amplitud
        else:
            crudo = rango.alto + azar.uniform(0.05, 0.40) * amplitud
    else:
        crudo = azar.uniform(rango.bajo, rango.alto)

    return round(max(crudo, 0.0), DECIMALES.get(codigo, 2))


def cuadrar_hemograma(valores: dict[str, float], azar: random.Random) -> dict[str, float]:
    """Ajusta el hematocrito para que cuadre con la hemoglobina del mismo hemograma.

    La regla de los tres es la aproximación que usa cualquier hematólogo: el hematocrito ronda el
    triple de la hemoglobina. Sin este ajuste salen pacientes con una hemoglobina de 16 y un
    hematocrito de 37, que es una combinación que no existe.
    """
    if "HB" not in valores or "HTO" not in valores:
        return valores

    cuadrado = dict(valores)
    cuadrado["HTO"] = round(valores["HB"] * azar.uniform(2.9, 3.1), DECIMALES["HTO"])
    return cuadrado


def interpretacion_de(codigo: str, valor: float, sexo: str) -> str | None:
    """Devuelve la interpretación (`H`, `L` o `N`), o `None` si la prueba no tiene rango."""
    rango = rango_aplicable(codigo, sexo)
    if rango is None:
        return None
    if valor > rango.alto:
        return "H"
    if valor < rango.bajo:
        return "L"
    return "N"


def dispara_refleja(codigo: str, valor: float, sexo: str) -> bool:
    """Indica si un resultado obliga a añadir la prueba refleja."""
    return codigo == PRUEBA_DISPARADORA and interpretacion_de(codigo, valor, sexo) == "H"


def resultado_cualitativo(azar: random.Random) -> tuple[str, str, str]:
    """Sortea un resultado cualitativo.

    Returns:
        El código SNOMED del resultado, su término y el código de interpretación.
    """
    if azar.random() < PROPORCION_POSITIVOS:
        return (POSITIVO, "Positive", "POS")
    return (NEGATIVO, "Negative", "NEG")
