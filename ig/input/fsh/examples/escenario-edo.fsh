// Enfermedad de declaración obligatoria: un antígeno de Legionella positivo obliga a declarar a
// Salud Pública. No es una funcionalidad opcional — el SVEA incluye a los centros privados.
//
// La generación automática de la notificación pertenece al hito 3; aquí se fija el contrato.

Instance: paciente-edo
InstanceOf: PacienteLabES
Usage: #example
Title: "José María Muñoz de la Torre"
Description: """
Paciente mutualista. Trae NHC, DNI y NASS, pero **ni NUHSA ni CIP-SNS**: es el caso frecuente que
justifica que ninguno de los dos sea obligatorio.

«Muñoz de la Torre» es el apellido paterno completo. Es exactamente el nombre que rompe cualquier
heurístico de partir por el espacio, y por eso está aquí.
"""
* active = true
* identifier[nhc].value = "00052914"
* identifier[dniNie].value = "45120983K"
* identifier[nass].value = "411029384756"
* name[0].use = #official
* name[0].family = "Muñoz de la Torre"
* name[0].family.extension[apellidoPadre].valueString = "Muñoz de la Torre"
* name[0].given[0] = "José"
* name[0].given[1] = "María"
* gender = #male
* birthDate = "1958-11-02"
* address[0].city = "Dos Hermanas"
* address[0].postalCode = "41700"
* address[0].country = "ES"
* address[0].extension[codigoIne].extension[provincia].valueCode = #41
* address[0].extension[codigoIne].extension[municipio].valueCode = #038


Instance: cobertura-mutua
InstanceOf: CoberturaLab
Usage: #example
Title: "Cobertura: mutualidad"
Description: "Cobertura `insurance`. Lleva aseguradora, que es lo que exige la invariante `hlis-cob-1`, y el número de mutualista en `subscriberId` — que en R5 es un `Identifier`, no una cadena."
* status = #active
* kind = #insurance
* beneficiary = Reference(paciente-edo)
* insurer = Reference(mutualidad-ejemplo)
* subscriberId[0].system = "https://aojeda006.github.io/HispaLIS/sid/mutualista"
* subscriberId[0].value = "MUF-8841203"


Instance: mutualidad-ejemplo
InstanceOf: Organization
Usage: #example
Title: "Mutualidad (aseguradora)"
Description: "Una aseguradora **no** es un centro sanitario y no tiene NICA: se referencia como `Organization` sin perfilar, no como `LaboratorioOrg`."
* active = true
* name = "Mutualidad de Funcionarios (simulada)"


Instance: especimen-edo
InstanceOf: EspecimenLab
Usage: #example
Title: "Orina para antígeno de Legionella"
Description: "Muestra de orina de la que sale el resultado declarable."
* status = #available
* accessionIdentifier.system = "https://aojeda006.github.io/HispaLIS/sid/acceso"
* accessionIdentifier.value = "26-0199001"
* type = $SCT#122575003
* subject = Reference(paciente-edo)
* receivedTime = "2026-07-30T11:20:00+02:00"
* collection.collectedDateTime = "2026-07-30T10:55:00+02:00"


Instance: resultado-legionella
InstanceOf: ResultadoLab
Usage: #example
Title: "Antígeno de Legionella: positivo"
Description: "Resultado cualitativo. Su código está en `ValueSet/catalogo-edo`, y eso es lo que dispara la notificación."
* status = #final
* code = CatalogoPruebas#LEGIOAG "Antígeno de Legionella en orina"
* subject = Reference(paciente-edo)
* specimen = Reference(especimen-edo)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-30T10:55:00+02:00"
* issued = "2026-07-30T15:40:00+02:00"
* valueCodeableConcept = $SCT#10828004 "Positive"
* interpretation[0] = http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation#POS "Positive"


Instance: notificacion-edo-ejemplo
InstanceOf: NotificacionEDO
Usage: #example
Title: "Declaración a Salud Pública"
Description: """
La notificación obligatoria. `focus` apunta al resultado que la motiva y `businessStatus` lleva el
estado frente a la administración, que no es el mismo que el `status` de la propia tarea: la tarea
puede estar `completed` por nuestra parte y la declaración seguir pendiente de acuse.
"""
* status = #in-progress
* intent = #order
* code.text = "Notificación de enfermedad de declaración obligatoria"
* businessStatus.text = "Enviada a Redalerta, pendiente de acuse"
* focus = Reference(resultado-legionella)
* for = Reference(paciente-edo)
* requester = Reference(laboratorio-ejemplo)
* owner = Reference(salud-publica-ejemplo)
* authoredOn = "2026-07-30T15:45:00+02:00"
* lastModified = "2026-07-30T15:47:00+02:00"


Instance: salud-publica-ejemplo
InstanceOf: Organization
Usage: #example
Title: "Salud Pública (destinatario de la declaración)"
Description: "Organismo al que se declara. Tampoco es un centro sanitario con NICA."
* active = true
* name = "Sistema de Vigilancia Epidemiológica de Andalucía (simulado)"
