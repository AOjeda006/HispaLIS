package es.hispalis.backend.aplicacion.edo;

import es.hispalis.backend.dominio.edo.CatalogoEdo;
import es.hispalis.backend.dominio.edo.ReglaDeDeclaracion;
import es.hispalis.backend.dominio.hecho.Hecho;
import es.hispalis.backend.dominio.hecho.RepositorioDeHechos;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.dominio.resultado.Resultado;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Al validarse un resultado, decide si obliga a declararlo a Salud Pública.
 *
 * <h2>La obligación es real; el destinatario, simulado</h2>
 *
 * <p>Todos los centros sanitarios de Andalucía, <strong>públicos y privados</strong>, forman parte
 * del Sistema de Vigilancia Epidemiológica (Decreto 66/1996), y la relación de enfermedades de
 * declaración obligatoria la fija la Orden de 19 de diciembre de 1996, actualizada por la de 12 de
 * noviembre de 2015. Que este laboratorio sea privado no le exime: es justo lo que hace de esta una
 * regla de negocio con motivo legal y no un adorno. Lo que sí es una simulación es el catálogo
 * concreto y el destinatario, y así está escrito en la guía.
 *
 * <h2>Decide sobre códigos</h2>
 *
 * <p>Este caso de uso <strong>no mira quién es el paciente</strong>, y no porque se haya tenido
 * cuidado: no tiene con qué. Lo que recibe es el agregado {@link Resultado}, que guarda el
 * identificador interno de la persona y nada más — ni nombre, ni NHC, ni NUHSA—. La decisión sale de
 * dos códigos, el de la prueba y el del valor, contrastados con el catálogo. Una regla que
 * necesitase la filiación para decidir estaría mal planteada antes de estar mal escrita.
 *
 * <h2>Qué deja hecho, y qué no</h2>
 *
 * <p>Deja apuntado el hecho {@link TipoDeHecho#RESULTADO_DECLARABLE} en la misma transacción que la
 * validación. Quien lo convierte en un {@code Task}, lo envía a Redalerta y recoge el acuse es el
 * notificador del ítem 48 — que se dispara desde ese hecho, no desde un {@code if} escondido aquí.
 * Separarlo así es lo que permite que el destinatario esté caído y el resultado se valide igual.
 */
@Service
public class DetectarDeclaracionObligatoria {

    private static final Logger LOG = LoggerFactory.getLogger(DetectarDeclaracionObligatoria.class);

    private final CatalogoEdo catalogo;
    private final RepositorioDeHechos hechos;

    public DetectarDeclaracionObligatoria(CatalogoEdo catalogo, RepositorioDeHechos hechos) {
        this.catalogo = catalogo;
        this.hechos = hechos;
    }

    /**
     * @param validado el resultado recién firmado; si todavía le falta una firma, no se declara nada
     * @return la enfermedad que hay que declarar, o vacío si este resultado no obliga a nada
     */
    public Optional<ReglaDeDeclaracion> ejecutar(Resultado validado) {
        Optional<ReglaDeDeclaracion> declarable = validado.obligaADeclarar(catalogo);
        if (declarable.isEmpty()) {
            return Optional.empty();
        }

        hechos.registrar(Hecho.de(
                TipoDeHecho.RESULTADO_DECLARABLE,
                validado.pacienteId(),
                Map.of("observationRef", "Observation/" + validado.id())));

        // Se traza la enfermedad y NO el resultado ni el paciente: al revés que en el hecho. Un log
        // que dijera «Observation/{uuid} declarable» ataría la enfermedad a una persona concreta para
        // cualquiera con acceso al fichero, y los logs no tienen consentimiento. Un recuento de
        // legionelosis sin a quién no es dato de nadie, y es lo que hace falta para saber que la
        // regla se está aplicando.
        LOG.info(
                "Resultado validado declarable a Salud Pública: {}. Queda apuntada la obligación.",
                declarable.orElseThrow().codigoDeEnfermedad());
        return declarable;
    }
}
