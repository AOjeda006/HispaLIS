"""Proyección de los datos sintéticos a recursos FHIR R5 conformes con los perfiles de la guía.

Se escriben como diccionarios y no con una librería de modelos FHIR a propósito: la conformidad no
la declara quien escribe el recurso, la declara **el validador oficial de HL7**, que en la CI revisa
lo que sale de aquí contra los perfiles de la guía. Una librería de modelos daría una falsa
sensación de garantía —comprueba tipos, no perfiles— a cambio de una dependencia pesada.

⚠️ **R5, no R4.** Dos trampas que aquí importan: `ServiceRequest.code` es un `CodeableReference` (el
concepto va dentro de `code.concept`, no directamente en `code`), y `Observation.triggeredBy` es
nuevo — es lo que sostiene la prueba refleja, que en R4 había que inventarse.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone

from generador.clinica import (
    INTERPRETACION,
    RAZON_DE_LA_REFLEJA,
    SEXO_SNOMED,
    SNOMED,
    RangoDeReferencia,
)
from generador.personas import PROVINCIA_SEVILLA, Paciente
from generador.terminologia import SYSTEM_UCUM, Prueba

CANONICA = "https://aojeda006.github.io/HispaLIS/fhir"
SID = "https://aojeda006.github.io/HispaLIS/sid"

PERFIL_PACIENTE = f"{CANONICA}/StructureDefinition/paciente-lab-es"
PERFIL_PETICION = f"{CANONICA}/StructureDefinition/peticion-lab"
PERFIL_ESPECIMEN = f"{CANONICA}/StructureDefinition/especimen-lab"
PERFIL_RESULTADO = f"{CANONICA}/StructureDefinition/resultado-lab"
PERFIL_INFORME = f"{CANONICA}/StructureDefinition/informe-lab"
PERFIL_LABORATORIO = f"{CANONICA}/StructureDefinition/laboratorio-org"
PERFIL_FACULTATIVO = f"{CANONICA}/StructureDefinition/facultativo-lab"

RESULTADOS_CUALITATIVOS = f"{CANONICA}/CodeSystem/resultados-cualitativos"

EXTENSION_CODIGO_INE = f"{CANONICA}/StructureDefinition/codigo-ine"
EXTENSION_APELLIDO_PADRE = "http://hl7.org/fhir/StructureDefinition/humanname-fathers-family"
EXTENSION_APELLIDO_MADRE = "http://hl7.org/fhir/StructureDefinition/humanname-mothers-family"

SID_DNI_NIE = "urn:oid:1.3.6.1.4.1.19126.3"
SID_CIP_SNS = "urn:oid:2.16.724.4.40"

TIPOS_IDENTIFICADOR = "http://terminology.hl7.org/CodeSystem/v2-0203"
AYUNO = "http://terminology.hl7.org/CodeSystem/v2-0916"

#: LOINC del informe de laboratorio, el mismo que usa el ejemplo de la guía.
LOINC_INFORME = "11502-2"

#: El laboratorio y el facultativo que firman todo lo generado. Son dato maestro, no población: un
#: laboratorio tiene uno de cada, no cien mil.
ID_LABORATORIO = "laboratorio-hispalis"
ID_FACULTATIVO = "facultativo-hispalis"

REFERENCIA_LABORATORIO = f"Organization/{ID_LABORATORIO}"
REFERENCIA_FACULTATIVO = f"Practitioner/{ID_FACULTATIVO}"


def desfase_horario(dia: date) -> timezone:
    """Devuelve el huso peninsular español del día indicado.

    Se calcula en vez de fijarlo porque una fecha de invierno con `+02:00` es un dato mal puesto que
    nadie mira: la hora sigue pareciendo razonable. La regla es la de la Unión Europea —del último
    domingo de marzo al último domingo de octubre—, que es normativa y estable.
    """
    inicio = _ultimo_domingo(dia.year, 3)
    fin = _ultimo_domingo(dia.year, 10)
    return timezone(timedelta(hours=2 if inicio <= dia < fin else 1))


def _ultimo_domingo(anio: int, mes: int) -> date:
    siguiente = date(anio + (mes == 12), mes % 12 + 1, 1)
    ultimo = siguiente - timedelta(days=1)
    return ultimo - timedelta(days=(ultimo.weekday() + 1) % 7)


def instante(dia: date, hora: time) -> str:
    """Compone un `dateTime` de FHIR con el huso que le toca al día."""
    return datetime.combine(dia, hora, tzinfo=desfase_horario(dia)).isoformat()


def laboratorio() -> dict:
    """El laboratorio que firma los informes."""
    return {
        "resourceType": "Organization",
        "id": ID_LABORATORIO,
        "meta": {"profile": [PERFIL_LABORATORIO]},
        "identifier": [
            {"system": f"{SID}/nica", "value": "41-00-4321"},
            {"system": f"{SID}/nif", "value": "B41567890"},
        ],
        "active": True,
        "name": "Laboratorio Clínico Hispalis, S.L.",
        # ⚠️ R5: `Organization.telecom` y `.address` ya no existen; cuelgan de `contact`.
        "contact": [
            {
                "address": {
                    "extension": [_codigo_ine("41", "091")],
                    "line": ["Avenida de la Buhaira, 12"],
                    "city": "Sevilla",
                    "postalCode": "41018",
                    "country": "ES",
                }
            }
        ],
    }


def facultativo() -> dict:
    """El facultativo que valida e interpreta los resultados."""
    return {
        "resourceType": "Practitioner",
        "id": ID_FACULTATIVO,
        "meta": {"profile": [PERFIL_FACULTATIVO]},
        "identifier": [
            {
                "system": f"{SID}/colegiado/com-sevilla",
                "value": "414109876",
                "assigner": {"display": "Ilustre Colegio Oficial de Médicos de Sevilla"},
            }
        ],
        "active": True,
        "name": [_nombre("Ángeles", "Muñoz", "Serrano")],
        "qualification": [{"code": {"text": "Especialista en Análisis Clínicos"}}],
    }


def paciente_a_fhir(paciente: Paciente) -> dict:
    """Proyecta un paciente sintético a `PacienteLabES`."""
    identificadores = [
        {
            "system": f"{SID}/nhc",
            "value": paciente.nhc,
            "type": {"coding": [{"system": TIPOS_IDENTIFICADOR, "code": "MR"}]},
        },
        {
            "system": SID_DNI_NIE,
            "value": paciente.documento,
            "type": {"coding": [{"system": TIPOS_IDENTIFICADOR, "code": "NI"}]},
        },
    ]
    if paciente.nuhsa is not None:
        identificadores.append(
            {
                "system": f"{SID}/nuhsa",
                "value": paciente.nuhsa,
                "type": {"coding": [{"system": TIPOS_IDENTIFICADOR, "code": "JHN"}]},
            }
        )
    if paciente.cip_sns is not None:
        identificadores.append(
            {
                "system": SID_CIP_SNS,
                "value": paciente.cip_sns,
                "type": {"coding": [{"system": TIPOS_IDENTIFICADOR, "code": "HC"}]},
            }
        )

    return {
        "resourceType": "Patient",
        "id": id_de_paciente(paciente),
        "meta": {"profile": [PERFIL_PACIENTE]},
        "identifier": identificadores,
        "name": [_nombre(paciente.nombre, paciente.apellido_padre, paciente.apellido_madre)],
        "telecom": [{"system": "phone", "value": paciente.telefono}],
        "gender": paciente.sexo,
        "birthDate": paciente.fecha_nacimiento.isoformat(),
        "address": [
            {
                "extension": [_codigo_ine(PROVINCIA_SEVILLA, paciente.municipio.codigo_ine)],
                "line": [paciente.via],
                "city": paciente.municipio.nombre,
                "postalCode": paciente.codigo_postal,
                "country": "ES",
            }
        ],
        "active": True,
    }


def peticion_a_fhir(
    identificador: str,
    numero: str,
    paciente: Paciente,
    prueba: Prueba,
    system_catalogo: str,
    especimen: str,
    momento: str,
) -> dict:
    """Proyecta una línea de petición a `PeticionLab`.

    Una línea por prueba: en R5 `ServiceRequest.code` es `0..1`, así que un volante con cinco
    determinaciones son cinco recursos que comparten `requisition`. Es exactamente como lo modela el
    núcleo del laboratorio, y no por casualidad.
    """
    return {
        "resourceType": "ServiceRequest",
        "id": identificador,
        "meta": {"profile": [PERFIL_PETICION]},
        "status": "active",
        "intent": "order",
        # ⚠️ R5: `code` es CodeableReference; el concepto va dentro de `code.concept`.
        "code": {"concept": _concepto(system_catalogo, prueba.codigo, prueba.display)},
        "requisition": {"system": f"{SID}/peticion", "value": numero},
        "subject": {"reference": referencia_de_paciente(paciente)},
        "requester": {"reference": REFERENCIA_FACULTATIVO},
        "performer": [{"reference": REFERENCIA_LABORATORIO}],
        "specimen": [{"reference": f"Specimen/{especimen}"}],
        "authoredOn": momento,
        "priority": "routine",
    }


def especimen_a_fhir(
    identificador: str,
    numero_de_acceso: str,
    paciente: Paciente,
    tipo: str,
    peticiones: tuple[str, ...],
    extraido: str,
    recibido: str,
    en_ayunas: bool,
    motivo_de_rechazo: tuple[str, str, str] | None = None,
) -> dict:
    """Proyecta una muestra a `EspecimenLab`.

    Args:
        motivo_de_rechazo: Terna `(system, código, término)` del motivo, o `None` si la muestra
            vale. Cuando viene, el estado pasa a `unsatisfactory`: el invariante `hlis-esp-1` exige
            que un
            rechazo esté motivado, porque rechazar sin decir por qué obliga a llamar por teléfono.
    """
    especimen = {
        "resourceType": "Specimen",
        "id": identificador,
        "meta": {"profile": [PERFIL_ESPECIMEN]},
        "accessionIdentifier": {"system": f"{SID}/acceso", "value": numero_de_acceso},
        "status": "available" if motivo_de_rechazo is None else "unsatisfactory",
        "type": _concepto(SNOMED, tipo, None),
        "subject": {"reference": referencia_de_paciente(paciente)},
        "request": [{"reference": f"ServiceRequest/{peticion}"} for peticion in peticiones],
        "receivedTime": recibido,
        "collection": {"collectedDateTime": extraido},
    }
    if en_ayunas:
        especimen["collection"]["fastingStatusCodeableConcept"] = _concepto(
            AYUNO, "F", "Patient was fasting prior to the procedure."
        )
    if motivo_de_rechazo is not None:
        system, codigo, termino = motivo_de_rechazo
        especimen["condition"] = [_concepto(system, codigo, termino)]
    return especimen


def resultado_a_fhir(
    identificador: str,
    paciente: Paciente,
    prueba: Prueba,
    system_catalogo: str,
    especimen: str,
    peticion: str | None,
    medido: str,
    emitido: str,
    valor: float | None = None,
    cualitativo: tuple[str, str, str] | None = None,
    interpretacion: str | None = None,
    rango: RangoDeReferencia | None = None,
    disparado_por: str | None = None,
) -> dict:
    """Proyecta un resultado a `ResultadoLab`.

    Args:
        peticion: Línea de petición que lo pidió, o `None` en una prueba refleja: la refleja la
            añade el laboratorio por protocolo, así que no hay volante que la pida.
        valor: Cifra del resultado cuantitativo.
        cualitativo: Terna `(código local, término en español, código SNOMED)` del resultado
            cualitativo.
        interpretacion: Código `H`, `L`, `N`, `POS` o `NEG`.
        rango: Rango de referencia aplicable al paciente, si la prueba tiene.
        disparado_por: Id del resultado que obligó a hacer este, si es una prueba refleja.
    """
    resultado = {
        "resourceType": "Observation",
        "id": identificador,
        "meta": {"profile": [PERFIL_RESULTADO]},
        "status": "final",
        "code": _concepto(system_catalogo, prueba.codigo, prueba.display),
        "subject": {"reference": referencia_de_paciente(paciente)},
        "specimen": {"reference": f"Specimen/{especimen}"},
        "performer": [{"reference": REFERENCIA_FACULTATIVO}],
        "effectiveDateTime": medido,
        "issued": emitido,
    }

    if peticion is not None:
        resultado["basedOn"] = [{"reference": f"ServiceRequest/{peticion}"}]

    if cualitativo is not None:
        local, termino, snomed = cualitativo
        # Las dos codificaciones y el `text` en español, igual que en el código de la prueba: la
        # local es la que el laboratorio compara contra su catálogo —de ahí sale la declaración
        # obligatoria— y la de SNOMED es la que entiende quien recibe el recurso sin conocer este
        # dialecto. Publicar solo una de las dos rompe a uno de los dos lados.
        resultado["valueCodeableConcept"] = {
            "coding": [
                {"system": RESULTADOS_CUALITATIVOS, "code": local, "display": termino},
                {"system": SNOMED, "code": snomed},
            ],
            "text": termino,
        }
    elif valor is not None:
        resultado["valueQuantity"] = _cantidad(valor, prueba)

    if interpretacion is not None:
        resultado["interpretation"] = [
            _concepto(INTERPRETACION, interpretacion, None),
        ]
    if rango is not None:
        resultado["referenceRange"] = [_rango_a_fhir(rango, prueba)]
    if disparado_por is not None:
        # ⚠️ R5: `triggeredBy` no existe en R4. Es el gancho oficial de la prueba refleja.
        resultado["triggeredBy"] = [
            {
                "observation": {"reference": f"Observation/{disparado_por}"},
                "type": "reflex",
                "reason": RAZON_DE_LA_REFLEJA,
            }
        ]
    return resultado


def informe_a_fhir(
    identificador: str,
    paciente: Paciente,
    peticiones: tuple[str, ...],
    especimen: str,
    resultados: tuple[str, ...],
    medido: str,
    emitido: str,
    conclusion: str,
) -> dict:
    """Proyecta un informe a `InformeLab`."""
    return {
        "resourceType": "DiagnosticReport",
        "id": identificador,
        "meta": {"profile": [PERFIL_INFORME]},
        "status": "final",
        "code": _concepto("http://loinc.org", LOINC_INFORME, None),
        "subject": {"reference": referencia_de_paciente(paciente)},
        "basedOn": [{"reference": f"ServiceRequest/{peticion}"} for peticion in peticiones],
        "specimen": [{"reference": f"Specimen/{especimen}"}],
        "performer": [{"reference": REFERENCIA_LABORATORIO}],
        "resultsInterpreter": [{"reference": REFERENCIA_FACULTATIVO}],
        "result": [{"reference": f"Observation/{resultado}"} for resultado in resultados],
        "effectiveDateTime": medido,
        "issued": emitido,
        "conclusion": conclusion,
    }


def id_de_paciente(paciente: Paciente) -> str:
    """Id lógico del recurso del paciente, derivado de su NHC para que sea reproducible."""
    return f"paciente-{paciente.nhc}"


def referencia_de_paciente(paciente: Paciente) -> str:
    """Referencia relativa al paciente, tal y como la escriben los demás recursos."""
    return f"Patient/{id_de_paciente(paciente)}"


def _nombre(nombre: str, apellido_padre: str, apellido_madre: str) -> dict:
    """Compone un `HumanName` español.

    `family` lleva el nombre familiar **completo** y las extensiones lo descomponen. Nunca al revés,
    y nunca partiendo por el espacio: «de la Torre Gómez» tiene un primer apellido de tres palabras.
    """
    return {
        "use": "official",
        "family": f"{apellido_padre} {apellido_madre}",
        "_family": {
            "extension": [
                {"url": EXTENSION_APELLIDO_PADRE, "valueString": apellido_padre},
                {"url": EXTENSION_APELLIDO_MADRE, "valueString": apellido_madre},
            ]
        },
        "given": [nombre],
    }


def _codigo_ine(provincia: str, municipio: str | None) -> dict:
    subextensiones = [{"url": "provincia", "valueCode": provincia}]
    if municipio is not None:
        subextensiones.append({"url": "municipio", "valueCode": municipio})
    return {"url": EXTENSION_CODIGO_INE, "extension": subextensiones}


def _concepto(system: str, codigo: str, display: str | None) -> dict:
    coding: dict = {"system": system, "code": codigo}
    if display is not None:
        coding["display"] = display
    return {"coding": [coding]}


def _cantidad(valor: float, prueba: Prueba) -> dict:
    return {
        "value": valor,
        "unit": prueba.unidad_impresa,
        "system": SYSTEM_UCUM,
        "code": prueba.unidad_ucum,
    }


def _rango_a_fhir(rango: RangoDeReferencia, prueba: Prueba) -> dict:
    limites = {
        "low": _cantidad(rango.bajo, prueba),
        "high": _cantidad(rango.alto, prueba),
    }
    if rango.sexo is not None:
        limites["appliesTo"] = [_concepto(SNOMED, SEXO_SNOMED[rango.sexo], None)]
    return limites
