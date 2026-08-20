package es.hispalis.backend.aplicacion.reconciliacion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.dominio.informe.LineaDeLaPeticion;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.fhir.FacultativaDePrueba;
import es.hispalis.backend.fhir.informe.TraductorDeInforme;
import es.hispalis.backend.fhir.resultado.TraductorDeProcedencia;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r5.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * La premisa sobre la que se sostiene el reconciliador: <strong>la proyección que publica la
 * escritura y la que él regenera son la misma</strong>.
 *
 * <p>Esta clase existe por un rojo intermitente de {@link ReconciliacionDelLaboratorioEnteroTest}: en
 * una ejecución de CI aparecía un {@code DiagnosticReport} divergente que nadie había tocado, uno de
 * cada tantos, y no se iba. No era un fallo del test.
 *
 * <h2>El mecanismo</h2>
 *
 * <p>La emisión escribe el recurso a partir del agregado <strong>en memoria</strong>, con la marca de
 * tiempo tal y como salió de {@code Instant.now()}. El reconciliador lo regenera a partir del
 * agregado <strong>releído de la base</strong>. Entre los dos hay tres precisiones distintas y no
 * encajan:
 *
 * <ul>
 *   <li>el reloj da <strong>nanosegundos</strong> —en Linux de verdad; en Windows la parte por debajo
 *       del milisegundo es casi constante, y por eso esto no se reproduce en un portátil—;
 *   <li>{@code timestamptz} guarda <strong>microsegundos</strong> y <strong>redondea</strong> lo que
 *       sobra, no lo trunca;
 *   <li>el {@code instant} de FHIR publica <strong>milisegundos</strong>.
 * </ul>
 *
 * <p>Cuando el reloj cae en el último medio microsegundo de un milisegundo, el redondeo de la base
 * cruza la frontera del milisegundo y las dos proyecciones del mismo recurso se separan en uno. Son
 * <strong>500 ns de cada millón</strong>: uno de cada dos mil recursos con marca de tiempo publicada
 * como {@code instant}, y en el circuito hay dos por paciente —el informe y la firma—, así que un
 * barrido de sesenta pacientes lo pisa una vez de cada diecisiete.
 *
 * <p>Lo que no falla es la transacción: la proyección sigue escribiéndose dentro de la del dominio
 * (D3, §9). Lo que no se sostenía es el corolario — que por escribirse a la vez digan lo mismo—,
 * porque una de las dos copias venía de la memoria y la otra de la base.
 *
 * <h2>Por qué se prueba con una marca puesta a mano</h2>
 *
 * <p>Porque con el reloj de verdad esto es un dado de dos mil caras, y un test que solo falla a veces
 * no es un test. La marca se pone en la franja mala a propósito: es la misma que el reloj de un
 * servidor Linux produce una vez de cada dos mil, y recorre exactamente el mismo camino.
 */
class LaProyeccionSobreviveALaIdaYVueltaTest extends TestDeIntegracion {

    /**
     * Un instante en la franja mala: {@code …123_999_600 ns}.
     *
     * <p>Los últimos 400 ns caen por encima de la mitad del último microsegundo del milisegundo 123,
     * así que la base redondea a {@code .124000} y al publicarlo sale {@code .124} donde la memoria
     * decía {@code .123}.
     */
    private static final Instant EN_LA_FRANJA = Instant.ofEpochSecond(1_755_000_000L, 123_999_600L);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RepositorioDeInformes informes;

    @Autowired
    private RepositorioDeResultados resultados;

    @Autowired
    private RepositorioDePeticiones peticiones;

    @Autowired
    private TraductorDeInforme aInforme;

    @Autowired
    private TraductorDeProcedencia aProcedencia;

    @Autowired
    private DaoRegistry daos;

    private CircuitoDePrueba circuito;

    /** El laboratorio que emite, creado por el circuito: el emisor tiene que existir de verdad. */
    private String laboratorio;

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    @Test
    void el_informe_se_publica_igual_desde_la_memoria_que_desde_la_base() {
        Resultado firmado = unResultadoFirmadoEnLaFranja();
        resultados.actualizar(firmado);
        Peticion linea =
                peticiones.buscarPorId(firmado.peticionId().orElseThrow()).orElseThrow();

        Informe recienEmitido = Informe.emitir(
                List.of(firmado),
                List.of(new LineaDeLaPeticion(
                        linea.id(), linea.numeroDePeticion(), linea.codigoDePrueba(), true, false)),
                laboratorio,
                EN_LA_FRANJA);
        informes.guardar(recienEmitido);
        publicar(aInforme.aFhir(recienEmitido));

        Informe releido = informes.buscarDePaciente(firmado.pacienteId()).stream()
                .filter(informe -> informe.id().equals(recienEmitido.id()))
                .findFirst()
                .orElseThrow();

        assertThat(comoSePublica(aInforme.aFhir(releido)))
                .as("el reconciliador regenera desde la base lo que la emisión publicó desde la memoria: "
                        + "si no coinciden, inventa una divergencia sobre un informe que está bien")
                .isEqualTo(comoSePublica(aInforme.aFhir(recienEmitido)));
    }

    @Test
    void la_firma_se_publica_igual_desde_la_memoria_que_desde_la_base() {
        Resultado firmado = unResultadoFirmadoEnLaFranja();
        resultados.actualizar(firmado);

        Resultado releido = resultados.buscarPorId(firmado.id()).orElseThrow();

        assertThat(comoSePublica(aProcedencia.aFhir(releido).get(0)))
                .as("la procedencia se deriva de la firma, y la firma lleva la misma marca de tiempo")
                .isEqualTo(comoSePublica(aProcedencia.aFhir(firmado).get(0)));
    }

    /**
     * Un resultado medido por la API y firmado <strong>en la franja mala</strong>.
     *
     * <p>La firma no se puede fechar desde fuera con esa precisión —lo que llega por HTTP es un
     * {@code instant} de FHIR, que ya viene en milisegundos—, así que el resultado se informa por el
     * circuito de verdad y la firma se pone sobre el agregado, que es donde el reloj del servidor la
     * pone en producción.
     */
    private Resultado unResultadoFirmadoEnLaFranja() {
        UUID id = UUID.fromString(CircuitoDePrueba.identidadDe(unResultadoMedido()));
        Resultado firmado = resultados
                .buscarPorId(id)
                .orElseThrow()
                .validar(codigoDePrueba -> Optional.empty(), FacultativaDePrueba.REFERENCIA, EN_LA_FRANJA);
        aProcedencia.aFhir(firmado).forEach(this::publicar);
        return firmado;
    }

    /** El circuito hasta la cifra medida, sin firmar: petición, muestra y resultado. */
    private String unResultadoMedido() {
        laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        FacultativaDePrueba.darDeAlta(rest, contexto);
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        return circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
    }

    /**
     * Publica lo que la escritura publicaría.
     *
     * <p>Este test no pasa por el caso de uso —necesita fechar a mano, y por HTTP no se puede—, así
     * que le toca a él dejar la proyección como la habría dejado la emisión. No es cosmética: el
     * PostgreSQL es uno para toda la ejecución, y un agregado sin su recurso publicado es una
     * divergencia que se lleva por delante al test del barrido completo.
     */
    private void publicar(Resource recurso) {
        daos.getResourceDaoOrNull(recurso.fhirType()).update(recurso, new SystemRequestDetails());
    }

    private String comoSePublica(Resource recurso) {
        return contexto.newJsonParser().encodeResourceToString(recurso);
    }
}
