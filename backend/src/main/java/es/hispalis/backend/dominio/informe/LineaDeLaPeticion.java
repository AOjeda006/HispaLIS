package es.hispalis.backend.dominio.informe;

import java.util.UUID;

/**
 * Una línea del volante dentro del alcance de un informe, y si ya está resuelta.
 *
 * <p>No es el agregado {@code Peticion} sino lo que el informe necesita saber de él: qué se pidió y
 * si ya hay respuesta. El agregado vive en su propio paquete y responde de sus propias reglas; traerlo
 * entero aquí ataría los dos por algo que el informe ni consulta ni modifica.
 *
 * @param id identidad de la línea, que es también la del recurso {@code ServiceRequest} que la publica
 * @param numeroDePeticion el número del volante que agrupa las líneas; va en el mensaje de error
 *     porque es lo que el laboratorio usa para localizar el trabajo pendiente
 * @param codigoDePrueba código del catálogo local de lo que se pidió
 * @param resuelta si la línea ya tiene resultado
 */
public record LineaDeLaPeticion(UUID id, String numeroDePeticion, String codigoDePrueba, boolean resuelta) {}
