// El «dialecto» local del laboratorio: los códigos con los que el personal pide y firma las pruebas.
//
// Es el eje de terminología del proyecto (§5 del diseño). El propio Módulo de Pruebas Analíticas de
// Diraya describe así su función: «cada SIL mantiene su autonomía para utilizar la nomenclatura más
// adecuada, pero al intercambiar información la base de datos actúa como traductor de los dialectos
// locales a un lenguaje común». Ese traductor es `ConceptMap/catalogo-a-loinc`.
//
// La propiedad `unidad-ucum` vive AQUÍ y no en una tabla aparte: el generador de datos sintéticos y
// el backend tienen que consumir el mismo catálogo que la IG, nunca una lista paralela.

CodeSystem: CatalogoPruebas
Id: catalogo-pruebas
Title: "Catálogo de pruebas del laboratorio"
Description: """
Códigos propios del laboratorio para las pruebas que oferta. Es un catálogo **local y simulado**: no
reproduce el de ningún laboratorio real.

Cada concepto cuantitativo declara su **unidad UCUM**, que es la que se emite en el resultado. Las
unidades son las de uso habitual en España, y eso condiciona el mapeo a LOINC: informar la glucosa en
`mg/dL` obliga a mapear al término LOINC de *masa/volumen*, no al de *moles/volumen*. Elegir el
término LOINC por el nombre en vez de por sus seis ejes es el error de mapeo más frecuente.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(PruebasDelCatalogo)
* ^property[0].code = #unidad-ucum
* ^property[0].description = "Unidad UCUM en la que el laboratorio emite el resultado. Ausente en las pruebas cualitativas."
* ^property[0].type = #code


// ─── Bioquímica ──────────────────────────────────────────────────────────────

* #GLU "Glucosa" "Glucosa en suero o plasma."
* #GLU ^property[0].code = #unidad-ucum
* #GLU ^property[0].valueCode = #mg/dL

* #CREA "Creatinina" "Creatinina en suero o plasma."
* #CREA ^property[0].code = #unidad-ucum
* #CREA ^property[0].valueCode = #mg/dL

// En España se informa la UREA; en el mundo anglosajón, el NITRÓGENO UREICO (BUN). Son magnitudes
// distintas con códigos LOINC distintos y un factor de conversión de 2,14 entre ellas.
* #UREA "Urea" "Urea en suero o plasma."
* #UREA ^property[0].code = #unidad-ucum
* #UREA ^property[0].valueCode = #mg/dL

* #NA "Sodio" "Sodio en suero o plasma."
* #NA ^property[0].code = #unidad-ucum
* #NA ^property[0].valueCode = #mmol/L

* #K "Potasio" "Potasio en suero o plasma."
* #K ^property[0].code = #unidad-ucum
* #K ^property[0].valueCode = #mmol/L

* #COLT "Colesterol total" "Colesterol total en suero o plasma."
* #COLT ^property[0].code = #unidad-ucum
* #COLT ^property[0].valueCode = #mg/dL

* #GOT "GOT (AST)" "Aspartato aminotransferasa en suero o plasma."
* #GOT ^property[0].code = #unidad-ucum
* #GOT ^property[0].valueCode = #U/L

* #GPT "GPT (ALT)" "Alanina aminotransferasa en suero o plasma."
* #GPT ^property[0].code = #unidad-ucum
* #GPT ^property[0].valueCode = #U/L

// «PCR» es ambiguo en un laboratorio español: proteína C reactiva y reacción en cadena de la
// polimerasa. Aquí es la primera, y la definición es lo que lo resuelve. Esa ambigüedad es
// exactamente la razón de que un dialecto local necesite mapearse a un lenguaje común.
* #PCR "Proteína C reactiva" "Proteína C reactiva en suero o plasma. No confundir con la reacción en cadena de la polimerasa."
* #PCR ^property[0].code = #unidad-ucum
* #PCR ^property[0].valueCode = #mg/L

* #HBA1C "Hemoglobina glicosilada" "Fracción de hemoglobina A1c sobre hemoglobina total, en sangre."
* #HBA1C ^property[0].code = #unidad-ucum
* #HBA1C ^property[0].valueCode = #%


// ─── Hematología ─────────────────────────────────────────────────────────────

* #HB "Hemoglobina" "Hemoglobina en sangre."
* #HB ^property[0].code = #unidad-ucum
* #HB ^property[0].valueCode = #g/dL

* #HTO "Hematocrito" "Fracción de volumen de eritrocitos en sangre."
* #HTO ^property[0].code = #unidad-ucum
* #HTO ^property[0].valueCode = #%

* #LEU "Leucocitos" "Recuento de leucocitos en sangre."
* #LEU ^property[0].code = #unidad-ucum
* #LEU ^property[0].valueCode = #"10*3/uL"

* #PLAQ "Plaquetas" "Recuento de plaquetas en sangre."
* #PLAQ ^property[0].code = #unidad-ucum
* #PLAQ ^property[0].valueCode = #"10*3/uL"


// ─── Hormonas ────────────────────────────────────────────────────────────────

// TSH y T4 libre son el par de la prueba refleja del diseño: una TSH alterada dispara una T4 libre,
// que se enlaza con `Observation.triggeredBy`.
* #TSH "TSH (tirotropina)" "Tirotropina en suero o plasma."
* #TSH ^property[0].code = #unidad-ucum
* #TSH ^property[0].valueCode = #"u[IU]/mL"

* #T4L "T4 libre" "Tiroxina libre en suero o plasma."
* #T4L ^property[0].code = #unidad-ucum
* #T4L ^property[0].valueCode = #ng/dL


// ─── Microbiología y serología ───────────────────────────────────────────────
//
// Cualitativas: sin unidad. Son las que pueden disparar una declaración obligatoria a Salud Pública
// (ValueSet `catalogo-edo`).

* #LEGIOAG "Antígeno de Legionella en orina" "Detección del antígeno de Legionella pneumophila en orina."
* #COPROSALM "Coprocultivo: Salmonella" "Identificación de Salmonella sp en heces por cultivo."
* #MTBPCR "Mycobacterium tuberculosis por PCR" "Detección de ADN del complejo Mycobacterium tuberculosis por amplificación de ácidos nucleicos."
* #VHAIGM "Anticuerpos IgM frente al virus de la hepatitis A" "Detección de anticuerpos IgM frente al virus de la hepatitis A en suero."
* #SARIGM "Anticuerpos IgM frente al virus del sarampión" "Detección de anticuerpos IgM frente al virus del sarampión en suero."
