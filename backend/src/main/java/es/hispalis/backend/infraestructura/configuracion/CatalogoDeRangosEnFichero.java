package es.hispalis.backend.infraestructura.configuracion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.resultado.CatalogoDeRangosDeReferencia;
import es.hispalis.backend.dominio.resultado.RangoDeReferencia;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Los rangos de referencia del laboratorio, leídos del fichero que los publica.
 *
 * <p><strong>Este fichero es la fuente única y lo comparten dos componentes.</strong> El backend lo
 * lee aquí para publicar {@code Observation.referenceRange}; el generador de datos sintéticos lo lee
 * en Python para sortear valores verosímiles. Antes estaban escritos dos veces —en una migración de
 * Flyway y en {@code simuladores/generador/clinica.py}— y nada comprobaba que coincidieran: si
 * divergen, el generador produce resultados que el laboratorio interpreta de otra manera, y el corpus
 * sigue validando tan campante porque los dos ficheros son válidos por separado. Es el mismo patrón
 * que la terminología con la guía (D15), sin sitio en la IG porque esto no es vocabulario compartido.
 *
 * <p><strong>Y por eso ya no son una tabla.</strong> Estaban sembrados con un {@code INSERT} en la
 * migración {@code V5}, que es lo que los convirtió en esquema: una vez ahí, cambiarlos exigía
 * escribir otra migración, y la copia de Python era el camino corto para no hacerlo. Una tabla cuyo
 * único escritor es una migración es un fichero de configuración con pasos de más.
 *
 * <p>Se lee <strong>una vez, al arrancar</strong>, y se valida entera: mejor no arrancar que servir
 * durante horas un rango a medias. Son diecinueve filas y no cambian mientras el proceso vive.
 */
@Component
public class CatalogoDeRangosEnFichero implements CatalogoDeRangosDeReferencia {

    /** Sexos que admite el fichero. Son los códigos de {@code Patient.gender} que discriminan rango. */
    private static final Set<String> SEXOS = Set.of("male", "female");

    private final Map<String, List<RangoDeReferencia>> porPrueba;

    public CatalogoDeRangosEnFichero(
            @Value("classpath:laboratorio/rangos-de-referencia.json") Resource fichero, ObjectMapper json) {
        this.porPrueba = leer(fichero, json).stream()
                .collect(Collectors.groupingBy(
                        RangoDeReferencia::codigoDePrueba,
                        Collectors.collectingAndThen(Collectors.toList(), CatalogoDeRangosEnFichero::ordenados)));
    }

    @Override
    public List<RangoDeReferencia> buscarPorPrueba(String codigoDePrueba) {
        return porPrueba.getOrDefault(codigoDePrueba, List.of());
    }

    private static List<RangoDeReferencia> leer(Resource fichero, ObjectMapper json) {
        List<RangoDeReferencia> rangos = new ArrayList<>();
        try (InputStream entrada = fichero.getInputStream()) {
            for (JsonNode fila : json.readTree(entrada).withArray("rangos")) {
                rangos.add(aRango(fila));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No se pudo leer «%s», el fichero de rangos de referencia del laboratorio"
                            .formatted(fichero.getDescription()),
                    e);
        }
        exigirQueNoHayaRangosSolapados(rangos);
        return rangos;
    }

    private static RangoDeReferencia aRango(JsonNode fila) {
        String sexo = fila.hasNonNull("sexo") ? fila.get("sexo").asText() : null;
        if (sexo != null && !SEXOS.contains(sexo)) {
            throw new DatoInvalido("«%s» no es un sexo al que atar un rango de referencia: %s o nada."
                    .formatted(sexo, String.join(" o ", SEXOS)));
        }
        // El propio `RangoDeReferencia` exige que los dos límites estén y en orden, así que un
        // fichero con «bajo» mayor que «alto» no llega a construirse.
        return new RangoDeReferencia(
                fila.get("prueba").asText(),
                sexo,
                fila.get("bajo").decimalValue(),
                fila.get("alto").decimalValue(),
                fila.get("unidad").asText());
    }

    /**
     * Dos rangos que aplican al mismo paciente harían ambiguo cuál se usa para interpretar la cifra.
     *
     * <p>Lo garantizaban dos índices únicos parciales de PostgreSQL, y al salir de la base de datos
     * la garantía se habría perdido en silencio: duplicar una línea del fichero no rompe el JSON.
     */
    private static void exigirQueNoHayaRangosSolapados(List<RangoDeReferencia> rangos) {
        Set<String> vistos = new HashSet<>();
        String repetidos = rangos.stream()
                .map(rango ->
                        rango.codigoDePrueba() + "/" + rango.sexoAlQueAplica().orElse("común"))
                .filter(clave -> !vistos.add(clave))
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        if (!repetidos.isEmpty()) {
            throw new DatoInvalido(
                    ("El fichero de rangos define dos veces %s. Con dos rangos para el mismo paciente no "
                                    + "se sabe con cuál interpretar el resultado.")
                            .formatted(repetidos));
        }
    }

    /** El común primero, igual que hacía el {@code ORDER BY sexo NULLS FIRST} de la consulta. */
    private static List<RangoDeReferencia> ordenados(List<RangoDeReferencia> rangos) {
        return rangos.stream()
                .sorted(Comparator.comparing(RangoDeReferencia::sexo, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }
}
