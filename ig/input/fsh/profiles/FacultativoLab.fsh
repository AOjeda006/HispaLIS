Profile: FacultativoLab
Parent: Practitioner
Id: facultativo-lab
Title: "Facultativo"
Description: """
Profesional sanitario que solicita pruebas o valida resultados.

La dirección de un laboratorio clínico en España la pueden ejercer médicos especialistas en Análisis
Clínicos, **farmacéuticos**, biólogos, químicos o bioquímicos: el colegio emisor del número de
colegiado no es único, y por eso el `system` del identificador cuelga de un espacio de nombres por
colegio (`…/sid/colegiado/{colegio}`).

Ese `system` variable es también la razón de que `identifier` **no se divida en slices**: un
discriminador por `system` exige un valor fijo por slice, y enumerar los cincuenta y dos colegios
provinciales de médicos más los de farmacéuticos, biólogos y químicos daría un perfil ilegible que
habría que tocar cada vez que apareciese un colegio nuevo. El colegio se identifica con
`identifier.assigner`, que es procesable y no obliga a cerrar la lista.

Los apellidos siguen la misma regla que en el paciente: `family` completo, extensiones para las
partes, nunca partir por el espacio.
"""

* active MS

* identifier 1..* MS
* identifier.system 1..1 MS
* identifier.system ^short = "Espacio de nombres del colegio profesional emisor"
* identifier.value 1..1 MS
* identifier.value ^short = "Número de colegiado"
* identifier.assigner MS
* identifier.assigner ^short = "Colegio profesional que emite el número"

* name 1..* MS
* insert ApellidosEspanoles

// La titulación es elemento estándar (§6.1): no hace falta extensión para saber si quien firma es
// médico especialista en Análisis Clínicos o farmacéutico.
* qualification MS
* qualification.code MS
* qualification.issuer MS

* telecom MS
