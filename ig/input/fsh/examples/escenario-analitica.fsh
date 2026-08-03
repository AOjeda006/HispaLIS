// Escenario principal: una analítica de rutina, de la petición al informe.
//
// Los ejemplos se agrupan POR ESCENARIO y no uno por fichero: se leen como una historia y las
// referencias entre ellos quedan a la vista. Todos los datos son SINTÉTICOS.
//
// `MUÑOZ`, `ÁLVAREZ` y `PEÑA` son casos de prueba obligatorios del proyecto, no decorativos: son
// los que revientan una tubería mal configurada de juego de caracteres.

Instance: laboratorio-ejemplo
InstanceOf: LaboratorioOrg
Usage: #example
Title: "Laboratorio Clínico Hispalis"
Description: "El laboratorio que emite los informes, con su NICA y su NIF."
* active = true
* name = "Laboratorio Clínico Hispalis, S.L."
* identifier[nica].system = $SID_NICA
* identifier[nica].value = "41-00-4321"
* identifier[nif].system = $SID_NIF
* identifier[nif].value = "B41567890"
* contact.address.line = "Avenida de la Buhaira, 12"
* contact.address.city = "Sevilla"
* contact.address.postalCode = "41018"
* contact.address.country = "ES"
* contact.address.extension[codigoIne].extension[provincia].valueCode = #41
* contact.address.extension[codigoIne].extension[municipio].valueCode = #091


Instance: facultativo-ejemplo
InstanceOf: FacultativoLab
Usage: #example
Title: "Dra. Ángeles Muñoz Serrano"
Description: "Facultativa especialista en Análisis Clínicos que valida los resultados."
* active = true
* identifier[0].system = $SID_COLEGIADO_COM_SEVILLA
* identifier[0].value = "414109876"
* identifier[0].assigner.display = "Ilustre Colegio Oficial de Médicos de Sevilla"
* name[0].use = #official
* name[0].family = "Muñoz Serrano"
* name[0].family.extension[apellidoPadre].valueString = "Muñoz"
* name[0].family.extension[apellidoMadre].valueString = "Serrano"
* name[0].given[0] = "Ángeles"
* qualification[0].code.text = "Especialista en Análisis Clínicos"


Instance: paciente-ejemplo
InstanceOf: PacienteLabES
Usage: #example
Title: "Begoña Peña Álvarez"
Description: """
Paciente privada con NHC, DNI y NUHSA. No consta CIP-SNS, que es lo habitual en un laboratorio
privado.

`family` lleva el apellido **completo** —«Peña Álvarez»— y las extensiones lo descomponen. Partir por
el espacio funcionaría aquí y fallaría con «de la Torre Gómez», que es exactamente por lo que no se
hace nunca.
"""
* active = true
* identifier[nhc].value = "00041237"
* identifier[dniNie].value = "28934571H"
* identifier[cipAutonomico].value = "AN0293847561"
* name[0].use = #official
* name[0].family = "Peña Álvarez"
* name[0].family.extension[apellidoPadre].valueString = "Peña"
* name[0].family.extension[apellidoMadre].valueString = "Álvarez"
* name[0].given[0] = "Begoña"
* gender = #female
* birthDate = "1971-03-14"
* telecom[0].system = #phone
* telecom[0].value = "+34600112233"
* address[0].line = "Calle Feria, 45, 2º B"
* address[0].city = "Sevilla"
* address[0].postalCode = "41003"
* address[0].country = "ES"
* address[0].extension[codigoIne].extension[provincia].valueCode = #41
* address[0].extension[codigoIne].extension[municipio].valueCode = #091


Instance: cobertura-privada
InstanceOf: CoberturaLab
Usage: #example
Title: "Cobertura: paga la paciente"
Description: "Cobertura `self-pay`. Sin aseguradora, como exige la invariante `hlis-cob-1`."
* status = #active
* kind = #self-pay
* beneficiary = Reference(paciente-ejemplo)


Instance: peticion-ejemplo
InstanceOf: PeticionLab
Usage: #example
Title: "Petición: perfil básico"
Description: "Línea de petición para una glucosa. Comparte `requisition` con el resto de líneas de la misma petición."
* status = #active
* intent = #order
* code.concept = CatalogoPruebas#GLU "Glucosa"
* requisition.system = "https://aojeda006.github.io/HispaLIS/sid/peticion"
* requisition.value = "P-2026-004512"
* subject = Reference(paciente-ejemplo)
* requester = Reference(facultativo-ejemplo)
* performer[0] = Reference(laboratorio-ejemplo)
* specimen[0] = Reference(especimen-ejemplo)
* authoredOn = "2026-07-28T08:15:00+02:00"
* priority = #routine


Instance: especimen-ejemplo
InstanceOf: EspecimenLab
Usage: #example
Title: "Sangre venosa, aceptada"
Description: "Muestra correcta: sin `condition`, porque no hay nada que objetar."
* status = #available
* accessionIdentifier.system = "https://aojeda006.github.io/HispaLIS/sid/acceso"
* accessionIdentifier.value = "26-0198437"
* type = $SCT#122555007
* subject = Reference(paciente-ejemplo)
* request[0] = Reference(peticion-ejemplo)
* receivedTime = "2026-07-28T09:02:00+02:00"
* collection.collectedDateTime = "2026-07-28T08:41:00+02:00"
* collection.fastingStatusCodeableConcept = $AYUNO_HL7#F "Patient was fasting prior to the procedure."
* collection.fastingStatusCodeableConcept.text = "En ayunas de doce horas"


Instance: resultado-glucosa
InstanceOf: ResultadoLab
Usage: #example
Title: "Glucosa 112 mg/dL"
Description: "Valor por encima del rango. Nunca se presenta una cifra sin unidad y sin rango de referencia."
* status = #final
* code = CatalogoPruebas#GLU "Glucosa"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
* basedOn[0] = Reference(peticion-ejemplo)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-28T08:41:00+02:00"
* issued = "2026-07-28T13:20:00+02:00"
* valueQuantity.value = 112
* valueQuantity.unit = "mg/dL"
* valueQuantity.system = $UCUM
* valueQuantity.code = #mg/dL
* interpretation[0] = http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation#H "High"
* referenceRange[0].low.value = 70
* referenceRange[0].low.unit = "mg/dL"
* referenceRange[0].low.system = $UCUM
* referenceRange[0].low.code = #mg/dL
* referenceRange[0].high.value = 100
* referenceRange[0].high.unit = "mg/dL"
* referenceRange[0].high.system = $UCUM
* referenceRange[0].high.code = #mg/dL


Instance: resultado-tsh
InstanceOf: ResultadoLab
Usage: #example
Title: "TSH 8,4 µUI/mL"
Description: "TSH alterada. Es la que dispara la T4 libre refleja."
* status = #final
* code = CatalogoPruebas#TSH "TSH (tirotropina)"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-28T08:41:00+02:00"
* issued = "2026-07-28T13:20:00+02:00"
* valueQuantity.value = 8.4
* valueQuantity.unit = "µUI/mL"
* valueQuantity.system = $UCUM
* valueQuantity.code = #u[IU]/mL
* interpretation[0] = http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation#H "High"
* referenceRange[0].low.value = 0.4
* referenceRange[0].low.unit = "µUI/mL"
* referenceRange[0].low.system = $UCUM
* referenceRange[0].low.code = #u[IU]/mL
* referenceRange[0].high.value = 4.0
* referenceRange[0].high.unit = "µUI/mL"
* referenceRange[0].high.system = $UCUM
* referenceRange[0].high.code = #u[IU]/mL


Instance: resultado-t4-libre-reflejo
InstanceOf: ResultadoLab
Usage: #example
Title: "T4 libre, prueba refleja"
Description: """
El gancho de R5: `triggeredBy` con `type = reflex` dice, de forma procesable, que esta determinación
existe **porque** la TSH salió alterada. En R4 esto había que inventarlo con una extensión.
"""
* status = #final
* code = CatalogoPruebas#T4L "T4 libre"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-28T08:41:00+02:00"
* issued = "2026-07-28T16:05:00+02:00"
* valueQuantity.value = 0.9
* valueQuantity.unit = "ng/dL"
* valueQuantity.system = $UCUM
* valueQuantity.code = #ng/dL
* triggeredBy[0].observation = Reference(resultado-tsh)
* triggeredBy[0].type = #reflex
* triggeredBy[0].reason = "TSH por encima del rango: se añade T4 libre según protocolo."
* referenceRange[0].low.value = 0.7
* referenceRange[0].low.unit = "ng/dL"
* referenceRange[0].low.system = $UCUM
* referenceRange[0].low.code = #ng/dL
* referenceRange[0].high.value = 1.9
* referenceRange[0].high.unit = "ng/dL"
* referenceRange[0].high.system = $UCUM
* referenceRange[0].high.code = #ng/dL


Instance: informe-ejemplo
InstanceOf: InformeLab
Usage: #example
Title: "Informe de la analítica"
Description: "Informe validado con los tres resultados. `presentedForm` transportaría el PDF firmado."
* status = #final
* code = $LOINC#11502-2
* subject = Reference(paciente-ejemplo)
* basedOn[0] = Reference(peticion-ejemplo)
* specimen[0] = Reference(especimen-ejemplo)
* performer[0] = Reference(laboratorio-ejemplo)
* resultsInterpreter[0] = Reference(facultativo-ejemplo)
* result[0] = Reference(resultado-glucosa)
* result[1] = Reference(resultado-tsh)
* result[2] = Reference(resultado-t4-libre-reflejo)
* effectiveDateTime = "2026-07-28T08:41:00+02:00"
* issued = "2026-07-28T16:30:00+02:00"
* conclusion = "Glucosa basal elevada y TSH por encima del rango con T4 libre normal. Se recomienda repetir en tres meses."
