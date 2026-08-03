Profile: InformeLab
Parent: DiagnosticReport
Id: informe-lab
Title: "Informe de laboratorio"
Description: """
Informe analítico validado: el conjunto de resultados que se entrega al peticionario y al paciente.

Un informe **siempre** tiene al menos un resultado —un informe vacío no es un informe— y siempre
lleva quién lo emite, porque de eso depende a quién se reclama.

`presentedForm` transporta el PDF firmado. Es el documento que el paciente descarga y el que, en la
práctica española, se considera «el informe»; los recursos estructurados son lo que permite
procesarlo.
"""

* status MS
* category MS

* code MS
* code ^short = "Tipo de informe, en LOINC"

* subject 1..1 MS
* subject only Reference(PacienteLabES)

* basedOn MS
* basedOn only Reference(PeticionLab)

* specimen MS
* specimen only Reference(EspecimenLab)

* result 1..* MS
* result only Reference(ResultadoLab)

* performer 1..* MS
* performer only Reference(LaboratorioOrg or FacultativoLab or PractitionerRole)

* resultsInterpreter MS
* resultsInterpreter only Reference(FacultativoLab or PractitionerRole)

* effective[x] MS
* issued 1..1 MS

* presentedForm MS
* presentedForm.contentType 1..1 MS
* presentedForm.contentType ^short = "Tipo MIME del documento entregado; el laboratorio emite PDF"
* presentedForm.language MS

* conclusion MS
