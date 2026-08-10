package es.hispalis.backend.infraestructura.bus;

import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import java.util.Optional;

/**
 * A qué tópico va cada tipo de hecho.
 *
 * <p>El {@code switch} es <strong>exhaustivo sobre el enumerado y sin {@code default}</strong>, y eso
 * es lo que hace este fichero. Añadir un {@link TipoDeHecho} nuevo deja de compilar hasta que alguien
 * diga a dónde va: la alternativa —un {@code default} que devuelve «ninguno»— haría que un hecho
 * nuevo se quedara callado en el {@code outbox} sin que nadie se enterara hasta que un consumidor
 * preguntara por qué no le llega.
 */
final class RutaDelHecho {

    private RutaDelHecho() {}

    /**
     * @return el tópico, o vacío si ese hecho no sale al bus
     */
    static Optional<Topico> de(TipoDeHecho tipo) {
        return switch (tipo) {
            case PETICION_REGISTRADA, LINEA_ANULADA -> Optional.of(Topico.PETICIONES);
            case ESPECIMEN_REGISTRADO -> Optional.of(Topico.ESPECIMENES);
            case RESULTADO_INFORMADO, RESULTADO_VALIDADO, RESULTADO_DECLARABLE -> Optional.of(Topico.RESULTADOS);
            case INFORME_EMITIDO -> Optional.of(Topico.INFORMES);

            // Los tópicos de §11 son los cuatro del laboratorio, y la filiación no es uno de ellos:
            // la demografía la manda el HIS y el laboratorio la recibe, no la anuncia. Publicarla
            // añadiría al bus el único dato que de verdad identifica a una persona fuera de aquí,
            // y un tópico replicado es lo más difícil de borrar que hay el día que alguien ejerza
            // el derecho de supresión. Se apuntan igual —el `outbox` es la prueba de lo que pasó—,
            // pero se marcan como descartadas en vez de quedarse pendientes para siempre.
            case PACIENTE_REGISTRADO, PACIENTE_ACTUALIZADO -> Optional.empty();
        };
    }
}
