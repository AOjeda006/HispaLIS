package es.hispalis.backend.infraestructura.terminologia;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.resultado.NoSeSabeSiEsCritico;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.UriType;
import org.hl7.fhir.r5.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El catálogo resuelto contra el servidor de terminología, por la API estándar.
 *
 * <h2>Tres operaciones y ninguna propietaria</h2>
 *
 * <p>{@code $lookup} para el nombre en español, {@code $validate-code} para saber si una prueba
 * existe y {@code $translate} para el LOINC. Las tres son del capítulo de terminología de FHIR: este
 * cliente funcionaría igual contra Snowstorm.
 *
 * <p><strong>Los parámetros de {@code $translate} se mandan con los nombres de R5</strong>
 * ({@code sourceCode}, {@code targetSystem}), no con los de R4 ({@code code},
 * {@code targetsystem}). Medido contra HAPI 8.10: acepta los dos, así que copiar un ejemplo de R4
 * <em>funciona</em> aquí y falla contra un servidor estricto — la peor clase de error. A la vuelta
 * pasa lo contrario y hay que tragarlo: HAPI devuelve {@code match.equivalence} con códigos de R4
 * ({@code narrower}) en vez de {@code match.relationship} de R5, así que se leen <strong>los
 * dos</strong>.
 *
 * <h2>Si el servidor no está, el laboratorio sigue</h2>
 *
 * <p>El nombre de una prueba es presentación; su código es el dato. Un servidor de terminología
 * caído no puede impedir que se registre un resultado, así que las respuestas se degradan —código
 * sin nombre, validación que no rechaza— y se avisa una vez por caída. Es una decisión de
 * disponibilidad, y está escrita aquí para que se vea: el servidor es una autoridad, no una puerta.
 */
public class TerminologiaDelServidor implements Terminologia {

    private static final Logger LOG = LoggerFactory.getLogger(TerminologiaDelServidor.class);

    private static final String LOINC = "http://loinc.org";
    private static final String CONJUNTO_DE_PRUEBAS =
            "https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo";
    private static final String MAPA_A_LOINC = "https://aojeda006.github.io/HispaLIS/fhir/ConceptMap/catalogo-a-loinc";

    private final IGenericClient cliente;

    /**
     * Lo ya resuelto. El catálogo de un laboratorio cambia cuando se incorpora una técnica, no entre
     * dos peticiones, así que preguntarlo una vez por proceso es suficiente — y evita convertir cada
     * escritura en tres viajes de red dentro de la transacción del dominio.
     *
     * <p>Solo se guarda lo que el servidor contestó. Una respuesta que no llegó no se cachea: si se
     * cachease, una caída de treinta segundos dejaría el resto de la vida del proceso publicando
     * recursos sin nombre.
     */
    private final Map<String, CodeableConcept> resueltos = new ConcurrentHashMap<>();

    private final Map<String, Boolean> validados = new ConcurrentHashMap<>();

    /** Los umbrales ya resueltos, incluido «esta prueba no tiene», que también es una respuesta. */
    private final Map<String, Optional<UmbralCritico>> umbrales = new ConcurrentHashMap<>();

    /** Las reglas reflejas ya resueltas, incluido «esta prueba no dispara ninguna». */
    private final Map<String, Optional<ReglaRefleja>> reflejas = new ConcurrentHashMap<>();

    private final AtomicBoolean avisadoDeQueNoEsta = new AtomicBoolean();

    public TerminologiaDelServidor(IGenericClient cliente) {
        this.cliente = cliente;
    }

    @Override
    public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
        CodeableConcept resuelto = resueltos.get(codigoLocal);
        return resuelto != null ? resuelto.copy() : resolver(codigoLocal).copy();
    }

    /**
     * {@code $lookup} otra vez, y aquí sí importa que la respuesta llegue.
     *
     * <p>Es la misma operación y el mismo concepto que {@link #pruebaDelCatalogo}, pero se leen otras
     * propiedades y <strong>la política de caída es la contraria</strong>: allí un servidor que no
     * contesta da un código sin nombre; aquí lanza. Que las dos cosas convivan en una clase cuyo
     * javadoc empieza diciendo «si el servidor no está, el laboratorio sigue» es deliberado — la regla
     * general tiene exactamente una excepción y conviene verla al lado de la regla.
     *
     * <p>Se cachea lo mismo que allí y por lo mismo: lo que el servidor llegó a contestar, incluida la
     * ausencia de umbral, que es una respuesta. Una llamada que no llegó no se cachea nunca.
     */
    @Override
    public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
        Optional<UmbralCritico> sabido = umbrales.get(codigoDePrueba);
        if (sabido != null) {
            return sabido;
        }
        Parameters entrada = new Parameters();
        entrada.addParameter("system", new UriType(CatalogoDePruebas.SYSTEM));
        entrada.addParameter("code", new CodeType(codigoDePrueba));

        Parameters salida = invocar(CodeSystem.class, "$lookup", entrada)
                .orElseThrow(() -> new NoSeSabeSiEsCritico(
                        "El servidor de terminología no ha contestado por «%s», así que no se sabe si tiene umbral "
                                        .formatted(codigoDePrueba)
                                + "crítico. Preguntar y no obtener respuesta no es lo mismo que no tener umbral."));

        Optional<UmbralCritico> umbral = umbralEn(salida, codigoDePrueba);
        umbrales.put(codigoDePrueba, umbral);
        return umbral;
    }

    /**
     * {@code $lookup} una vez más, y aquí la política de caída vuelve a ser la general: se degrada.
     *
     * <p>Sin respuesta se contesta «no hay refleja» en vez de lanzar, al revés que en
     * {@link #umbralDe}, y la asimetría está pensada: con un umbral crítico, callarse
     * <strong>invierte</strong> la respuesta y alguien no recibe una llamada; con una refleja,
     * callarse solo <strong>omite</strong> una prueba añadida, y el facultativo que valide la TSH la
     * va a ver marcada como alta igual. Bloquear el registro de resultados porque no se puede
     * consultar el protocolo sería cambiar un problema de terminología por uno clínico.
     *
     * <p>Por eso mismo <strong>una respuesta que no llegó no se cachea</strong>: la próxima vuelve a
     * preguntar, y en cuanto el servidor conteste las reflejas se reanudan solas.
     */
    @Override
    public Optional<ReglaRefleja> reflejaDe(String codigoDePrueba) {
        Optional<ReglaRefleja> sabido = reflejas.get(codigoDePrueba);
        if (sabido != null) {
            return sabido;
        }
        Parameters entrada = new Parameters();
        entrada.addParameter("system", new UriType(CatalogoDePruebas.SYSTEM));
        entrada.addParameter("code", new CodeType(codigoDePrueba));

        Optional<Parameters> salida = invocar(CodeSystem.class, "$lookup", entrada);
        if (salida.isEmpty()) {
            return Optional.empty();
        }
        Optional<ReglaRefleja> regla = reglaEn(salida.get(), codigoDePrueba);
        reflejas.put(codigoDePrueba, regla);
        return regla;
    }

    /**
     * Arma la regla con lo que el concepto declara, o dice que esa prueba no refleja nada.
     *
     * <p>Una regla mal declarada —una prueba refleja sin su motivo, o una que se refleja a sí
     * misma— <strong>no se aplica</strong>, y se avisa nombrando el concepto. No se lanza, a
     * diferencia del umbral crítico: aquí el defecto del catálogo hace que falte una prueba, no que
     * se dé por buena una cifra peligrosa, y tumbar el registro del resultado sería peor.
     */
    private static Optional<ReglaRefleja> reglaEn(Parameters salida, String codigoDePrueba) {
        Optional<String> refleja =
                propiedad(salida, "prueba-refleja").map(org.hl7.fhir.r5.model.DataType::primitiveValue);
        if (refleja.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ReglaRefleja(
                    codigoDePrueba,
                    refleja.get(),
                    propiedad(salida, "motivo-de-la-refleja")
                            .map(org.hl7.fhir.r5.model.DataType::primitiveValue)
                            .orElse(null)));
        } catch (DatoInvalido malDeclarada) {
            LOG.warn(
                    "El catálogo declara una prueba refleja para «{}» que no se puede aplicar, así que no se "
                            + "añade ninguna: {}",
                    codigoDePrueba,
                    malDeclarada.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void exigirQueLaPruebaExiste(String codigoLocal) {
        Boolean sabido = validados.get(codigoLocal);
        boolean existe = sabido != null ? sabido : preguntarSiExiste(codigoLocal);
        if (!existe) {
            throw new ReglaDeNegocioIncumplida(
                    "«%s» no está en el catálogo de pruebas del laboratorio.".formatted(codigoLocal));
        }
    }

    /** Construye el concepto y lo cachea solo si el servidor llegó a contestar algo. */
    private CodeableConcept resolver(String codigoLocal) {
        Coding local = new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(codigoLocal);
        Optional<String> nombre = nombreEnEspanol(codigoLocal);
        nombre.ifPresent(local::setDisplay);

        CodeableConcept concepto = new CodeableConcept().addCoding(local);
        aLoinc(codigoLocal).ifPresent(concepto::addCoding);
        // El `text` es lo que enseña quien renderiza el recurso, y aquí va el nombre español del
        // catálogo — no el del LOINC. El `display` del LOINC llega en inglés porque su licencia
        // prohíbe alterarlo (ADR-0009) y porque su variante lingüística es/ES no traduce el nombre
        // largo; dejarlo mandar convertiría un informe español en uno inglés.
        nombre.ifPresent(concepto::setText);

        if (nombre.isPresent()) {
            resueltos.put(codigoLocal, concepto);
        }
        return concepto;
    }

    /** {@code $lookup}: el nombre de la prueba tal y como lo publica la guía, en español. */
    private Optional<String> nombreEnEspanol(String codigoLocal) {
        Parameters entrada = new Parameters();
        entrada.addParameter("system", new UriType(CatalogoDePruebas.SYSTEM));
        entrada.addParameter("code", new CodeType(codigoLocal));

        return invocar(CodeSystem.class, "$lookup", entrada).flatMap(salida -> texto(salida, "display"));
    }

    /**
     * {@code $translate}: el LOINC equivalente, con el nombre oficial que devuelve el servidor.
     *
     * <p>El {@code display} no se escribe aquí y no es un detalle: ADR-0009 registra que fijarlo a
     * mano hace fallar la validación en un equipo con locale español y pasarla en la CI.
     */
    private Optional<Coding> aLoinc(String codigoLocal) {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(MAPA_A_LOINC));
        entrada.addParameter("system", new UriType(CatalogoDePruebas.SYSTEM));
        entrada.addParameter("sourceCode", new CodeType(codigoLocal));
        entrada.addParameter("targetSystem", new UriType(LOINC));

        return invocar(ConceptMap.class, "$translate", entrada).flatMap(TerminologiaDelServidor::conceptoTraducido);
    }

    /** {@code $validate-code} contra el conjunto que publica la guía. */
    private boolean preguntarSiExiste(String codigoLocal) {
        Parameters entrada = new Parameters();
        entrada.addParameter("url", new UriType(CONJUNTO_DE_PRUEBAS));
        entrada.addParameter("system", new UriType(CatalogoDePruebas.SYSTEM));
        entrada.addParameter("code", new CodeType(codigoLocal));

        Optional<Boolean> respuesta =
                invocar(ValueSet.class, "$validate-code", entrada).flatMap(salida -> booleano(salida, "result"));
        respuesta.ifPresent(existe -> validados.put(codigoLocal, existe));
        // Sin respuesta no se rechaza: un servidor caído no puede convertir cada petición del
        // laboratorio en un 422. El código sigue guardándose y el reconciliador puede repasarlo.
        return respuesta.orElse(true);
    }

    private Optional<Parameters> invocar(
            Class<? extends org.hl7.fhir.r5.model.Resource> tipo, String operacion, Parameters entrada) {
        try {
            Parameters salida = cliente.operation()
                    .onType(tipo)
                    .named(operacion)
                    .withParameters(entrada)
                    .returnResourceType(Parameters.class)
                    .execute();
            avisadoDeQueNoEsta.set(false);
            return Optional.ofNullable(salida);
        } catch (RuntimeException noContesta) {
            if (avisadoDeQueNoEsta.compareAndSet(false, true)) {
                LOG.warn(
                        "El servidor de terminología no contesta a {}: los recursos se publican con el código y sin "
                                + "nombre, y las pruebas desconocidas dejan de rechazarse. Causa: {}",
                        operacion,
                        noContesta.toString());
            }
            return Optional.empty();
        }
    }

    /**
     * El {@code Coding} del primer emparejamiento útil.
     *
     * <p>Se descartan los emparejamientos que el mapa declara como <em>no equivalentes</em> en la
     * dirección que importa: {@code HTO → 4544-3} dice «el término LOINC es más estrecho», y
     * publicarlo como si fuera lo mismo afirmaría un método que el laboratorio no ha declarado. Se
     * miran las dos formas del elemento porque HAPI aún devuelve la de R4.
     */
    private static Optional<Coding> conceptoTraducido(Parameters salida) {
        if (!booleano(salida, "result").orElse(false)) {
            return Optional.empty();
        }
        for (Parameters.ParametersParameterComponent emparejamiento : salida.getParameter()) {
            if (!"match".equals(emparejamiento.getName())) {
                continue;
            }
            String relacion = emparejamiento.getPart().stream()
                    .filter(parte -> "relationship".equals(parte.getName()) || "equivalence".equals(parte.getName()))
                    .map(parte -> parte.getValue().primitiveValue())
                    .findFirst()
                    .orElse("");
            if (!"equivalent".equals(relacion)) {
                continue;
            }
            Optional<Coding> concepto = emparejamiento.getPart().stream()
                    .filter(parte -> "concept".equals(parte.getName()))
                    .map(parte -> parte.getValue())
                    .filter(Coding.class::isInstance)
                    .map(Coding.class::cast)
                    .findFirst();
            if (concepto.isPresent()) {
                return concepto;
            }
        }
        return Optional.empty();
    }

    /**
     * Arma el umbral con lo que el concepto declara, o dice que no hay.
     *
     * <p>Sin ningún límite no hay umbral y se contesta vacío. Con límite pero sin unidad o sin
     * procedencia hay <strong>un defecto del catálogo</strong>, y eso no se contesta vacío: un umbral
     * a medias no se puede usar y quedarse callado lo convertiría en «esta prueba no tiene crítico»,
     * que es la mentira exacta que hay que evitar. Se lanza nombrando la prueba, para que quien lo
     * lea sepa qué concepto de la guía hay que arreglar.
     */
    private static Optional<UmbralCritico> umbralEn(Parameters salida, String codigoDePrueba) {
        BigDecimal bajo = decimalDe(salida, "limite-critico-bajo");
        BigDecimal alto = decimalDe(salida, "limite-critico-alto");
        if (bajo == null && alto == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UmbralCritico(
                    codigoDePrueba,
                    bajo,
                    alto,
                    unidadDe(salida),
                    propiedad(salida, "procedencia-del-valor-critico")
                            .map(org.hl7.fhir.r5.model.DataType::primitiveValue)
                            .orElse(null)));
        } catch (DatoInvalido incompleto) {
            throw new NoSeSabeSiEsCritico("El catálogo publica un límite crítico para «%s» que no se puede usar: %s"
                    .formatted(codigoDePrueba, incompleto.getMessage()));
        }
    }

    /**
     * El valor de una propiedad del concepto, tal y como lo devuelve {@code $lookup}.
     *
     * <p>Cada propiedad llega como un parámetro {@code property} con dos partes, {@code code} y
     * {@code value}; no como un parámetro con el nombre de la propiedad. Buscar por nombre no
     * encuentra nada y no da error — devuelve vacío, que aquí significaría «no tiene umbral».
     */
    private static Optional<org.hl7.fhir.r5.model.DataType> propiedad(Parameters salida, String codigo) {
        return salida.getParameter().stream()
                .filter(parametro -> "property".equals(parametro.getName()))
                .filter(parametro -> parametro.getPart().stream()
                        .anyMatch(parte -> "code".equals(parte.getName())
                                && parte.getValue() != null
                                && codigo.equals(parte.getValue().primitiveValue())))
                .flatMap(parametro -> parametro.getPart().stream())
                .filter(parte -> "value".equals(parte.getName()) && parte.getValue() != null)
                .map(Parameters.ParametersParameterComponent::getValue)
                .findFirst();
    }

    /**
     * Un límite, leído de su forma textual y no del tipo con el que venga envuelto.
     *
     * <p>El tipo declarado es {@code decimal} y HAPI lo devuelve como tal, pero leerlo por
     * {@code primitiveValue()} hace que un servidor que lo mande como cadena siga funcionando — y un
     * umbral es justo donde no interesa que el sistema se rompa por un envoltorio. Lo que sí se
     * rechaza es una cifra que no es una cifra: eso no se aproxima.
     */
    private static BigDecimal decimalDe(Parameters salida, String codigo) {
        return propiedad(salida, codigo)
                .map(org.hl7.fhir.r5.model.DataType::primitiveValue)
                .filter(valor -> valor != null && !valor.isBlank())
                .map(valor -> {
                    try {
                        return new BigDecimal(valor);
                    } catch (NumberFormatException noEsUnaCifra) {
                        throw new NoSeSabeSiEsCritico(
                                "El catálogo declara «%s» como límite crítico y eso no es una cifra.".formatted(valor));
                    }
                })
                .orElse(null);
    }

    /** La unidad del concepto, que llega como {@code Coding} y no como cadena. */
    private static String unidadDe(Parameters salida) {
        return propiedad(salida, "unidad-ucum")
                .filter(Coding.class::isInstance)
                .map(valor -> ((Coding) valor).getCode())
                .orElse(null);
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
                .map(Parameters.ParametersParameterComponent::getValue)
                .filter(BooleanType.class::isInstance)
                .map(valor -> ((BooleanType) valor).getValue())
                .findFirst();
    }
}
