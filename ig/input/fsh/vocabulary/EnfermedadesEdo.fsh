// Las enfermedades de declaración obligatoria que este laboratorio puede llegar a detectar.
//
// ⚠️ VEROSÍMIL, NO FIEL, y está escrito también en la descripción del recurso porque es lo que un
// lector necesita saber antes de usarlo. Lo REAL es la obligación: todos los centros sanitarios de
// Andalucía, públicos *y privados*, forman parte del SVEA (Decreto 66/1996), y la relación de EDO la
// fija la Orden de 19 de diciembre de 1996, actualizada por la de 12 de noviembre de 2015. Lo
// SIMULADO es esta lista —la real es mucho más amplia— y el destinatario.
//
// Es un CodeSystem propio y no CIE-10-ES, que es lo que el proyecto usa para diagnósticos (§4.3), por
// una razón concreta: una EDO no es un diagnóstico del paciente. Es una entrada de una lista
// administrativa, y la RENAVE la publica con su propia nomenclatura y sus propios criterios de caso.
// Codificarla con el CIE-10 del cuadro clínico afirmaría un diagnóstico que el laboratorio no ha
// hecho: lo que el laboratorio sabe es que una muestra dio positivo, no que la persona esté enferma.

CodeSystem: EnfermedadesEdo
Id: enfermedades-edo
Title: "Enfermedades de declaración obligatoria"
Description: """
Enfermedades que obligan a declarar a Salud Pública cuando el laboratorio confirma un resultado
positivo.

**Esta relación es una simulación verosímil, no la relación oficial.** La real la fija la normativa
andaluza citada arriba y es mucho más amplia; aquí hay una muestra suficiente para que la regla sea
demostrable de extremo a extremo. El destinatario de la declaración —Redalerta— también se modela de
forma verosímil: su contrato no es público.

Qué prueba declara cada una y con qué resultado vive en `CodeSystem/catalogo-pruebas`, en las
propiedades `enfermedad-edo` y `resultado-que-declara` de cada concepto. Aquí solo están las
enfermedades: mezclar las dos cosas en un sitio sería confundir el catálogo de lo que se oferta con
el de lo que se declara.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(EnfermedadesDeclarables)

* #LEGIONELOSIS "Legionelosis" "Infección por Legionella pneumophila. Declaración urgente: un caso aislado puede ser la punta de un brote de origen ambiental."
* #SALMONELOSIS "Salmonelosis" "Infección por Salmonella no tifoidea."
* #TUBERCULOSIS "Tuberculosis" "Enfermedad por el complejo Mycobacterium tuberculosis."
* #HEPATITIS-A "Hepatitis A" "Infección aguda por el virus de la hepatitis A."
* #SARAMPION "Sarampión" "Infección por el virus del sarampión. En un país con eliminación declarada, un solo caso obliga a investigar."


ValueSet: EnfermedadesDeclarables
Id: enfermedades-declarables
Title: "Enfermedades declarables a Salud Pública"
Description: "Todas las de `CodeSystem/enfermedades-edo`. Es el conjunto al que apunta `NotificacionEDO`."

* include codes from system EnfermedadesEdo
