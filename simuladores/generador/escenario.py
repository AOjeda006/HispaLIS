"""El episodio de laboratorio completo: de la petición al informe.

Un paciente suelto no prueba nada. Lo que prueba el circuito es el **episodio**: un volante con
varias determinaciones, un tubo del que salen todas, los resultados que produce y el informe que las
recoge. Aquí se arma ese episodio entero y con las referencias cruzadas puestas, que es donde se ve
si el modelo aguanta.

Dos episodios de cada veinte terminan en **muestra rechazada y sin resultados**. No es ruido: es el
caso que sostiene el invariante C6 del laboratorio —una muestra rechazada no puede producir
resultados—, y un corpus donde todas las muestras valen deja ese camino sin recorrer.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, time, timedelta

from faker import Faker

from generador import clinica, recursos
from generador.configuracion import ConfiguracionGeneracion
from generador.identificadores import numero_de_acceso, numero_de_peticion
from generador.personas import Paciente, generar_pacientes
from generador.terminologia import Catalogo

#: Motivos de rechazo, del `ValueSet/motivos-rechazo-muestra` de la guía: terna
#: `(system, código, término)`.
MOTIVOS_DE_RECHAZO = (
    ("http://terminology.hl7.org/CodeSystem/rejection-criteria", "hemolized", "hemolized specimen"),
    (
        "http://terminology.hl7.org/CodeSystem/rejection-criteria",
        "insufficient",
        "insufficient specimen volume",
    ),
    ("http://terminology.hl7.org/CodeSystem/rejection-criteria", "clotted", "specimen clotted"),
    ("http://terminology.hl7.org/CodeSystem/v2-0493", "CON", "Contaminated"),
)

#: Qué parte de las muestras llega en condiciones que obligan a rechazarla.
PROPORCION_RECHAZADAS = 0.10

#: Qué parte de los pacientes vuelve por segunda vez dentro del periodo generado.
PROPORCION_CON_SEGUNDO_EPISODIO = 0.25

#: Días hacia atrás sobre los que se reparte la actividad. Un laboratorio no atiende todo el mismo
#: día, y con todas las fechas iguales no se prueba ninguna búsqueda por rango.
DIAS_DE_ACTIVIDAD = 30

HORA_PETICION = time(8, 0)
HORA_EXTRACCION = time(8, 41)
HORA_RECEPCION = time(9, 2)
HORA_VALIDACION = time(14, 30)
HORA_INFORME = time(16, 30)


@dataclass(slots=True)
class Corpus:
    """Todo lo generado en una ejecución, en el orden en que se produjo.

    Attributes:
        recursos: Los recursos FHIR, dato maestro incluido.
        pacientes: Los pacientes sintéticos de los que salió todo.
        episodios: Cuántos episodios se generaron.
        muestras_rechazadas: Cuántos episodios acabaron en rechazo y sin resultados.
        reflejas: Cuántas pruebas reflejas se dispararon.
    """

    recursos: list[dict] = field(default_factory=list)
    pacientes: tuple[Paciente, ...] = ()
    episodios: int = 0
    muestras_rechazadas: int = 0
    reflejas: int = 0

    def de_tipo(self, tipo: str) -> list[dict]:
        """Devuelve los recursos generados de un tipo concreto."""
        return [recurso for recurso in self.recursos if recurso["resourceType"] == tipo]


def generar(configuracion: ConfiguracionGeneracion, catalogo: Catalogo) -> Corpus:
    """Genera el corpus completo de una ejecución.

    Args:
        configuracion: Semilla, número de pacientes y fecha de referencia.
        catalogo: El catálogo de pruebas leído de la guía.

    Returns:
        Los recursos y el recuento de lo que salió.
    """
    azar = random.Random(configuracion.semilla)
    faker = Faker("es_ES")
    faker.seed_instance(configuracion.semilla)

    corpus = Corpus(recursos=[recursos.laboratorio(), recursos.facultativo()])
    corpus.pacientes = generar_pacientes(configuracion.pacientes, azar, faker)

    contadores = _Contadores()
    for paciente in corpus.pacientes:
        corpus.recursos.append(recursos.paciente_a_fhir(paciente))

        episodios = 2 if azar.random() < PROPORCION_CON_SEGUNDO_EPISODIO else 1
        for _ in range(episodios):
            _generar_episodio(corpus, paciente, catalogo, configuracion.fecha, azar, contadores)

    return corpus


@dataclass(slots=True)
class _Contadores:
    """Numeración correlativa de la ejecución, que es lo que hace los ids reproducibles."""

    peticion: int = 0
    muestra: int = 0
    resultado: int = 0
    informe: int = 0

    def siguiente(self, cual: str) -> int:
        setattr(self, cual, getattr(self, cual) + 1)
        return getattr(self, cual)


def _generar_episodio(
    corpus: Corpus,
    paciente: Paciente,
    catalogo: Catalogo,
    referencia: date,
    azar: random.Random,
    contadores: _Contadores,
) -> None:
    panel = clinica.elegir_panel(azar)
    dia = referencia - timedelta(days=azar.randrange(DIAS_DE_ACTIVIDAD))

    orden_muestra = contadores.siguiente("muestra")
    id_muestra = f"muestra-{orden_muestra:06d}"
    numero = numero_de_peticion(dia.year, contadores.peticion + 1)

    lineas = []
    for codigo in panel.codigos:
        orden = contadores.siguiente("peticion")
        identificador = f"peticion-{orden:06d}"
        corpus.recursos.append(
            recursos.peticion_a_fhir(
                identificador=identificador,
                numero=numero,
                paciente=paciente,
                prueba=catalogo[codigo],
                system_catalogo=catalogo.system,
                especimen=id_muestra,
                momento=recursos.instante(dia, HORA_PETICION),
            )
        )
        lineas.append((codigo, identificador))

    rechazo = azar.choice(MOTIVOS_DE_RECHAZO) if azar.random() < PROPORCION_RECHAZADAS else None
    corpus.recursos.append(
        recursos.especimen_a_fhir(
            identificador=id_muestra,
            numero_de_acceso=numero_de_acceso(dia.year, orden_muestra),
            paciente=paciente,
            tipo=panel.tipo_de_muestra,
            peticiones=tuple(identificador for _, identificador in lineas),
            extraido=recursos.instante(dia, HORA_EXTRACCION),
            recibido=recursos.instante(dia, HORA_RECEPCION),
            en_ayunas=panel.tipo_de_muestra == clinica.SANGRE_VENOSA,
            motivo_de_rechazo=rechazo,
        )
    )

    corpus.episodios += 1
    if rechazo is not None:
        # Y aquí se acaba el episodio: una muestra rechazada no produce resultados, y el informe que
        # los recogería tampoco existe. Es el invariante, no una omisión.
        corpus.muestras_rechazadas += 1
        return

    emitidos = _informar_resultados(
        corpus, paciente, catalogo, lineas, id_muestra, dia, azar, contadores
    )

    corpus.recursos.append(
        recursos.informe_a_fhir(
            identificador=f"informe-{contadores.siguiente('informe'):06d}",
            paciente=paciente,
            peticiones=tuple(identificador for _, identificador in lineas),
            especimen=id_muestra,
            resultados=tuple(identificador for identificador, _ in emitidos),
            medido=recursos.instante(dia, HORA_EXTRACCION),
            emitido=recursos.instante(dia, HORA_INFORME),
            conclusion=_conclusion(emitidos),
        )
    )


def _informar_resultados(
    corpus: Corpus,
    paciente: Paciente,
    catalogo: Catalogo,
    lineas: list[tuple[str, str]],
    id_muestra: str,
    dia: date,
    azar: random.Random,
    contadores: _Contadores,
) -> list[tuple[str, str | None]]:
    """Emite los resultados del panel y, si procede, la prueba refleja.

    Returns:
        Pares `(id del resultado, código de interpretación)`, en el orden en que se emitieron.
    """
    valores = {
        codigo: clinica.valor_cuantitativo(codigo, paciente.sexo, azar)
        for codigo, _ in lineas
        if catalogo[codigo].es_cuantitativa
    }
    valores = clinica.cuadrar_hemograma(valores, azar)

    emitidos: list[tuple[str, str | None]] = []
    for codigo, peticion in lineas:
        prueba = catalogo[codigo]
        identificador = f"resultado-{contadores.siguiente('resultado'):06d}"
        interpretacion: str | None

        if prueba.es_cuantitativa:
            valor = valores[codigo]
            interpretacion = clinica.interpretacion_de(codigo, valor, paciente.sexo)
            corpus.recursos.append(
                recursos.resultado_a_fhir(
                    identificador=identificador,
                    paciente=paciente,
                    prueba=prueba,
                    system_catalogo=catalogo.system,
                    especimen=id_muestra,
                    peticion=peticion,
                    medido=recursos.instante(dia, HORA_EXTRACCION),
                    emitido=recursos.instante(dia, HORA_VALIDACION),
                    valor=valor,
                    interpretacion=interpretacion,
                    rango=clinica.rango_aplicable(codigo, paciente.sexo),
                )
            )
            if clinica.dispara_refleja(codigo, valor, paciente.sexo):
                _anadir_refleja(
                    corpus,
                    paciente,
                    catalogo,
                    id_muestra,
                    dia,
                    identificador,
                    azar,
                    contadores,
                    emitidos,
                )
        else:
            snomed, termino, interpretacion = clinica.resultado_cualitativo(azar)
            corpus.recursos.append(
                recursos.resultado_a_fhir(
                    identificador=identificador,
                    paciente=paciente,
                    prueba=prueba,
                    system_catalogo=catalogo.system,
                    especimen=id_muestra,
                    peticion=peticion,
                    medido=recursos.instante(dia, HORA_EXTRACCION),
                    emitido=recursos.instante(dia, HORA_VALIDACION),
                    cualitativo=(snomed, termino),
                    interpretacion=interpretacion,
                )
            )

        emitidos.append((identificador, interpretacion))

    return emitidos


def _anadir_refleja(
    corpus: Corpus,
    paciente: Paciente,
    catalogo: Catalogo,
    id_muestra: str,
    dia: date,
    disparador: str,
    azar: random.Random,
    contadores: _Contadores,
    emitidos: list[tuple[str, str | None]],
) -> None:
    """Añade la T4 libre que dispara una TSH alta, enlazada con `triggeredBy`."""
    prueba = catalogo[clinica.PRUEBA_REFLEJA]
    valor = clinica.valor_cuantitativo(clinica.PRUEBA_REFLEJA, paciente.sexo, azar)
    identificador = f"resultado-{contadores.siguiente('resultado'):06d}"

    corpus.recursos.append(
        recursos.resultado_a_fhir(
            identificador=identificador,
            paciente=paciente,
            prueba=prueba,
            system_catalogo=catalogo.system,
            especimen=id_muestra,
            # La refleja no la pidió nadie: la añade el laboratorio por protocolo.
            peticion=None,
            medido=recursos.instante(dia, HORA_EXTRACCION),
            emitido=recursos.instante(dia, HORA_INFORME),
            valor=valor,
            interpretacion=clinica.interpretacion_de(clinica.PRUEBA_REFLEJA, valor, paciente.sexo),
            rango=clinica.rango_aplicable(clinica.PRUEBA_REFLEJA, paciente.sexo),
            disparado_por=disparador,
        )
    )
    corpus.reflejas += 1
    emitidos.append((identificador, None))


def _conclusion(emitidos: list[tuple[str, str | None]]) -> str:
    """Redacta la conclusión del informe a partir de lo que salió alterado."""
    alterados = sum(1 for _, interpretacion in emitidos if interpretacion in ("H", "L", "POS"))
    if alterados == 0:
        return "Todos los parámetros estudiados están dentro del rango de referencia."
    if alterados == 1:
        return "Un parámetro fuera del rango de referencia. Se recomienda valoración clínica."
    return (
        f"{alterados} parámetros fuera del rango de referencia. Se recomienda valoración clínica."
    )
