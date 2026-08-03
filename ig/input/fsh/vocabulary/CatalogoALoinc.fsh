// El traductor del dialecto local al lenguaje común (§5 del diseño).
//
// ⚠️ R5 renombra dos elementos de `ConceptMap`: `source[x]`/`target[x]` pasan a ser
// `sourceScope[x]`/`targetScope[x]`, y `element.target.equivalence` pasa a ser `relationship`, con
// un conjunto de códigos distinto. Un `ConceptMap` de R4 copiado tal cual no valida.
//
// Todos los códigos LOINC de este mapeo están verificados contra la tabla Core de LOINC 2.82:
// existen, están en estado ACTIVE y ninguno lleva aviso de copyright de terceros.

Instance: CatalogoALoinc
InstanceOf: ConceptMap
Usage: #definition
Title: "Catálogo del laboratorio → LOINC"
Description: """
Traduce cada código del catálogo local al término LOINC equivalente.

**La unidad manda sobre el nombre.** El laboratorio informa la glucosa en `mg/dL`, así que el término
correcto es el de *masa/volumen* (`2345-7`) y no el de *moles/volumen* (`14749-6`), que es el que se
elegiría buscando «glucosa» por texto. Lo mismo con la creatinina, la urea y el colesterol. Un
término LOINC se elige por sus **seis ejes**, no por su nombre.

**La urea no es el nitrógeno ureico.** En España se informa urea (`3091-6`); en el mundo anglosajón,
nitrógeno ureico o BUN (`3094-0`). Son magnitudes distintas con un factor de conversión de 2,14
entre ellas, y confundirlas multiplica el resultado por dos.

**No todas las correspondencias son equivalencias.** Donde el término LOINC fija un método que el
código local no fija —el recuento automatizado del hemograma, el cultivo específico, la amplificación
con sonda—, la relación declarada es `source-is-broader-than-target`. Forzar `equivalent` para que
quede uniforme sería inventar una precisión que el catálogo no tiene.

Los `display` de los términos LOINC van en **inglés y sin alterar**, que es como los publica LOINC:
la licencia no permite cambiar el contenido de sus campos, y la variante lingüística española de
LOINC 2.82 es parcial —traduce los ejes, no el nombre largo—. El español que ve el usuario es el
`display` del catálogo local, que es el lado del mapeo que sí es nuestro.
"""

* url = "https://aojeda006.github.io/HispaLIS/fhir/ConceptMap/catalogo-a-loinc"
* name = "CatalogoALoinc"
* status = #draft
* experimental = true

* sourceScopeCanonical = Canonical(PruebasDelCatalogo)
* targetScopeUri = "http://loinc.org"

* group[+].source = Canonical(CatalogoPruebas)
* group[=].target = "http://loinc.org"

// ─── Bioquímica ──────────────────────────────────────────────────────────────────
* group[=].element[+].code = #GLU
* group[=].element[=].display = "Glucosa"
* group[=].element[=].target[+].code = #2345-7
* group[=].element[=].target[=].display = "Glucose [Mass/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #CREA
* group[=].element[=].display = "Creatinina"
* group[=].element[=].target[+].code = #2160-0
* group[=].element[=].target[=].display = "Creatinine [Mass/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #UREA
* group[=].element[=].display = "Urea"
* group[=].element[=].target[+].code = #3091-6
* group[=].element[=].target[=].display = "Urea [Mass/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #NA
* group[=].element[=].display = "Sodio"
* group[=].element[=].target[+].code = #2951-2
* group[=].element[=].target[=].display = "Sodium [Moles/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #K
* group[=].element[=].display = "Potasio"
* group[=].element[=].target[+].code = #2823-3
* group[=].element[=].target[=].display = "Potassium [Moles/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #COLT
* group[=].element[=].display = "Colesterol total"
* group[=].element[=].target[+].code = #2093-3
* group[=].element[=].target[=].display = "Cholesterol [Mass/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #GOT
* group[=].element[=].display = "GOT (AST)"
* group[=].element[=].target[+].code = #1920-8
* group[=].element[=].target[=].display = "Aspartate aminotransferase [Enzymatic activity/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #GPT
* group[=].element[=].display = "GPT (ALT)"
* group[=].element[=].target[+].code = #1742-6
* group[=].element[=].target[=].display = "Alanine aminotransferase [Enzymatic activity/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #PCR
* group[=].element[=].display = "Proteína C reactiva"
* group[=].element[=].target[+].code = #1988-5
* group[=].element[=].target[=].display = "C reactive protein [Mass/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #HBA1C
* group[=].element[=].display = "Hemoglobina glicosilada"
* group[=].element[=].target[+].code = #4548-4
* group[=].element[=].target[=].display = "Hemoglobin A1c/Hemoglobin.total in Blood"
* group[=].element[=].target[=].relationship = #equivalent


// ─── Hematología — el código local no fija el método; el término LOINC exige recuento automatizado ───
* group[=].element[+].code = #HB
* group[=].element[=].display = "Hemoglobina"
* group[=].element[=].target[+].code = #718-7
* group[=].element[=].target[=].display = "Hemoglobin [Mass/volume] in Blood"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #HTO
* group[=].element[=].display = "Hematocrito"
* group[=].element[=].target[+].code = #4544-3
* group[=].element[=].target[=].display = "Hematocrit [Volume Fraction] of Blood by Automated count"
* group[=].element[=].target[=].relationship = #source-is-broader-than-target

* group[=].element[+].code = #LEU
* group[=].element[=].display = "Leucocitos"
* group[=].element[=].target[+].code = #6690-2
* group[=].element[=].target[=].display = "Leukocytes [#/volume] in Blood by Automated count"
* group[=].element[=].target[=].relationship = #source-is-broader-than-target

* group[=].element[+].code = #PLAQ
* group[=].element[=].display = "Plaquetas"
* group[=].element[=].target[+].code = #777-3
* group[=].element[=].target[=].display = "Platelets [#/volume] in Blood by Automated count"
* group[=].element[=].target[=].relationship = #source-is-broader-than-target


// ─── Hormonas — TSH y T4 libre son el par de la prueba refleja ───────────────────
* group[=].element[+].code = #TSH
* group[=].element[=].display = "TSH (tirotropina)"
* group[=].element[=].target[+].code = #3016-3
* group[=].element[=].target[=].display = "Thyrotropin [Units/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #T4L
* group[=].element[=].display = "T4 libre"
* group[=].element[=].target[+].code = #3024-7
* group[=].element[=].target[=].display = "Thyroxine (T4) free [Mass/volume] in Serum or Plasma"
* group[=].element[=].target[=].relationship = #equivalent


// ─── Microbiología y serología — las que pueden disparar una declaración obligatoria ───
* group[=].element[+].code = #LEGIOAG
* group[=].element[=].display = "Antígeno de Legionella en orina"
* group[=].element[=].target[+].code = #31870-9
* group[=].element[=].target[=].display = "Legionella pneumophila Ag [Presence] in Urine"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #COPROSALM
* group[=].element[=].display = "Coprocultivo: Salmonella"
* group[=].element[=].target[+].code = #20955-1
* group[=].element[=].target[=].display = "Salmonella sp identified in Stool by Organism specific culture"
* group[=].element[=].target[=].relationship = #source-is-broader-than-target

* group[=].element[+].code = #MTBPCR
* group[=].element[=].display = "Mycobacterium tuberculosis por PCR"
* group[=].element[=].target[+].code = #38379-4
* group[=].element[=].target[=].display = "Mycobacterium tuberculosis complex DNA [Presence] in Specimen by NAA with probe detection"
* group[=].element[=].target[=].relationship = #source-is-broader-than-target

* group[=].element[+].code = #VHAIGM
* group[=].element[=].display = "Anticuerpos IgM frente al virus de la hepatitis A"
* group[=].element[=].target[+].code = #22314-9
* group[=].element[=].target[=].display = "Hepatitis A virus IgM Ab [Presence] in Serum"
* group[=].element[=].target[=].relationship = #equivalent

* group[=].element[+].code = #SARIGM
* group[=].element[=].display = "Anticuerpos IgM frente al virus del sarampión"
* group[=].element[=].target[+].code = #21503-8
* group[=].element[=].target[=].display = "Measles virus IgM Ab [Presence] in Serum"
* group[=].element[=].target[=].relationship = #equivalent
