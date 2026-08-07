package es.hispalis.integracion.infraestructura.terminologia;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import es.hispalis.integracion.terminologia.PruebaDelCatalogo;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.IntegerType;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r5.model.UriType;
import org.hl7.fhir.r5.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El catálogo, preguntado al servidor de terminología por la API estándar (D14).
 *
 * <p>Sustituye a la lectura de los ficheros que produce SUSHI. La diferencia no es de dónde salen los
 * datos sino de <strong>quién los interpreta</strong>: antes el motor leía un {@code CodeSystem} y lo
 * recorría él, y eso le obligaba a saber cosas —que la unidad vive en una propiedad, que un
 * {@code ValueSet} enumerado se lee de {@code compose.include.concept}— que son del servidor de
 * terminología, no suyas. Ahora pregunta y no interpreta.
 *
 * <h2>Las cuatro operaciones, y cada una para lo suyo</h2>
 *
 * <ul>
 *   <li>{@code $lookup} — el nombre en español y la unidad UCUM, que el {@code CodeSystem} declara
 *       como propiedad {@code unidad-ucum} y el servidor devuelve como tal.
 *   <li>{@code $validate-code} — si un código es del catálogo, y si un tipo de muestra es de los que
 *       el laboratorio acepta.
 *   <li>{@code $translate} — el LOINC de una prueba, y la vuelta: el código local de un LOINC que
 *       llega en un {@code OBX-3}.
 *   <li>{@code $expand} — cuántas pruebas hay, para avisar al arrancar si el servidor está vacío.
 * </ul>
 *
 * <h2>⚠️ La vuelta del mapa: R5 la define y HAPI todavía no la implementa</h2>
 *
 * <p>R5 traduce al revés con {@code targetCode}/{@code targetCoding}. Medido contra HAPI 8.10, los
 * rechaza: <em>«HAPI-1154: One (and only one) of the in parameters (code, coding, codeableConcept)
 * must be provided»</em>. Lo que sí entiende es {@code reverse=true}, que es como se pedía en R4.
 *
 * <p>Así que se piden <strong>las dos formas, la de R5 primero</strong>. No es una concesión a HAPI:
 * es lo contrario — el día que el servidor implemente el parámetro estándar, la primera llamada
 * acierta y la segunda deja de hacerse sin tocar una línea. Y contra un servidor que solo entienda la
 * forma antigua, el motor sigue funcionando.
 *
 * <h2>Solo se invierte lo que el mapa declara equivalente</h2>
 *
 * <p>Donde el {@code ConceptMap} dice que el código local es más amplio que el término LOINC, la
 * vuelta no vale: varios LOINC caerían en el mismo código local y elegir uno inventaría una precisión
 * que el mapa dice explícitamente que no tiene. El servidor devuelve la relación —{@code equivalent}
 * en R5, {@code equivalence} en la forma de R4— y aquí se exige que sea de equivalencia.
 */
public class CatalogoDelServidorDeTerminologia implements CatalogoDelLaboratorio {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogoDelServidorDeTerminologia.class);

    private static final String CONJUNTO_DE_PRUEBAS =
            "https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo";
    private static final String CONJUNTO_DE_TIPOS_DE_MUESTRA =
            "https://aojeda006.github.io/HispaLIS/fhir/ValueSet/tipos-muestra";
    private static final String MAPA_A_LOINC = "https://aojeda006.github.io/HispaLIS/fhir/ConceptMap/catalogo-a-loinc";
    private static final String SNOMED = "http://snomed.info/sct";

    /** La propiedad del {@code CodeSystem} que declara en qué unidad emite el laboratorio. */
    private static final String PROPIEDAD_UNIDAD = "unidad-ucum";

    private final IGenericClient cliente;

    /**
     * Lo ya preguntado.
     *
     * <p>Un canal traduce el mismo puñado de códigos en cada mensaje, y sin caché cada {@code OBX} de
     * un hemograma serían tres viajes de red. <strong>No es una copia del catálogo:</strong> solo
     * entra lo que el servidor ha contestado, y lo que no contestó no entra — si se cachease un fallo,
     * una caída de treinta segundos dejaría el resto de la vida del proceso rechazando mensajes
     * buenos.
     */
    private final Map<String, PruebaDelCatalogo> pruebas = new ConcurrentHashMap<>();

    private final Map<String, String> localesPorLoinc = new ConcurrentHashMap<>();
    private final Map<String, Boolean> tiposDeMuestra = new ConcurrentHashMap<>();

    public CatalogoDelServidorDeTerminologia(IGenericClient cliente) {
        this.cliente = cliente;
    }

    @Override
    public Optional<String> traducirALocal(String system, String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return Optional.empty();
        }
        String limpio = codigo.strip();

        // Sin `system` no se sabe en qué dialecto viene, así que se prueban los dos que este
        // laboratorio entiende, el propio primero. Es lo que hay que hacer con los emisores que no
        // rellenan `OBX-3.3`, que son muchos — y sigue sin ser adivinar: si no está en ninguno de los
        // dos catálogos, no se traduce.
        if (system == null || system.isBlank()) {
            return esDelCatalogo(limpio) ? Optional.of(limpio) : desdeLoinc(limpio);
        }
        if (SYSTEM.equals(system)) {
            return esDelCatalogo(limpio) ? Optional.of(limpio) : Optional.empty();
        }
        if (LOINC.equals(system)) {
            return desdeLoinc(limpio);
        }
        return Optional.empty();
    }

    @Override
    public Optional<PruebaDelCatalogo> buscar(String codigoLocal) {
        if (codigoLocal == null || codigoLocal.isBlank()) {
            return Optional.empty();
        }
        PruebaDelCatalogo sabida = pruebas.get(codigoLocal);
        return sabida != null ? Optional.of(sabida) : preguntarPor(codigoLocal);
    }

    @Override
    public boolean esTipoDeMuestraConocido(String codigoSnomed) {
        if (codigoSnomed == null || codigoSnomed.isBlank()) {
            return false;
        }
        String limpio = codigoSnomed.strip();
        Boolean sabido = tiposDeMuestra.get(limpio);
        if (sabido != null) {
            return sabido;
        }
        Optional<Boolean> respuesta = validar(CONJUNTO_DE_TIPOS_DE_MUESTRA, SNOMED, limpio);
        respuesta.ifPresent(esta -> tiposDeMuestra.put(limpio, esta));
        return respuesta.orElse(false);
    }

    /**
     * Cuántas pruebas oferta el catálogo, con {@code $expand}.
     *
     * <p>No se guarda ni se usa para traducir nada: sirve para que el arranque diga en voz alta si el
     * servidor de terminología está vacío. Es la única llamada que pide el conjunto entero, y por eso
     * devuelve un número y no una lista — una lista aquí acabaría siendo el {@code Map<String,String>}
     * que el invariante 4 prohíbe.
     */
    @Override
    public int tamano() {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(CONJUNTO_DE_PRUEBAS));
        entrada.addParameter("count", new IntegerType(0));

        return invocar(ValueSet.class, "$expand", entrada, ValueSet.class)
                .map(conjunto -> conjunto.getExpansion().getTotal())
                .orElse(0);
    }

    /** {@code $lookup} para el nombre y la unidad, {@code $translate} para el LOINC. */
    private Optional<PruebaDelCatalogo> preguntarPor(String codigoLocal) {
        Parameters entrada = new Parameters();
        entrada.addParameter("system", new UriType(SYSTEM));
        entrada.addParameter("code", new CodeType(codigoLocal));

        Optional<Parameters> salida = invocar(CodeSystem.class, "$lookup", entrada, Parameters.class);
        if (salida.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> nombre = texto(salida.get(), "display");
        if (nombre.isEmpty()) {
            // Un `$lookup` que contesta sin `display` es una prueba que el servidor no conoce.
            return Optional.empty();
        }

        PruebaDelCatalogo prueba = new PruebaDelCatalogo(
                codigoLocal,
                nombre.get(),
                unidadDe(salida.get()).orElse(null),
                aLoinc(codigoLocal).orElse(null));
        pruebas.put(codigoLocal, prueba);
        return Optional.of(prueba);
    }

    /**
     * La unidad UCUM, leída de la propiedad que el {@code CodeSystem} declara.
     *
     * <p>Es terminología y llega como terminología: un {@code Coding} de UCUM dentro de la propiedad
     * {@code unidad-ucum}. El motor no la deduce del nombre de la prueba ni la lleva escrita.
     */
    private static Optional<String> unidadDe(Parameters salida) {
        return salida.getParameter().stream()
                .filter(parametro -> "property".equals(parametro.getName()))
                .filter(propiedad -> PROPIEDAD_UNIDAD.equals(parteDe(propiedad, "code")))
                .map(propiedad -> propiedad.getPart().stream()
                        .filter(parte -> "value".equals(parte.getName()))
                        .map(ParametersParameterComponent::getValue)
                        .filter(Coding.class::isInstance)
                        .map(valor -> ((Coding) valor).getCode())
                        .findFirst()
                        .orElse(null))
                .filter(unidad -> unidad != null && !unidad.isBlank())
                .findFirst();
    }

    private static String parteDe(ParametersParameterComponent propiedad, String nombre) {
        return propiedad.getPart().stream()
                .filter(parte -> nombre.equals(parte.getName()) && parte.getValue() != null)
                .map(parte -> parte.getValue().primitiveValue())
                .findFirst()
                .orElse(null);
    }

    /** {@code $translate} en la dirección que publica la guía: código local → LOINC. */
    private Optional<String> aLoinc(String codigoLocal) {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(MAPA_A_LOINC));
        entrada.addParameter("system", new UriType(SYSTEM));
        entrada.addParameter("sourceCode", new CodeType(codigoLocal));
        entrada.addParameter("targetSystem", new UriType(LOINC));

        // Aquí NO se exige equivalencia: la guía publica esta dirección tal cual, incluidos los
        // mapeos que declaran que el LOINC es más estrecho. Lo que no vale es la vuelta.
        return invocar(ConceptMap.class, "$translate", entrada, Parameters.class)
                .flatMap(salida -> emparejamiento(salida, LOINC, false));
    }

    /** {@code $translate} al revés: LOINC → código local, solo donde hay equivalencia. */
    private Optional<String> desdeLoinc(String loinc) {
        String sabido = localesPorLoinc.get(loinc);
        if (sabido != null) {
            return Optional.of(sabido);
        }
        Optional<String> local = alReves(loinc).or(() -> alRevesComoEnR4(loinc));
        local.ifPresent(codigo -> localesPorLoinc.put(loinc, codigo));
        return local;
    }

    /** La forma de R5: se identifica el <em>destino</em> y el servidor devuelve los orígenes. */
    private Optional<String> alReves(String loinc) {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(MAPA_A_LOINC));
        entrada.addParameter("targetCode", new CodeType(loinc));
        entrada.addParameter("targetSystem", new UriType(LOINC));

        return invocar(ConceptMap.class, "$translate", entrada, Parameters.class)
                .flatMap(salida -> emparejamiento(salida, SYSTEM, true));
    }

    /** La forma de R4, que es la que HAPI 8.10 entiende. Ver la nota de la clase. */
    private Optional<String> alRevesComoEnR4(String loinc) {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(MAPA_A_LOINC));
        entrada.addParameter("system", new UriType(LOINC));
        entrada.addParameter("code", new CodeType(loinc));
        entrada.addParameter("reverse", true);

        return invocar(ConceptMap.class, "$translate", entrada, Parameters.class)
                .flatMap(salida -> emparejamiento(salida, SYSTEM, true));
    }

    /**
     * El código del primer emparejamiento del {@code system} pedido.
     *
     * @param exigirEquivalencia si se descartan los emparejamientos que el mapa no declara
     *     {@code equivalent}. Se miran las dos formas del elemento —{@code relationship} de R5 y
     *     {@code equivalence} de R4— porque HAPI aún contesta la segunda.
     */
    private static Optional<String> emparejamiento(Parameters salida, String system, boolean exigirEquivalencia) {
        if (!booleano(salida, "result").orElse(false)) {
            return Optional.empty();
        }
        for (ParametersParameterComponent match : salida.getParameter()) {
            if (!"match".equals(match.getName())) {
                continue;
            }
            if (exigirEquivalencia && !"equivalent".equals(relacionDe(match))) {
                continue;
            }
            Optional<String> codigo = match.getPart().stream()
                    .filter(parte -> "concept".equals(parte.getName()))
                    .map(ParametersParameterComponent::getValue)
                    .filter(Coding.class::isInstance)
                    .map(Coding.class::cast)
                    .filter(concepto -> system.equals(concepto.getSystem()))
                    .map(Coding::getCode)
                    .findFirst();
            if (codigo.isPresent()) {
                return codigo;
            }
        }
        return Optional.empty();
    }

    private static String relacionDe(ParametersParameterComponent match) {
        return match.getPart().stream()
                .filter(parte -> "relationship".equals(parte.getName()) || "equivalence".equals(parte.getName()))
                .filter(parte -> parte.getValue() != null)
                .map(parte -> parte.getValue().primitiveValue())
                .findFirst()
                .orElse("");
    }

    private boolean esDelCatalogo(String codigoLocal) {
        PruebaDelCatalogo sabida = pruebas.get(codigoLocal);
        if (sabida != null) {
            return true;
        }
        // Sin respuesta no se traduce: el canal manda el mensaje a la bandeja de errores, que es
        // reprocesable. Aceptar un código sin comprobarlo metería en el laboratorio una prueba que
        // quizá no oferta, y eso no se deshace con un reproceso.
        return validar(CONJUNTO_DE_PRUEBAS, SYSTEM, codigoLocal).orElse(false);
    }

    private Optional<Boolean> validar(String conjunto, String system, String codigo) {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(conjunto));
        entrada.addParameter("system", new UriType(system));
        entrada.addParameter("code", new CodeType(codigo));

        return invocar(ValueSet.class, "$validate-code", entrada, Parameters.class)
                .flatMap(salida -> booleano(salida, "result"));
    }

    private <T extends org.hl7.fhir.r5.model.Resource> Optional<T> invocar(
            Class<? extends org.hl7.fhir.r5.model.Resource> tipo,
            String operacion,
            Parameters entrada,
            Class<T> devuelve) {
        try {
            return Optional.ofNullable(cliente.operation()
                    .onType(tipo)
                    .named(operacion)
                    .withParameters(entrada)
                    .returnResourceType(devuelve)
                    .execute());
        } catch (RuntimeException noContesta) {
            LOG.warn(
                    "El servidor de terminología no resolvió {} sobre {}: el mensaje no se traduce y va a la "
                            + "bandeja de errores. Causa: {}",
                    operacion,
                    tipo.getSimpleName(),
                    noContesta.toString());
            return Optional.empty();
        }
    }

    private static Optional<String> texto(Parameters salida, String nombre) {
        return salida.getParameter().stream()
                .filter(parametro -> nombre.equals(parametro.getName()) && parametro.getValue() != null)
                .map(parametro -> parametro.getValue().primitiveValue())
                .filter(valor -> valor != null && !valor.isBlank())
                .findFirst();
    }

    private static Optional<Boolean> booleano(Parameters salida, String nombre) {
        return salida.getParameter().stream()
                .filter(parametro -> nombre.equals(parametro.getName()))
                .map(ParametersParameterComponent::getValue)
                .filter(BooleanType.class::isInstance)
                .map(valor -> ((BooleanType) valor).getValue())
                .findFirst();
    }
}
