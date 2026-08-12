package es.hispalis.backend.infraestructura.exportacion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La exportación masiva.
 *
 * @param habilitada si {@code $export} está publicado. Encendida por defecto, porque lo que de verdad
 *     la cierra no es este interruptor sino los <em>scopes</em>: exportar exige {@code system/Group.rs}
 *     <strong>y</strong> {@code system/*.rs}, que ningún cliente tiene de fábrica
 * @param directorio dónde viven los NDJSON mientras existen. Fuera del árbol de la aplicación: un
 *     volcado de población no se despliega ni se empaqueta con el código
 * @param caducidad cuánto se puede descargar un fichero. Corta a propósito — mientras existe, es el
 *     activo más apetecible de todo el montaje
 * @param barrido cada cuánto pasa el barrendero a por lo caducado y lo huérfano
 * @param maximoDeMiembros tope de pacientes por cohorte exportada. La exportación comparte base de
 *     datos con la operación asistencial, y una cohorte que crece sola acaba siendo la consulta que
 *     deja al laboratorio sin poder registrar un resultado
 */
@ConfigurationProperties(prefix = "hispalis.exportacion")
public record PropiedadesDeExportacion(
        boolean habilitada, String directorio, Duration caducidad, Duration barrido, int maximoDeMiembros) {}
