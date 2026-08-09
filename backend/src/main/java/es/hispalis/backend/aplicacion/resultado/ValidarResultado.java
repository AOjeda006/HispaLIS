package es.hispalis.backend.aplicacion.resultado;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.hecho.Hecho;
import es.hispalis.backend.dominio.hecho.RepositorioDeHechos;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.dominio.resultado.ValoresCriticos;
import es.hispalis.backend.fhir.resultado.TraductorDeProcedencia;
import es.hispalis.backend.fhir.resultado.TraductorDeResultado;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validación facultativa: el paso que convierte una cifra medida en un resultado publicable.
 *
 * <p>Lo que sale del analizador es una medida. Entre la medida y el informe hay una persona que la
 * contrasta con la clínica y con los controles del día, y que responde de ella. Este caso de uso es
 * ese paso, y por eso escribe <strong>tres cosas en la misma transacción</strong> (§9): el dominio,
 * la proyección del resultado —que pasa a {@code final}— y la procedencia, que es donde queda quién
 * firmó y cuándo.
 *
 * <p>Es una operación y no un {@code PUT} porque no es una modificación del recurso: el valor no
 * cambia. Cambia quién responde de él. Un {@code PUT} obligaría al cliente a mandar el recurso
 * entero para tocar algo que ni siquiera es un campo suyo, y abriría la puerta a colar de paso una
 * corrección del valor — que es otra operación clínica, con reglas propias, y sigue rechazada.
 */
@Service
public class ValidarResultado {

    private final RepositorioDeResultados resultados;
    private final RepositorioDeHechos hechos;
    private final ValoresCriticos criticos;
    private final TraductorDeResultado traductor;
    private final TraductorDeProcedencia traductorDeProcedencia;
    private final DaoRegistry daos;

    public ValidarResultado(
            RepositorioDeResultados resultados,
            RepositorioDeHechos hechos,
            ValoresCriticos criticos,
            TraductorDeResultado traductor,
            TraductorDeProcedencia traductorDeProcedencia,
            DaoRegistry daos) {
        this.resultados = resultados;
        this.hechos = hechos;
        this.criticos = criticos;
        this.traductor = traductor;
        this.traductorDeProcedencia = traductorDeProcedencia;
        this.daos = daos;
    }

    /**
     * @param identidad el resultado que se firma
     * @param facultativo quién responde de él; se deja llegar vacío a propósito para que sea el
     *     dominio quien diga que hace falta, y no una restricción del borde con otro mensaje
     * @param cuando el momento de la firma; si no viene, ahora
     * @return el resultado ya publicado como {@code final}
     * @throws DatoInvalido si el resultado no existe o si no se dice quién valida
     * @throws ReglaDeNegocioIncumplida si ya estaba validado
     */
    @Transactional
    public Observation ejecutar(
            IIdType identidad, Reference facultativo, DateTimeType cuando, RequestDetails peticionHttp) {
        UUID id = identidadDe(identidad);
        Resultado existente = resultados
                .buscarPorId(id)
                .orElseThrow(() ->
                        new DatoInvalido("El resultado %s no está registrado en este laboratorio.".formatted(id)));

        // El catálogo de críticos se le pasa AL AGREGADO, que es quien decide cuántas firmas hacen
        // falta. Preguntarlo aquí y pasar un booleano dejaría la regla en el caso de uso, y con ella
        // fuera del alcance de cualquier otra puerta de entrada.
        Resultado validado = existente.validar(criticos, quienEs(facultativo), momentoDe(cuando));
        resultados.actualizar(validado);

        // Este es el hecho del que colgarán el `ORU^R01` saliente y el notificador EDO. Ni la cifra
        // ni quién firmó viajan en él: van las dos referencias que hay que mirar para saberlo.
        //
        // Solo se apunta cuando el resultado queda VALIDADO. La primera firma de un crítico no es un
        // resultado publicable, y anunciarla haría que el HIS incorporase a la historia una cifra a
        // la que todavía le falta la revisión que el laboratorio se ha impuesto.
        if (validado.estaValidado()) {
            hechos.registrar(Hecho.de(
                    TipoDeHecho.RESULTADO_VALIDADO,
                    validado.pacienteId(),
                    Map.of(
                            "observationRef",
                            "Observation/" + validado.id(),
                            "provenanceRef",
                            "Provenance/"
                                    + TraductorDeProcedencia.identidadDe(
                                            validado, validado.firmas().size()))));
        }

        // Las procedencias van primero porque son las que dan fe del cambio: si algo revienta
        // después, la transacción entera se deshace y no queda ni un resultado `final` sin firma ni
        // una firma sin resultado. Todas las escrituras son de la misma transacción, no de pasos
        // encadenados.
        traductorDeProcedencia
                .aFhir(validado)
                .forEach(procedencia -> daos.getResourceDao(Provenance.class).update(procedencia, peticionHttp));

        return (Observation) daos.getResourceDao(Observation.class)
                .update(traductor.aFhir(validado), peticionHttp)
                .getResource();
    }

    /**
     * Quién firma, tal y como se guarda: la referencia literal al facultativo.
     *
     * <p>El dominio no comprueba que exista —el directorio de profesionales es dato maestro y no
     * tiene agregado (§10)—, pero <strong>la proyección sí</strong>: al escribir el
     * {@code Provenance} el servidor exige que la referencia resuelva, y la misma transacción tumba
     * la validación entera si no lo hace. Es lo que se quiere: una firma con un nombre que no está
     * dado de alta no es una firma, es un texto.
     */
    private static String quienEs(Reference facultativo) {
        return facultativo == null ? null : facultativo.getReference();
    }

    private static Instant momentoDe(DateTimeType cuando) {
        return cuando == null || cuando.getValue() == null
                ? null
                : cuando.getValue().toInstant();
    }

    private static UUID identidadDe(IIdType identidad) {
        try {
            return UUID.fromString(identidad.getIdPart());
        } catch (IllegalArgumentException e) {
            throw new DatoInvalido("«%s» no es un resultado de este laboratorio.".formatted(identidad.getIdPart()));
        }
    }
}
