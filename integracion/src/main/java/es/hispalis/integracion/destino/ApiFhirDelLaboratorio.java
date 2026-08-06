package es.hispalis.integracion.destino;

import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;

/**
 * El destino de los canales: la API FHIR del laboratorio.
 *
 * <p>Es un puerto y no una llamada suelta porque de él cuelga la regla que más caro sale saltarse:
 * <strong>el motor escribe por la API pública, como un cliente más</strong> (D5). No hay atajo al
 * dominio, no hay acceso a su base de datos y no hay «modo interno». Las mismas validaciones, los
 * mismos invariantes y la misma auditoría que si el mensaje lo hubiera escrito una persona desde la
 * web.
 *
 * <p>La contrapartida es que este motor <strong>no puede</strong> hacer nada que la API no ofrezca, y
 * eso es exactamente lo que se quiere: si un canal necesita algo que la API no expone, lo que falta
 * es una operación en el laboratorio, no un atajo aquí.
 *
 * <h2>Por qué hay un «buscar» por cada «registrar»</h2>
 *
 * <p>Es lo que sostiene D22. El motor escribe recurso a recurso y la atomicidad la pone el reproceso,
 * así que <strong>reaplicar un mensaje tiene que ser inofensivo</strong>. Cada canal pregunta antes
 * de escribir, con la clave de negocio del recurso —el número de volante, el de acceso de la
 * muestra—, y no con un contador propio: el motor no lleva libro de lo que escribió, porque un libro
 * propio se desincroniza y entonces el reproceso duplica justo cuando más falta hacía que no lo
 * hiciera. Quien sabe qué hay en el laboratorio es el laboratorio.
 *
 * <p>Todas las búsquedas van por {@code POST …/_search} (ADR-0016).
 */
public interface ApiFhirDelLaboratorio {

    /**
     * Busca al paciente por su número de historia.
     *
     * @return la referencia {@code Patient/<id>}, o vacío si no está registrado
     */
    Optional<String> buscarPacientePorNhc(String nhc);

    /**
     * Da de alta al paciente.
     *
     * @return la referencia {@code Patient/<id>} que asignó el laboratorio
     * @throws ElLaboratorioRechaza si la API responde un error
     */
    String darDeAltaPaciente(Patient paciente);

    /**
     * Corrige la filiación de un paciente ya registrado.
     *
     * @param referencia la referencia {@code Patient/<id>} devuelta por la búsqueda
     * @return la misma referencia
     * @throws ElLaboratorioRechaza si la API responde un error
     */
    String corregirPaciente(String referencia, Patient paciente);

    /**
     * Busca una línea concreta de un volante.
     *
     * <p>La clave de negocio de una línea es <strong>el número de volante más la prueba</strong>: un
     * volante agrupa varias líneas y cada una es de una prueba distinta. Buscar solo por volante
     * confundiría la glucosa con la creatinina del mismo papel.
     *
     * @return la referencia {@code ServiceRequest/<id>}, o vacío si esa línea no está
     */
    Optional<String> buscarLinea(String numeroDeVolante, String codigoDePrueba);

    /** Registra una línea de petición. */
    String registrarLinea(ServiceRequest linea);

    /**
     * Busca una muestra por su número de acceso, que es su identificador de negocio.
     *
     * @return la referencia {@code Specimen/<id>}, o vacío
     */
    Optional<String> buscarEspecimen(String numeroDeAcceso);

    /** Registra una muestra. */
    String registrarEspecimen(Specimen especimen);

    /**
     * Busca un resultado ya informado para esa muestra y esa prueba.
     *
     * <p>Un resultado no tiene identificador de negocio propio, así que la clave es la pareja
     * muestra + prueba: dos cifras de la misma prueba sobre la misma muestra son el mismo resultado
     * reenviado, no dos determinaciones. Si algún día hicieran falta repeticiones, harían falta con
     * ellas un identificador que las distinga y una regla clínica que diga cuál manda.
     *
     * @return la referencia {@code Observation/<id>}, o vacío
     */
    Optional<String> buscarResultado(String especimenRef, String codigoDePrueba);

    /** Informa un resultado. Entra siempre como preliminar: el analizador mide, no valida. */
    String informarResultado(Observation resultado);

    /** Lee un informe ya emitido. Lo usa el canal saliente. */
    DiagnosticReport leerInforme(String referencia);

    /** Lee los resultados que cita un informe, en el orden en que los cita. */
    List<Observation> leerResultados(List<String> referencias);

    /** Lee un paciente por su referencia. */
    Patient leerPaciente(String referencia);

    /**
     * La API contestó que no.
     *
     * <p>Lleva el texto del {@code OperationOutcome} porque es lo único que explica al operador del
     * HIS qué tiene que arreglar. Un «error al escribir» no lo explica.
     */
    class ElLaboratorioRechaza extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ElLaboratorioRechaza(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
