package es.hispalis.integracion.infraestructura.terminologia;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import es.hispalis.integracion.terminologia.PruebaDelCatalogo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * El catálogo, leído de los artefactos que publica la guía.
 *
 * <p>Se leen <strong>como recursos FHIR</strong>, no como JSON suelto: son un {@code CodeSystem} y un
 * {@code ConceptMap} de verdad, y parsearlos con HAPI es lo que hace que el día del servidor de
 * terminología (ítem 33) el cambio sea de dónde vienen, no de qué son.
 *
 * <p>Es exactamente lo que hace el generador de datos sintéticos en Python, y por la misma razón
 * (D15): quien consume una copia propia de la terminología acaba probando su copia.
 *
 * <h2>El mapa se lee al revés</h2>
 *
 * <p>La guía publica {@code catálogo local → LOINC}, que es la dirección en la que el laboratorio
 * habla hacia fuera. El motor necesita la contraria —lo que entra viene en LOINC— y la obtiene
 * invirtiendo <strong>ese mismo mapa</strong>. No hay una segunda tabla que pueda desviarse.
 *
 * <p>Invertir un mapeo no siempre es legítimo y aquí se comprueba que lo sea: solo se invierten las
 * correspondencias declaradas {@code equivalent}. Donde el {@code ConceptMap} dice
 * {@code source-is-broader-than-target} —el código local es más amplio que el término LOINC— la
 * vuelta no vale: varios LOINC caerían en el mismo código local, y elegir uno sería inventar una
 * precisión que el mapeo dice explícitamente que no tiene.
 */
@Component
public class CatalogoLeidoDeLaGuia implements CatalogoDelLaboratorio {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogoLeidoDeLaGuia.class);

    private static final String FICHERO_CODESYSTEM = "CodeSystem-catalogo-pruebas.json";
    private static final String FICHERO_CONCEPTMAP = "ConceptMap-catalogo-a-loinc.json";
    private static final String FICHERO_TIPOS_DE_MUESTRA = "ValueSet-tipos-muestra.json";

    /** La propiedad del {@code CodeSystem} que declara en qué unidad emite el laboratorio. */
    private static final String PROPIEDAD_UNIDAD = "unidad-ucum";

    private final Map<String, PruebaDelCatalogo> porCodigoLocal;
    private final Map<String, String> localPorLoinc;
    private final Set<String> tiposDeMuestra;

    public CatalogoLeidoDeLaGuia(PropiedadesTerminologia propiedades) {
        FhirContext contexto = FhirContext.forR5();
        Path directorio = Path.of(propiedades.directorio());

        ConceptMap mapa = leer(contexto, directorio.resolve(FICHERO_CONCEPTMAP), ConceptMap.class);
        this.localPorLoinc = invertir(mapa);

        CodeSystem catalogo = leer(contexto, directorio.resolve(FICHERO_CODESYSTEM), CodeSystem.class);
        this.porCodigoLocal = pruebasDe(catalogo, loincPorLocal(mapa));

        ValueSet muestras = leer(contexto, directorio.resolve(FICHERO_TIPOS_DE_MUESTRA), ValueSet.class);
        this.tiposDeMuestra = codigosDe(muestras);

        LOG.info(
                "Catálogo del laboratorio cargado desde {}: {} pruebas, {} equivalencias LOINC invertibles, "
                        + "{} tipos de muestra",
                directorio.toAbsolutePath(),
                porCodigoLocal.size(),
                localPorLoinc.size(),
                tiposDeMuestra.size());
    }

    @Override
    public Optional<String> traducirALocal(String system, String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return Optional.empty();
        }
        String limpio = codigo.strip();

        // Sin `system` no se puede saber en qué dialecto viene, así que se prueban los dos que este
        // laboratorio entiende, el propio primero. Es lo que hay que hacer con los emisores que no
        // rellenan `OBX-3.3`, que son muchos — y sigue sin ser adivinar: si no está en ninguno de
        // los dos catálogos, no se traduce.
        if (system == null || system.isBlank()) {
            return porCodigoLocal.containsKey(limpio)
                    ? Optional.of(limpio)
                    : Optional.ofNullable(localPorLoinc.get(limpio));
        }
        if (SYSTEM.equals(system) && porCodigoLocal.containsKey(limpio)) {
            return Optional.of(limpio);
        }
        if (LOINC.equals(system)) {
            return Optional.ofNullable(localPorLoinc.get(limpio));
        }
        return Optional.empty();
    }

    @Override
    public Optional<PruebaDelCatalogo> buscar(String codigoLocal) {
        return Optional.ofNullable(porCodigoLocal.get(codigoLocal));
    }

    @Override
    public boolean esTipoDeMuestraConocido(String codigoSnomed) {
        return codigoSnomed != null && tiposDeMuestra.contains(codigoSnomed.strip());
    }

    @Override
    public int tamano() {
        return porCodigoLocal.size();
    }

    /**
     * Los códigos enumerados del {@code ValueSet}.
     *
     * <p>Solo se leen los {@code compose.include.concept} — la enumeración explícita—, que es como
     * está escrito el conjunto de tipos de muestra de esta guía. Un {@code ValueSet} definido por
     * filtro o por referencia a otro necesitaría un {@code $expand}, y eso es el servidor de
     * terminología del ítem 33: si algún día el conjunto se escribe así, esto devolverá vacío y el
     * arranque lo dirá en el log en vez de aceptar cualquier código en silencio.
     */
    private static Set<String> codigosDe(ValueSet conjunto) {
        return conjunto.getCompose().getInclude().stream()
                .flatMap(inclusion -> inclusion.getConcept().stream())
                .map(ValueSet.ConceptReferenceComponent::getCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, PruebaDelCatalogo> pruebasDe(CodeSystem catalogo, Map<String, String> loincPorLocal) {
        Map<String, PruebaDelCatalogo> pruebas = new LinkedHashMap<>();
        for (CodeSystem.ConceptDefinitionComponent concepto : catalogo.getConcept()) {
            pruebas.put(
                    concepto.getCode(),
                    new PruebaDelCatalogo(
                            concepto.getCode(),
                            concepto.getDisplay(),
                            unidadDe(concepto),
                            loincPorLocal.get(concepto.getCode())));
        }
        return Map.copyOf(pruebas);
    }

    private static String unidadDe(CodeSystem.ConceptDefinitionComponent concepto) {
        return concepto.getProperty().stream()
                .filter(propiedad -> PROPIEDAD_UNIDAD.equals(propiedad.getCode()))
                .filter(propiedad -> propiedad.getValue() instanceof org.hl7.fhir.r5.model.Coding)
                .map(propiedad -> ((org.hl7.fhir.r5.model.Coding) propiedad.getValue()).getCode())
                .findFirst()
                .orElse(null);
    }

    private static Map<String, String> loincPorLocal(ConceptMap mapa) {
        Map<String, String> equivalencias = new HashMap<>();
        for (ConceptMap.ConceptMapGroupComponent grupo : mapa.getGroup()) {
            for (ConceptMap.SourceElementComponent elemento : grupo.getElement()) {
                elemento.getTarget().stream()
                        .findFirst()
                        .ifPresent(destino -> equivalencias.put(elemento.getCode(), destino.getCode()));
            }
        }
        return equivalencias;
    }

    /** Ver la nota de la clase: solo se invierte lo que el mapa declara {@code equivalent}. */
    private static Map<String, String> invertir(ConceptMap mapa) {
        Map<String, String> alReves = new HashMap<>();
        for (ConceptMap.ConceptMapGroupComponent grupo : mapa.getGroup()) {
            if (!LOINC.equals(grupo.getTarget())) {
                continue;
            }
            for (ConceptMap.SourceElementComponent elemento : grupo.getElement()) {
                for (ConceptMap.TargetElementComponent destino : elemento.getTarget()) {
                    if (destino.getRelationship() == Enumerations.ConceptMapRelationship.EQUIVALENT) {
                        alReves.put(destino.getCode(), elemento.getCode());
                    }
                }
            }
        }
        return Map.copyOf(alReves);
    }

    private static <T extends org.hl7.fhir.r5.model.Resource> T leer(
            FhirContext contexto, Path fichero, Class<T> tipo) {
        try {
            return contexto.newJsonParser().parseResource(tipo, Files.readString(fichero, StandardCharsets.UTF_8));
        } catch (IOException noEsta) {
            throw new UncheckedIOException(
                    ("No se encuentra «%s». La terminología no se copia dentro del motor: se lee de lo que produce "
                                    + "la guía (D15). Ejecuta «npx fsh-sushi .» dentro de «ig/», o apunta a otro "
                                    + "directorio con HISPALIS_TERMINOLOGIA.")
                            .formatted(fichero.toAbsolutePath()),
                    noEsta);
        }
    }
}
