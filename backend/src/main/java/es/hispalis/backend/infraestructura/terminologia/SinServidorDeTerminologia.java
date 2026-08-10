package es.hispalis.backend.infraestructura.terminologia;

import es.hispalis.backend.dominio.edo.ReglaDeDeclaracion;
import es.hispalis.backend.dominio.resultado.NoSeSabeSiEsCritico;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.ResultadosCualitativos;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.util.Optional;
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
    public CodeableConcept valorCualitativo(String codigoLocal) {
        return new CodeableConcept()
                .addCoding(new Coding().setSystem(ResultadosCualitativos.SYSTEM).setCode(codigoLocal));
    }

    /**
     * Sin catálogo no hay nada que declarar, y eso no deja ninguna obligación sin cumplir.
     *
     * <p>Parece la degradación peligrosa —callarse una declaración obligatoria es incumplir la ley—,
     * pero no llega a darse: esto solo se pregunta al validar un resultado, y validar aquí es
     * imposible, porque {@link #umbralDe} lanza. Sin terminología no hay validación, y sin validación
     * no hay nada que declarar. La puerta ya está cerrada un paso antes.
     */
    @Override
    public Optional<ReglaDeDeclaracion> declaracionDe(String codigoDePrueba) {
        return Optional.empty();
    }

    @Override
    public void exigirQueLaPruebaExiste(String codigoLocal) {
        // Sin autoridad a la que preguntar, rechazar sería inventarse la respuesta.
    }

    /**
     * Aquí <strong>no</strong> se degrada, y es la única excepción de esta clase.
     *
     * <p>Con el nombre de una prueba, callarse es aceptable: el código es el dato y el nombre es su
     * adorno. Con un umbral crítico, callarse es contestar «no es crítico» — que no es una versión
     * pobre de la respuesta, es la respuesta contraria, y la paga alguien a quien no se llama.
     */
    @Override
    public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
        throw new NoSeSabeSiEsCritico(
                "No hay servidor de terminología configurado, así que no se puede saber si «%s» tiene umbral crítico. "
                                .formatted(codigoDePrueba)
                        + "Los umbrales los publica la guía y no hay a quién preguntárselos.");
    }

    /**
     * Y aquí sí se degrada, aunque la regla también venga del catálogo.
     *
     * <p>La diferencia con el umbral está en <strong>qué se pierde</strong>: sin refleja falta una
     * prueba añadida, que el facultativo va a echar en falta al ver la TSH marcada como alta; sin
     * umbral se afirma que una cifra peligrosa no lo es. Omitir no es lo mismo que mentir.
     */
    @Override
    public Optional<ReglaRefleja> reflejaDe(String codigoDePrueba) {
        return Optional.empty();
    }
}
