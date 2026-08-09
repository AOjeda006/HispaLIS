package es.hispalis.backend.dominio.resultado;

import java.util.Optional;

/**
 * Puerto: a quién le pregunta el laboratorio qué prueba refleja cada prueba.
 *
 * <p>La respuesta la da la <strong>terminología</strong>, igual que los nombres y que los umbrales
 * críticos, porque la regla es una propiedad del concepto del catálogo y no una tabla aparte
 * (invariante 4). Se pregunta por un código a la vez y nunca «dame todas las reglas»: con un método
 * así, lo primero que haría alguien es cachearlas al arrancar, y eso es la lista paralela.
 *
 * <p><strong>Vacío significa «no hay refleja», y aquí sí se puede degradar.</strong> Es la diferencia
 * con {@link ValoresCriticos}, donde no saber lanza en vez de contestar que no: con un umbral
 * crítico, callarse <em>invierte</em> la respuesta —un potasio de 7,5 pasaría por normal— y alguien
 * no recibe una llamada. Con una refleja, callarse solo <em>omite</em> una prueba añadida, y el
 * facultativo que valide la TSH la va a ver marcada como alta igual. Bloquear el registro de un
 * resultado porque el servidor de terminología no contesta sería cambiar un problema de terminología
 * por uno clínico, que es justo lo que la regla general del proyecto prohíbe.
 */
public interface ReglasReflejas {

    /**
     * Qué añade el laboratorio cuando esta prueba sale alterada.
     *
     * @param codigoDePrueba código del catálogo local
     * @return la regla, o vacío si esa prueba no dispara ninguna — o si no se ha podido preguntar
     */
    Optional<ReglaRefleja> reflejaDe(String codigoDePrueba);
}
