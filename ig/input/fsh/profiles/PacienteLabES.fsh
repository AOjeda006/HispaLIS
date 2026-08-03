Profile: PacienteLabES
Parent: Patient
Id: paciente-lab-es
Title: "Paciente de laboratorio (España)"
Description: """
Paciente atendido por un laboratorio clínico privado español.

Modela la **jerarquía real** de identificadores del SNS (§4.1 del diseño): el CIP-SNS es el código
nacional que hace de nexo, el CIP autonómico es el de cada comunidad —en Andalucía, el NUHSA— y el
NHC es el código interno que el propio centro está obligado a asignar por la Ley 41/2002.

Solo el NHC es obligatorio. En un laboratorio **privado** el NUHSA y el CIP-SNS faltan con
frecuencia: los mutualistas (MUFACE, MUGEJU, ISFAS) y los pacientes privados a menudo ni los
conocen. Un perfil que los exigiese rechazaría pacientes reales.
"""

// El NUHSA se modela como slice de «CIP autonómico», no como un tipo aparte: así el perfil sirve
// para otra comunidad cambiando el `system` del slice, sin rehacerlo.
* insert SlicingIdentificadorPorSystem(Jerarquía de identificación del SNS más los códigos civiles y laborales de uso habitual en un laboratorio privado.)

* identifier contains
    nhc 1..1 MS and
    dniNie 0..1 MS and
    cipAutonomico 0..1 MS and
    cipSns 0..1 MS and
    nass 0..1 MS

* identifier[nhc].system = $SID_NHC
* identifier[nhc].value 1..1
* identifier[nhc] obeys hlis-nhc-1
* identifier[nhc].type = $TIPOS_IDENTIFICADOR_HL7#MR
* identifier[nhc] ^short = "Número de historia clínica del laboratorio"
* identifier[nhc] ^definition = "Código de identificación única del paciente en este centro. La Ley 41/2002 obliga a los centros privados no vinculados a la red pública a asignarlo, y es el único identificador que el laboratorio emite: por eso es el único obligatorio y el único con formato validado."

* identifier[dniNie].system = $SID_DNI_NIE
* identifier[dniNie].type = $TIPOS_IDENTIFICADOR_HL7#NI
* identifier[dniNie] ^short = "DNI o NIE"

* identifier[cipAutonomico].system = $SID_NUHSA
// `JHN` (jurisdictional health number) describe con precisión lo que es un CIP autonómico, y
// refuerza que este slice vale para cualquier comunidad, no solo para Andalucía.
* identifier[cipAutonomico].type = $TIPOS_IDENTIFICADOR_HL7#JHN
* identifier[cipAutonomico] ^short = "CIP autonómico — en Andalucía, el NUHSA"
* identifier[cipAutonomico] ^definition = "Código de identificación personal de la comunidad autónoma. En Andalucía es el NUHSA («AN» más diez dígitos), que vive en la Base de Datos de Usuarios de Diraya. NUNCA es obligatorio: un laboratorio privado atiende a diario a pacientes que no lo conocen."

* identifier[cipSns].system = $SID_CIP_SNS
* identifier[cipSns].type = $TIPOS_IDENTIFICADOR_HL7#HC
* identifier[cipSns] ^short = "CIP-SNS, código nacional único y vitalicio"

* identifier[nass].system = $SID_NASS
* identifier[nass].type = $TIPOS_IDENTIFICADOR_HL7#SS
* identifier[nass] ^short = "Número de afiliación a la Seguridad Social"

// Ningún `pattern` ni regex sobre el `value` de los identificadores ajenos (D16): el laboratorio no
// los emite, validar su formato solo produce falsos rechazos, y la estructura cambia por Real
// Decreto —ya lo ha hecho tres veces—, lo que convertiría un cambio normativo en un despliegue
// urgente.
* identifier.value MS

// Que un dato no conste es información, y no se representa con un valor vacío: un extranjero SIN
// DNI no es lo mismo que un paciente cuyo DNI no se registró.
* identifier.value.extension contains $EXT_AUSENCIA_DATO named ausencia 0..1 MS
* identifier.value.extension[ausencia] ^short = "Motivo por el que el identificador no consta"

* name 1..* MS
* insert ApellidosEspanoles

* insert CodigoIneEnDireccion(address)

* active MS
* gender MS
// `birthDate` no se fuerza a 1..1 pese a que el rango de referencia por edad lo necesita: un
// paciente puede llegar sin filiar. Marcarlo `MS` obliga a soportarlo sin rechazar el recurso.
* birthDate MS
* address MS
* telecom MS


Invariant: hlis-nhc-1
Description: "El número de historia clínica del laboratorio son exactamente ocho dígitos."
Severity: #error
Expression: "value.matches('^[0-9]{8}$')"
