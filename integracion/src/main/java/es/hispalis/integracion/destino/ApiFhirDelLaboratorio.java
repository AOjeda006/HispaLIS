package es.hispalis.integracion.destino;

import java.util.Optional;
import org.hl7.fhir.r5.model.Patient;

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
 */
public interface ApiFhirDelLaboratorio {

    /**
     * Busca al paciente por su número de historia.
     *
     * <p>La búsqueda va <strong>por el cuerpo</strong> ({@code POST …/_search}) y no por la URL: el
     * NHC identifica a una persona y no puede acabar en un log de acceso ni en el historial de un
     * proxy. Es la misma regla que aplica la web profesional (ADR-0016).
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
