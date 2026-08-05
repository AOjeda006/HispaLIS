package es.hispalis.backend.fhir;

import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;

/**
 * Rechazo explícito del {@code PUT} en los recursos cuya modificación aún no pasa por el dominio.
 *
 * <p>La alternativa no es «que funcione»: es que el {@code update} heredado de HAPI escriba la
 * proyección y <strong>deje el dominio atrás en silencio</strong>. A partir de ahí las dos mitades
 * dicen cosas distintas, nada avisa, y el invariante del espécimen rechazado —que se comprueba
 * contra el dominio— empieza a decidir con datos viejos.
 *
 * <p>Entre un fallo visible y una corrupción callada, se elige el fallo visible. Es la misma razón
 * por la que el diseño prevé un <em>reconciliador</em> como vía de recuperación oficial y no como
 * script de emergencia.
 *
 * <p>Se levanta recurso a recurso, según cada modificación tenga semántica de negocio definida:
 * corregir la filiación de un paciente la tiene y ya está hecha; rectificar un resultado ya emitido
 * es una operación clínica con reglas propias (¿se sustituye?, ¿se marca como enmendado?, ¿qué pasa
 * con el informe que lo incluía?) que no se improvisa.
 */
public final class EscrituraSoloPorAlta {

    private EscrituraSoloPorAlta() {
        // Utilidad.
    }

    /**
     * Rechaza la modificación de un recurso.
     *
     * @param recurso nombre del recurso FHIR, para el mensaje
     * @return nunca; el tipo de retorno permite usarlo como {@code throw rechazar(...)}
     * @throws ReglaDeNegocioIncumplida siempre
     */
    public static ReglaDeNegocioIncumplida rechazar(String recurso) {
        throw new ReglaDeNegocioIncumplida(
                ("Modificar un %s ya registrado no está soportado todavía: su escritura pasa por el núcleo del "
                                + "laboratorio y esa operación aún no tiene reglas definidas. Se rechaza en vez de "
                                + "escribir solo la mitad.")
                        .formatted(recurso));
    }
}
