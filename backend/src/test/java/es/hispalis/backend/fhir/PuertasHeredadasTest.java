package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.Delete;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.Patch;
import ca.uhn.fhir.rest.annotation.Update;
import es.hispalis.backend.TestDeIntegracion;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Las puertas de escritura que los proveedores propios <strong>heredan</strong> sin cerrar.
 *
 * <p>{@code BaseJpaResourceProvider} expone {@code create}, {@code update}, {@code patch},
 * {@code delete}, {@code metaAdd}, {@code metaDelete} y {@code expunge}. Los proveedores de este
 * proyecto solo sustituían las dos primeras, así que las otras cinco seguían siendo las de HAPI:
 * <strong>escriben la proyección y dejan el dominio atrás</strong>, en silencio. Es el fallo que
 * describe {@code ADR-0014}, y esta vez se sabía que estaba abierto.
 *
 * <p>Un test por verbo, y todos contra un paciente <strong>de verdad</strong>: un verbo que se
 * rechazara por «no existe el recurso» no probaría nada. Lo que se comprueba en cada uno son las
 * dos mitades del daño — que la API dice que no, y que <strong>el recurso sigue como estaba</strong>.
 * La segunda importa más: un `204` seguido de una proyección corrupta es exactamente la forma en
 * que estas puertas hacen daño.
 *
 * @see PuertaHeredadaCerrada la regla, y por qué se rechaza en vez de implementarse
 */
class PuertasHeredadasTest extends TestDeIntegracion {

    // El 95 ya lo usa `InformeConLineasPendientesTest`, y el NHC es único: dos clases en el mismo
    // proceso con el mismo rango se pisan según el orden en que corran.
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(97_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private List<ProveedorPropio> proveedoresPropios;

    @Test
    void patch_no_puede_modificar_un_paciente() {
        String id = crearPaciente();

        ResponseEntity<String> respuesta = enviar(
                HttpMethod.PATCH,
                "/fhir/Patient/" + id,
                "application/json-patch+json",
                """
                [{"op": "replace", "path": "/gender", "value": "male"}]""");

        seRechazaSinTocarNada(respuesta, id);
    }

    @Test
    void delete_no_puede_borrar_un_paciente() {
        String id = crearPaciente();

        ResponseEntity<String> respuesta = enviar(HttpMethod.DELETE, "/fhir/Patient/" + id, null, null);

        seRechazaSinTocarNada(respuesta, id);
    }

    @Test
    void meta_add_no_puede_etiquetar_un_paciente() {
        String id = crearPaciente();

        ResponseEntity<String> respuesta = enviar(
                HttpMethod.POST,
                "/fhir/Patient/" + id + "/$meta-add",
                "application/fhir+json",
                parametrosConEtiqueta());

        seRechazaSinTocarNada(respuesta, id);
    }

    @Test
    void meta_delete_no_puede_quitarle_una_etiqueta_a_un_paciente() {
        String id = crearPaciente();

        ResponseEntity<String> respuesta = enviar(
                HttpMethod.POST,
                "/fhir/Patient/" + id + "/$meta-delete",
                "application/fhir+json",
                parametrosConEtiqueta());

        seRechazaSinTocarNada(respuesta, id);
    }

    @Test
    void expunge_no_puede_borrar_un_paciente_del_todo() {
        String id = crearPaciente();

        ResponseEntity<String> respuesta = enviar(
                HttpMethod.POST,
                "/fhir/Patient/" + id + "/$expunge",
                "application/fhir+json",
                """
                {"resourceType":"Parameters","parameter":[\
                {"name":"expungeDeletedResources","valueBoolean":true},\
                {"name":"expungePreviousVersions","valueBoolean":true}]}""");

        seRechazaSinTocarNada(respuesta, id);
    }

    /**
     * La sexta puerta, que no estaba en la lista porque no existía cuando se escribió.
     *
     * <p>{@code $merge} llegó con HAPI 8: fusiona dos pacientes, reescribe las referencias de todo
     * lo que apuntaba al origen y, con {@code deleteSource}, lo borra. Es la operación que más
     * escribe de todas las heredadas y no aparece en el recuento de {@code ADR-0014} — que hablaba
     * de cinco — porque la lista se hizo contra otra versión de la librería.
     *
     * <p>Es también el argumento de que enumerar las puertas una vez no sirve: <strong>la lista la
     * amplía el framework al actualizarse</strong>, sin avisar y sin que nada falle.
     */
    @Test
    void merge_no_puede_fusionar_dos_pacientes() {
        String origen = crearPaciente();
        String destino = crearPaciente();

        ResponseEntity<String> respuesta = enviar(
                HttpMethod.POST,
                "/fhir/Patient/$hapi.fhir.merge",
                "application/fhir+json",
                """
                {"resourceType":"Parameters","parameter":[\
                {"name":"source-patient","valueReference":{"reference":"Patient/%s"}},\
                {"name":"target-patient","valueReference":{"reference":"Patient/%s"}}]}"""
                        .formatted(origen, destino));

        seRechazaSinTocarNada(respuesta, origen);
        assertThat(existeEnLaProyeccion(destino)).isTrue();
    }

    @Test
    void undo_merge_tampoco() {
        String id = crearPaciente();

        ResponseEntity<String> respuesta = enviar(
                HttpMethod.POST,
                "/fhir/Patient/$hapi.fhir.undo-merge",
                "application/fhir+json",
                """
                {"resourceType":"Parameters","parameter":[\
                {"name":"source-patient","valueReference":{"reference":"Patient/%s"}},\
                {"name":"target-patient","valueReference":{"reference":"Patient/%s"}}]}"""
                        .formatted(id, id));

        seRechazaSinTocarNada(respuesta, id);
    }

    /**
     * Que la lista de puertas no se quede corta cuando HAPI cambie de versión.
     *
     * <p>Los siete tests de arriba prueban que las puertas conocidas están cerradas. Este prueba
     * <strong>que no hay ninguna que no conozcamos</strong>, que es lo único que sobrevive a una
     * actualización de la librería. {@code $merge} entró así: los proveedores ganaron una operación
     * que escribe, nadie la pidió, nada falló y la lista de {@code ADR-0014} —cinco puertas— se
     * quedó corta en silencio.
     *
     * <p>Se recorre por reflexión la jerarquía de cada proveedor propio buscando métodos anotados
     * con los verbos de escritura de HAPI, y se exige que cada uno esté en una de tres cajas:
     * gobernado por el núcleo, cerrado a propósito, o de solo lectura. Un método nuevo no cae en
     * ninguna y rompe el build con su nombre delante.
     */
    @Test
    void no_hay_ninguna_puerta_de_escritura_que_no_conozcamos() {
        // Escriben, y las escribe el núcleo: `create` y `update` están sustituidas en cada proveedor,
        // y `$validar` es una operación PROPIA que ejecuta un caso de uso de dominio.
        Set<String> gobernadas = Set.of("create", "update", "validar");
        Set<String> cerradas =
                Set.of("patch", "delete", "metaAdd", "metaDelete", "expunge", "mergeResource", "resourceUndoMerge");
        // Llevan `@Operation` y no escriben: `$meta`, `$everything` y `$lastn` leen, `$match` busca,
        // y `$validate` —el de HAPI, que no es nuestro `$validar`— es lo contrario de escribir.
        Set<String> deSoloLectura = Set.of(
                "meta",
                "validate",
                "patientInstanceEverything",
                "patientTypeEverything",
                "patientMatch",
                "observationLastN");

        Set<String> desconocidas = new TreeSet<>();
        for (ProveedorPropio proveedor : proveedoresPropios) {
            for (Class<?> clase = proveedor.getClass(); clase != null; clase = clase.getSuperclass()) {
                for (Method metodo : clase.getDeclaredMethods()) {
                    if (metodo.isBridge() || metodo.isSynthetic() || !escribeOPuedeEscribir(metodo)) {
                        continue;
                    }
                    String nombre = metodo.getName();
                    if (!gobernadas.contains(nombre) && !cerradas.contains(nombre) && !deSoloLectura.contains(nombre)) {
                        desconocidas.add(clase.getSimpleName() + "#" + nombre);
                    }
                }
            }
        }

        assertThat(desconocidas)
                .as(
                        """
                        HAPI expone verbos de escritura que este proyecto no ha clasificado: %s.
                        Cada uno es una puerta al lado del núcleo hasta que se demuestre lo contrario.                         Decide si la gobierna el dominio, si se cierra en `SoloLosVerbosQueElNucleoGobierna`                         o si solo lee, y añádela a la caja que le toque con un test.""",
                        desconocidas)
                .isEmpty();
    }

    /** Los métodos que HAPI publica como verbos capaces de escribir. */
    private static boolean escribeOPuedeEscribir(Method metodo) {
        return metodo.isAnnotationPresent(Create.class)
                || metodo.isAnnotationPresent(Update.class)
                || metodo.isAnnotationPresent(Delete.class)
                || metodo.isAnnotationPresent(Patch.class)
                || metodo.isAnnotationPresent(Operation.class);
    }

    /**
     * La comprobación que hacen todos: la API dice que no, con un {@code OperationOutcome}, y el
     * paciente sigue exactamente donde estaba — en la proyección y en el dominio.
     */
    private void seRechazaSinTocarNada(ResponseEntity<String> respuesta, String id) {
        assertThat(respuesta.getStatusCode().is2xxSuccessful())
                .as("la puerta sigue abierta: %s → %s", respuesta.getStatusCode(), respuesta.getBody())
                .isFalse();

        OperationOutcome fallo = contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
        assertThat(fallo.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(fallo.getIssueFirstRep().getDiagnostics())
                .as("tiene que rechazarlo EL LABORATORIO y decir qué hacer en su lugar, no HAPI por "
                        + "un ajuste que mañana se cambia sin que nadie lo relacione con esto")
                .contains(PuertaHeredadaCerrada.CABECERA);

        assertThat(existeEnLaProyeccion(id))
                .as("el recurso desapareció o cambió pese al error: el daño ya está hecho")
                .isTrue();
        assertThat(estaEnElDominio(id)).as("el agregado tiene que seguir ahí").isTrue();
    }

    private boolean existeEnLaProyeccion(String id) {
        return rest.getForEntity("/fhir/Patient/" + id, String.class)
                .getStatusCode()
                .is2xxSuccessful();
    }

    private boolean estaEnElDominio(String id) {
        Long filas =
                jdbc.queryForObject("SELECT count(*) FROM dominio.paciente WHERE id = CAST(? AS uuid)", Long.class, id);
        return filas != null && filas == 1L;
    }

    /** Crea un paciente por la vía legítima y devuelve su id lógico. */
    private String crearPaciente() {
        Patient paciente = new Patient();
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.NHC)
                .setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Muñoz Peña").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);

        ResponseEntity<String> creado = enviar(
                HttpMethod.POST,
                "/fhir/Patient",
                "application/fhir+json",
                contexto.newJsonParser().encodeResourceToString(paciente));
        assertThat(creado.getStatusCode().is2xxSuccessful())
                .as("el paciente de la prueba no se pudo crear: %s", creado.getBody())
                .isTrue();

        return contexto.newJsonParser()
                .parseResource(Patient.class, creado.getBody())
                .getIdElement()
                .getIdPart();
    }

    private static String parametrosConEtiqueta() {
        return """
                {"resourceType":"Parameters","parameter":[{"name":"meta","valueMeta":{"tag":[\
                {"system":"https://ejemplo.invalid/etiquetas","code":"la-que-sea"}]}}]}""";
    }

    private ResponseEntity<String> enviar(HttpMethod verbo, String ruta, String tipo, String cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        if (tipo != null) {
            cabeceras.setContentType(MediaType.valueOf(tipo));
        }
        return rest.exchange(ruta, verbo, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }
}
