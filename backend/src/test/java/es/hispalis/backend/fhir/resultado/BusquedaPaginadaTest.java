package es.hispalis.backend.fhir.resultado;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Criterio de aceptación 8 (§14 del diseño): búsqueda y paginación.
 *
 * <p>Un facultativo abre la analítica de un paciente y pide «las glucemias». Son más de las que caben
 * en una respuesta, así que el servidor entrega la primera página y <strong>dice cómo seguir</strong>
 * en {@code Bundle.link[relation=next]}.
 *
 * <p>El test recorre las páginas <strong>siguiendo ese enlace y nunca construyendo la URL a mano</strong>,
 * y no es un formalismo: la URL de la página siguiente es <em>opaca</em> —lleva el identificador de la
 * búsqueda cacheada en el servidor, no un desplazamiento que el cliente pueda calcular—. Un cliente
 * que se invente {@code &_getpagesoffset=…} funciona hasta que el servidor cambia de estrategia de
 * paginación, y entonces falla saltándose resultados en silencio, que en un laboratorio significa un
 * resultado que nadie vio.
 *
 * <p>Se comprueba además que el filtro <strong>filtra de verdad</strong>: en la base hay resultados de
 * otro paciente y de otra prueba, y ninguno puede aparecer. Una paginación correcta sobre un filtro
 * roto sigue siendo una fuga de datos de otro paciente.
 */
class BusquedaPaginadaTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    /** Cuántas glucemias tiene el paciente que se busca, y de cuántas en cuántas se piden. */
    private static final int GLUCEMIAS = 12;

    private static final int POR_PAGINA = 5;

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(50_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int puerto;

    @Test
    void recorre_todas_las_paginas_siguiendo_el_enlace_next() {
        String paciente = conGlucemiasYRuido();

        List<String> recogidos = new ArrayList<>();
        int paginas = 0;

        Bundle pagina = buscar(consultaDeGlucemias(paciente, POR_PAGINA));
        assertThat(pagina.getTotal())
                .as("el total lo declara el servidor; sin él el cliente no sabe cuánto le queda")
                .isEqualTo(GLUCEMIAS);

        while (true) {
            paginas++;
            pagina.getEntry()
                    .forEach(entrada ->
                            recogidos.add(entrada.getResource().getIdElement().getIdPart()));

            String siguiente = enlaceSiguiente(pagina);
            if (siguiente == null) {
                break;
            }
            pagina = buscar(URI.create(siguiente));
        }

        assertThat(paginas).isEqualTo((GLUCEMIAS + POR_PAGINA - 1) / POR_PAGINA);
        assertThat(recogidos)
                .as("una página repetida o saltada se ve aquí: ni duplicados ni faltantes")
                .doesNotHaveDuplicates()
                .hasSize(GLUCEMIAS);
    }

    @Test
    void el_enlace_next_apunta_a_donde_el_cliente_puede_ir_y_no_a_la_direccion_interna() {
        String paciente = conGlucemiasYRuido();

        HttpHeaders comoSiViniesePorUnProxy = new HttpHeaders();
        comoSiViniesePorUnProxy.set("X-Forwarded-Host", "laboratorio.example");
        comoSiViniesePorUnProxy.set("X-Forwarded-Proto", "https");

        Bundle pagina = buscar(consultaDeGlucemias(paciente, POR_PAGINA), comoSiViniesePorUnProxy);

        // El navegador no alcanza el backend: lo alcanza a través de un proxy —el servidor de
        // desarrollo de Angular, y el `compose` del ítem 15—. Si el servidor firmase el enlace con
        // la dirección por la que le llegó la petición, la segunda página apuntaría a una máquina
        // que el navegador no puede resolver, y el cliente no puede corregirlo porque para él la
        // URL es opaca. El fallo aparecería solo al pasar de la primera página.
        assertThat(enlaceSiguiente(pagina))
                .as("la página siguiente tiene que estar donde el cliente pueda pedirla")
                .startsWith("https://laboratorio.example/fhir");
    }

    @Test
    void la_busqueda_no_devuelve_lo_de_otro_paciente_ni_lo_de_otra_prueba() {
        String paciente = conGlucemiasYRuido();

        List<Observation> encontrados = todasLasPaginas(consultaDeGlucemias(paciente, POR_PAGINA));

        assertThat(encontrados)
                // Sin esta talla el resto pasaría en vacío: `allSatisfy` sobre una lista vacía se
                // cumple siempre, y una búsqueda que no devuelve nada aprobaría el test.
                .hasSize(GLUCEMIAS)
                .allSatisfy(resultado -> assertThat(resultado.getSubject().getReference())
                        .as("un resultado de otro paciente en esta lista es una fuga, no un fallo de formato")
                        .isEqualTo(paciente))
                .allSatisfy(resultado -> assertThat(
                                CatalogoDePruebas.codigoDe(resultado.getCode()).orElseThrow())
                        .isEqualTo("GLU"));
    }

    @Test
    void lo_que_se_pagina_es_lo_que_el_dominio_tiene_guardado() {
        String paciente = conGlucemiasYRuido();

        List<Observation> encontrados = todasLasPaginas(consultaDeGlucemias(paciente, POR_PAGINA));

        // La búsqueda se sirve de la proyección FHIR, que es su sitio (§9: cero mapeo en lectura).
        // Pero la proyección solo vale lo que valga su acuerdo con el núcleo, así que se contrasta
        // con la tabla del dominio: si algún día divergen, se entera este test y no un facultativo.
        Long enElDominio = jdbc.queryForObject(
                "SELECT count(*) FROM dominio.resultado WHERE paciente_id = ? AND codigo_de_prueba = 'GLU'",
                Long.class,
                UUID.fromString(paciente.substring("Patient/".length())));

        assertThat((long) encontrados.size())
                .as("la proyección que se busca y el núcleo que manda tienen que contar lo mismo")
                .isEqualTo(enElDominio);
    }

    /**
     * Da de alta un paciente con {@link #GLUCEMIAS} glucemias, y alrededor el ruido que hace que el
     * filtro tenga algo que descartar: creatininas del mismo paciente y glucemias de otro.
     *
     * @return la referencia {@code Patient/<uuid>} del paciente que se busca
     */
    private String conGlucemiasYRuido() {
        String paciente = crear(pacienteDePrueba());
        String muestra = crear(especimenDePrueba(paciente));
        for (int i = 0; i < GLUCEMIAS; i++) {
            crear(resultadoDePrueba(paciente, muestra, "GLU", 80 + i));
        }
        for (int i = 0; i < 5; i++) {
            crear(resultadoDePrueba(paciente, muestra, "CREA", 1));
        }

        String otroPaciente = crear(pacienteDePrueba());
        String otraMuestra = crear(especimenDePrueba(otroPaciente));
        for (int i = 0; i < 4; i++) {
            crear(resultadoDePrueba(otroPaciente, otraMuestra, "GLU", 95));
        }
        return paciente;
    }

    private List<Observation> todasLasPaginas(URI primera) {
        List<Observation> recogidos = new ArrayList<>();
        Bundle pagina = buscar(primera);
        while (true) {
            pagina.getEntry().stream()
                    .map(entrada -> (Observation) entrada.getResource())
                    .forEach(recogidos::add);

            String siguiente = enlaceSiguiente(pagina);
            if (siguiente == null) {
                return recogidos;
            }
            pagina = buscar(URI.create(siguiente));
        }
    }

    /** El único modo admitido de llegar a la página siguiente: preguntárselo al servidor. */
    private static String enlaceSiguiente(Bundle pagina) {
        Bundle.BundleLinkComponent enlace = pagina.getLink(Bundle.LINK_NEXT);
        return enlace == null ? null : enlace.getUrl();
    }

    private URI consultaDeGlucemias(String paciente, int porPagina) {
        return URI.create("http://localhost:%d/fhir/Observation?patient=%s&code=%s&_count=%d"
                .formatted(
                        puerto,
                        URLEncoder.encode(paciente, UTF_8),
                        URLEncoder.encode(CatalogoDePruebas.SYSTEM + "|GLU", UTF_8),
                        porPagina));
    }

    private Bundle buscar(URI url) {
        return buscar(url, new HttpHeaders());
    }

    private Bundle buscar(URI url, HttpHeaders cabeceras) {
        ResponseEntity<String> respuesta =
                rest.exchange(url, HttpMethod.GET, new HttpEntity<>(cabeceras), String.class);
        assertThat(respuesta.getStatusCode())
                .as("falló la búsqueda %s: %s", url, respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
    }

    private String crear(IBaseResource recurso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(recurso);

        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + recurso.fhirType(), HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
        assertThat(respuesta.getStatusCode())
                .as("falló al crear %s: %s", recurso.fhirType(), respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(0, location.indexOf("/_history"))
                .substring(location.indexOf("/fhir/") + "/fhir/".length());
    }

    private static Patient pacienteDePrueba() {
        Patient paciente = new Patient();
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.NHC)
                .setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Muñoz Álvarez").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }

    private static Specimen especimenDePrueba(String paciente) {
        Specimen especimen = new Specimen();
        especimen.getAccessionIdentifier().setValue("A" + SIGUIENTE.incrementAndGet());
        especimen.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        especimen.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(SANGRE_VENOSA)));
        especimen.setSubject(new Reference(paciente));
        return especimen;
    }

    private static Observation resultadoDePrueba(String paciente, String especimen, String codigo, int valor) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(codigo)));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(especimen));
        resultado.setValue(
                new Quantity().setValue(valor).setUnit("mg/dL").setSystem(UCUM).setCode("mg/dL"));
        return resultado;
    }
}
