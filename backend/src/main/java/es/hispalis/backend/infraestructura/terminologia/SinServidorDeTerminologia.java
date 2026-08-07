package es.hispalis.backend.infraestructura.terminologia;

import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;

/**
 * El laboratorio sin servidor de terminología configurado: publica el código y no valida nada.
 *
 * <p>Existe para los tests que no van de terminología y para arrancar en local sin levantar la pila
 * entera. <strong>No es un catálogo de repuesto</strong>: no lleva ni un código dentro, que es la
 * diferencia entre degradar y tener una lista paralela escondida.
 *
 * <p>Es exactamente lo que publicaba el laboratorio antes del ítem 32, así que su comportamiento no
 * es una novedad ni una pérdida: es el punto de partida.
 */
public class SinServidorDeTerminologia implements Terminologia {

    @Override
    public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
        return new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(codigoLocal));
    }

    @Override
    public void exigirQueLaPruebaExiste(String codigoLocal) {
        // Sin autoridad a la que preguntar, rechazar sería inventarse la respuesta.
    }
}
