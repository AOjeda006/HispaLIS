package es.hispalis.backend.aplicacion.reconciliacion;

import java.util.UUID;

/**
 * Una diferencia entre lo que dice el dominio y lo que hay publicado en la proyección.
 *
 * <p>Lleva <strong>el tipo de recurso y su identidad, nada más</strong>. El informe del reconciliador
 * se lee en una consola, se pega en un correo y acaba en un registro de incidencias: si dijera qué
 * campo cambió, ese diagnóstico sería un volcado clínico viajando por sitios donde no puede estar.
 * Quien necesite ver la diferencia mira los dos recursos por la API, con sus permisos.
 *
 * @param tipoDeRecurso el nombre FHIR: {@code Patient}, {@code Observation}…
 * @param id la identidad del recurso, que es la del agregado
 * @param clase qué le pasa
 */
public record Divergencia(String tipoDeRecurso, UUID id, Clase clase) {

    /** Las tres formas en que el dominio y su proyección pueden dejar de coincidir. */
    public enum Clase {

        /** El agregado existe y el recurso no está publicado. */
        AUSENTE,

        /** Los dos existen y no dicen lo mismo. */
        DISTINTO,

        /**
         * El recurso está publicado y no hay agregado detrás.
         *
         * <p>Es la forma que tenía el incidente del {@code Bundle transaction} (§15) y la que un
         * reconciliador ingenuo no detecta: regenerar desde el dominio arregla lo que falta y deja
         * intacto lo que sobra. Un recurso sin agregado es un dato clínico que el laboratorio publica
         * y del que no responde nadie.
         */
        HUERFANO
    }

    public String referencia() {
        return tipoDeRecurso + "/" + id;
    }

    @Override
    public String toString() {
        return clase + " " + referencia();
    }
}
