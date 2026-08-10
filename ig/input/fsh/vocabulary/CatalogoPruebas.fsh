// El «dialecto» local del laboratorio: los códigos con los que el personal pide y firma las pruebas.
//
// Es el eje de terminología del proyecto (§5 del diseño). El propio Módulo de Pruebas Analíticas de
// Diraya describe así su función: «cada SIL mantiene su autonomía para utilizar la nomenclatura más
// adecuada, pero al intercambiar información la base de datos actúa como traductor de los dialectos
// locales a un lenguaje común». Ese traductor es `ConceptMap/catalogo-a-loinc`.
//
// La propiedad `unidad-ucum` vive AQUÍ y no en una tabla aparte: el generador de datos sintéticos y
// el backend tienen que consumir el mismo catálogo que la IG, nunca una lista paralela.
//
// Los LÍMITES CRÍTICOS viven aquí por lo mismo, y hay que distinguirlos de los rangos de referencia,
// que NO están en la guía: un rango de normalidad depende del método y del analizador de cada
// laboratorio, y dos laboratorios que usan el mismo código `CREA` publican rangos distintos sin
// contradecirse. Un límite crítico es otra cosa — el umbral acordado con quien recibe la llamada—, y
// tiene que estar publicado para que el clínico sepa qué dispara un aviso. Su procedencia va en el
// propio concepto: ver `ValueSet/valores-criticos`.

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
// `Coding` y no `code`: la unidad va con su sistema, que es la regla dura del proyecto. Además,
// una propiedad de tipo `code` se interpreta como código de ESTE CodeSystem, que no es lo que se
// quiere decir: `mg/dL` no es una prueba del catálogo.
* ^property[0].type = #Coding

// Los tres de abajo van juntos o no van: un umbral sin unidad no se puede comparar con nada y un
// umbral sin procedencia no se puede auditar. Los límites se expresan SIEMPRE en la `unidad-ucum`
// del propio concepto — no llevan la suya, porque dos unidades para la misma prueba serían la
// primera forma de equivocarse.
* ^property[1].code = #limite-critico-bajo
* ^property[1].description = "Cifra en la que un resultado pasa a ser crítico por lo bajo, en la `unidad-ucum` del concepto. Ausente cuando la prueba no tiene límite crítico inferior."
* ^property[1].type = #decimal
* ^property[2].code = #limite-critico-alto
* ^property[2].description = "Cifra en la que un resultado pasa a ser crítico por lo alto, en la `unidad-ucum` del concepto. Ausente cuando la prueba no tiene límite crítico superior."
* ^property[2].type = #decimal
* ^property[3].code = #procedencia-del-valor-critico
* ^property[3].description = "De dónde salen los dos límites, con la referencia concreta. Obligatoria en todo concepto que declare alguno: un umbral sin fuente no se usa."
* ^property[3].type = #string

// Las dos de abajo son la REGLA REFLEJA, y van juntas por lo mismo que las tres de arriba: una
// prueba que se añade sola sin poder decir por qué aparece en el informe como una determinación que
// nadie pidió. `type = #code` a propósito: una propiedad de tipo `code` se interpreta como código de
// ESTE CodeSystem, y aquí eso es exactamente lo que se quiere decir — la refleja es otra prueba del
// catálogo. Es el caso contrario al de `unidad-ucum`, que por eso es `Coding`.
* ^property[4].code = #prueba-refleja
* ^property[4].description = "Prueba del catálogo que el laboratorio añade por su cuenta cuando ESTA sale alterada, es decir, fuera de su rango de referencia. Ausente en las pruebas que no disparan ninguna."
* ^property[4].type = #code
* ^property[5].code = #motivo-de-la-refleja
* ^property[5].description = "La frase que explica la refleja al que lee el informe, en español y ya redactada. Va tal cual a `Observation.triggeredBy.reason`, y de ahí a la pantalla: quien redacta la regla redacta también cómo se cuenta."
* ^property[5].type = #string

// Las dos de abajo son la REGLA DE DECLARACIÓN OBLIGATORIA, y también van juntas: una prueba que
// declara sin decir con qué resultado declararía todos —incluidos los negativos—, y un criterio de
// positividad sin enfermedad no dice a Salud Pública de qué se le está avisando.
//
// ⚠️ R5 tiene un sitio hecho a medida para esto y NO se usa: `ConceptMap.additionalAttribute` más
// `element.target.dependsOn`, que dice literalmente «este mapeo solo vale si tal atributo tiene tal
// valor» —en R4 era `dependsOn.property`, una `uri`, y no existía `additionalAttribute`—. Se
// descartó por una razón medida, no por desconocerlo: HAPI 8.10 no lo implementa ni a la entrada ni
// a la salida (`TranslationQuery` no tiene `dependency`, y el `match` de `$translate` no devuelve
// `dependsOn`), así que publicarlo dejaría el criterio en un elemento que el servidor no sirve y el
// backend tendría que leerlo de otro sitio — es decir, dos fuentes de verdad para la misma regla.
// Como propiedades del concepto, un solo `$lookup` trae la enfermedad y el criterio a la vez, que es
// el mismo patrón que ya usan el umbral crítico y la prueba refleja.
* ^property[6].code = #enfermedad-edo
* ^property[6].description = "Enfermedad de declaración obligatoria que esta prueba confirma. Ausente en las pruebas que no obligan a declarar, que son casi todas."
* ^property[6].type = #Coding
* ^property[7].code = #resultado-que-declara
* ^property[7].description = "Valor cualitativo que, en esta prueba, dispara la declaración. Obligatorio en todo concepto que declare una `enfermedad-edo`: sin él se declararía también un negativo."
* ^property[7].type = #Coding


// ─── Bioquímica ──────────────────────────────────────────────────────────────

* #GLU "Glucosa" "Glucosa en suero o plasma."
* #GLU ^property[0].code = #unidad-ucum
* #GLU ^property[0].valueCoding = $UCUM#"mg/dL"
* #GLU ^property[1].code = #limite-critico-bajo
* #GLU ^property[1].valueDecimal = 45
* #GLU ^property[2].code = #limite-critico-alto
* #GLU ^property[2].valueDecimal = 400
* #GLU ^property[3].code = #procedencia-del-valor-critico
* #GLU ^property[3].valueString = "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."

// Sin límite crítico inferior: la tabla 6 no declara ninguno para la creatinina, y ponerlo sería
// inventarlo. Que falte no es un descuido — es lo que dice la fuente.
* #CREA "Creatinina" "Creatinina en suero o plasma."
* #CREA ^property[0].code = #unidad-ucum
* #CREA ^property[0].valueCoding = $UCUM#"mg/dL"
* #CREA ^property[1].code = #limite-critico-alto
* #CREA ^property[1].valueDecimal = 5
* #CREA ^property[2].code = #procedencia-del-valor-critico
* #CREA ^property[2].valueString = "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."

// En España se informa la UREA; en el mundo anglosajón, el NITRÓGENO UREICO (BUN). Son magnitudes
// distintas con códigos LOINC distintos y un factor de conversión de 2,14 entre ellas.
* #UREA "Urea" "Urea en suero o plasma."
* #UREA ^property[0].code = #unidad-ucum
* #UREA ^property[0].valueCoding = $UCUM#"mg/dL"
* #UREA ^property[1].code = #limite-critico-alto
* #UREA ^property[1].valueDecimal = 170
* #UREA ^property[2].code = #procedencia-del-valor-critico
* #UREA ^property[2].valueString = "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."

* #NA "Sodio" "Sodio en suero o plasma."
* #NA ^property[0].code = #unidad-ucum
* #NA ^property[0].valueCoding = $UCUM#"mmol/L"
* #NA ^property[1].code = #limite-critico-bajo
* #NA ^property[1].valueDecimal = 120
* #NA ^property[2].code = #limite-critico-alto
* #NA ^property[2].valueDecimal = 160
* #NA ^property[3].code = #procedencia-del-valor-critico
* #NA ^property[3].valueString = "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."

* #K "Potasio" "Potasio en suero o plasma."
* #K ^property[0].code = #unidad-ucum
* #K ^property[0].valueCoding = $UCUM#"mmol/L"
* #K ^property[1].code = #limite-critico-bajo
* #K ^property[1].valueDecimal = 2.8
* #K ^property[2].code = #limite-critico-alto
* #K ^property[2].valueDecimal = 6.3
* #K ^property[3].code = #procedencia-del-valor-critico
* #K ^property[3].valueString = "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."

* #COLT "Colesterol total" "Colesterol total en suero o plasma."
* #COLT ^property[0].code = #unidad-ucum
* #COLT ^property[0].valueCoding = $UCUM#"mg/dL"

* #GOT "GOT (AST)" "Aspartato aminotransferasa en suero o plasma."
* #GOT ^property[0].code = #unidad-ucum
* #GOT ^property[0].valueCoding = $UCUM#"U/L"

* #GPT "GPT (ALT)" "Alanina aminotransferasa en suero o plasma."
* #GPT ^property[0].code = #unidad-ucum
* #GPT ^property[0].valueCoding = $UCUM#"U/L"

// «PCR» es ambiguo en un laboratorio español: proteína C reactiva y reacción en cadena de la
// polimerasa. Aquí es la primera, y la definición es lo que lo resuelve. Esa ambigüedad es
// exactamente la razón de que un dialecto local necesite mapearse a un lenguaje común.
* #PCR "Proteína C reactiva" "Proteína C reactiva en suero o plasma. No confundir con la reacción en cadena de la polimerasa."
* #PCR ^property[0].code = #unidad-ucum
* #PCR ^property[0].valueCoding = $UCUM#"mg/L"

* #HBA1C "Hemoglobina glicosilada" "Fracción de hemoglobina A1c sobre hemoglobina total, en sangre."
* #HBA1C ^property[0].code = #unidad-ucum
* #HBA1C ^property[0].valueCoding = $UCUM#"%"


// ─── Hematología ─────────────────────────────────────────────────────────────

* #HB "Hemoglobina" "Hemoglobina en sangre."
* #HB ^property[0].code = #unidad-ucum
* #HB ^property[0].valueCoding = $UCUM#"g/dL"

* #HTO "Hematocrito" "Fracción de volumen de eritrocitos en sangre."
* #HTO ^property[0].code = #unidad-ucum
* #HTO ^property[0].valueCoding = $UCUM#"%"

* #LEU "Leucocitos" "Recuento de leucocitos en sangre."
* #LEU ^property[0].code = #unidad-ucum
* #LEU ^property[0].valueCoding = $UCUM#"10*3/uL"

* #PLAQ "Plaquetas" "Recuento de plaquetas en sangre."
* #PLAQ ^property[0].code = #unidad-ucum
* #PLAQ ^property[0].valueCoding = $UCUM#"10*3/uL"


// ─── Hormonas ────────────────────────────────────────────────────────────────

// TSH y T4 libre son el par de la prueba refleja del diseño: una TSH alterada dispara una T4 libre,
// que se enlaza con `Observation.triggeredBy`. La regla vive AQUÍ y no en el código del laboratorio:
// cambiar a qué prueba refleja la TSH tiene que ser cambiar un catálogo, no desplegar un backend.
//
// A diferencia de los límites críticos, este umbral NO necesita fuente externa publicada: «alterada»
// significa fuera del rango de referencia que publica este laboratorio con su método y su
// analizador, y a qué prueba refleja es su protocolo, acordado con sus clínicos. Un valor crítico es
// lo contrario — un acuerdo con quien recibe la llamada— y por eso el ítem 43 exigió cita y este no.
* #TSH "TSH (tirotropina)" "Tirotropina en suero o plasma."
* #TSH ^property[0].code = #unidad-ucum
* #TSH ^property[0].valueCoding = $UCUM#"u[IU]/mL"
* #TSH ^property[1].code = #prueba-refleja
* #TSH ^property[1].valueCode = #T4L
* #TSH ^property[2].code = #motivo-de-la-refleja
* #TSH ^property[2].valueString = "Derivada de un TSH alterado: el protocolo de función tiroidea del laboratorio añade la T4 libre cuando la TSH cae fuera de su rango de referencia."

* #T4L "T4 libre" "Tiroxina libre en suero o plasma."
* #T4L ^property[0].code = #unidad-ucum
* #T4L ^property[0].valueCoding = $UCUM#"ng/dL"


// ─── Microbiología y serología ───────────────────────────────────────────────
//
// Cualitativas: sin unidad, y su valor viene de `CodeSystem/resultados-cualitativos`. Son las que
// disparan una declaración obligatoria a Salud Pública, y aquí es donde está escrito CUÁL y CON QUÉ
// RESULTADO. El `ValueSet/catalogo-edo` publica la lista para poder leerla de un vistazo; la regla
// que ejecuta el laboratorio son estas dos propiedades.
//
// Fíjate en que el criterio es `#POS` y no «cualquier cosa que no sea negativo»: un `#IND` no
// declara. Una serología en zona gris no es un caso, y declararla llenaría el sistema de vigilancia
// de casos que luego hay que retirar.

* #LEGIOAG "Antígeno de Legionella en orina" "Detección del antígeno de Legionella pneumophila en orina."
* #LEGIOAG ^property[0].code = #enfermedad-edo
* #LEGIOAG ^property[0].valueCoding = EnfermedadesEdo#LEGIONELOSIS
* #LEGIOAG ^property[1].code = #resultado-que-declara
* #LEGIOAG ^property[1].valueCoding = ResultadosCualitativos#POS

* #COPROSALM "Coprocultivo: Salmonella" "Identificación de Salmonella sp en heces por cultivo."
* #COPROSALM ^property[0].code = #enfermedad-edo
* #COPROSALM ^property[0].valueCoding = EnfermedadesEdo#SALMONELOSIS
* #COPROSALM ^property[1].code = #resultado-que-declara
* #COPROSALM ^property[1].valueCoding = ResultadosCualitativos#POS

* #MTBPCR "Mycobacterium tuberculosis por PCR" "Detección de ADN del complejo Mycobacterium tuberculosis por amplificación de ácidos nucleicos."
* #MTBPCR ^property[0].code = #enfermedad-edo
* #MTBPCR ^property[0].valueCoding = EnfermedadesEdo#TUBERCULOSIS
* #MTBPCR ^property[1].code = #resultado-que-declara
* #MTBPCR ^property[1].valueCoding = ResultadosCualitativos#POS

* #VHAIGM "Anticuerpos IgM frente al virus de la hepatitis A" "Detección de anticuerpos IgM frente al virus de la hepatitis A en suero."
* #VHAIGM ^property[0].code = #enfermedad-edo
* #VHAIGM ^property[0].valueCoding = EnfermedadesEdo#HEPATITIS-A
* #VHAIGM ^property[1].code = #resultado-que-declara
* #VHAIGM ^property[1].valueCoding = ResultadosCualitativos#POS

* #SARIGM "Anticuerpos IgM frente al virus del sarampión" "Detección de anticuerpos IgM frente al virus del sarampión en suero."
* #SARIGM ^property[0].code = #enfermedad-edo
* #SARIGM ^property[0].valueCoding = EnfermedadesEdo#SARAMPION
* #SARIGM ^property[1].code = #resultado-que-declara
* #SARIGM ^property[1].valueCoding = ResultadosCualitativos#POS
