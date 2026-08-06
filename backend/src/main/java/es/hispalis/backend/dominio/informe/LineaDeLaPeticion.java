package es.hispalis.backend.dominio.informe;

import java.util.UUID;

/**
 * Lo que el informe necesita saber de una línea del volante para decidir si puede emitirse.
 *
 * <p>No es el agregado {@code Peticion}: es la vista mínima que el invariante necesita. El informe
 * no tiene por qué saber quién pidió la prueba ni cuándo, y arrastrar el agregado entero hasta aquí
 * ataría dos partes del dominio que no se necesitan.
 *
 * @param conResultado si contra la línea consta ya alguna determinación
 * @param anulada si la línea se retiró
 */
public record LineaDeLaPeticion(
        UUID id, String numeroDePeticion, String codigoDePrueba, boolean conResultado, boolean anulada) {

    /**
     * Si la línea ya no deja trabajo pendiente.
     *
     * <p>Una línea anulada cuenta como resuelta, y esa es la regla entera. Lo que el invariante del
     * informe persigue no es que todo tenga resultado, sino que <strong>nadie siga esperando</strong>
     * algo que el informe da por cerrado: de una línea retirada ya nadie espera nada, porque el
     * laboratorio lo dijo y lo publicó.
     *
     * <p>Vive aquí y no en quien construye el alcance a propósito. Si la calculara el caso de uso,
     * cada nuevo camino de entrada —el motor de integración, por ejemplo— tendría que acordarse de
     * calcularla igual.
     */
    public boolean resuelta() {
        return conResultado || anulada;
    }
}
