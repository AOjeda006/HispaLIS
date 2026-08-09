package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Practitioner;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Las facultativas que firman los resultados en los tests.
 *
 * <p>Existe porque <strong>quien valida tiene que estar dado de alta</strong>: el servidor comprueba
 * la integridad referencial al escribir, así que un {@code Provenance.agent.who} que apunte a nadie
 * se rechaza. Es lo correcto —una firma con un nombre inventado no es una firma—, pero significa que
 * todo test que valide un resultado necesita a esta persona en el servidor.
 *
 * <p>Son <strong>dos</strong> y no una porque la doble validación de un resultado crítico exige
 * precisamente eso: dos personas distintas. Con una sola dada de alta, el test que la comprueba no se
 * podría ni escribir.
 *
 * <p>Se dan de alta con un {@code PUT} de identificador fijo, no con un {@code POST}: así son siempre
 * las mismas personas, la operación es idempotente y cada clase de test puede pedirlas sin saber si
 * otra lo hizo antes.
 */
public final class FacultativaDePrueba {

    /** Lo que se manda en el parámetro {@code facultativo} de {@code $validar}. */
    public static final String REFERENCIA = "Practitioner/analisis-clinicos";

    /** La segunda firma de un resultado crítico. Es otra persona, que es justo lo que se comprueba. */
    public static final String SEGUNDA_REFERENCIA = "Practitioner/analisis-clinicos-guardia";

    private static final Map<String, String> APELLIDOS = Map.of(
            REFERENCIA, "Núñez Peña",
            SEGUNDA_REFERENCIA, "Muñoz Álvarez");

    private static final Set<String> YA_ESTAN = ConcurrentHashMap.newKeySet();

    private FacultativaDePrueba() {
        // Utilidad.
    }

    /**
     * Se asegura de que la facultativa existe y devuelve su referencia.
     *
     * @param rest el cliente del test
     * @param contexto el contexto FHIR con el que serializar
     * @return {@link #REFERENCIA}
     */
    public static String darDeAlta(TestRestTemplate rest, FhirContext contexto) {
        return darDeAlta(rest, contexto, REFERENCIA);
    }

    /**
     * Se asegura de que una facultativa concreta existe y devuelve su referencia.
     *
     * @param referencia {@link #REFERENCIA} o {@link #SEGUNDA_REFERENCIA}
     */
    public static String darDeAlta(TestRestTemplate rest, FhirContext contexto, String referencia) {
        if (!YA_ESTAN.add(referencia)) {
            return referencia;
        }

        Practitioner quien = new Practitioner();
        quien.setId(referencia.substring(referencia.indexOf('/') + 1));
        quien.addName(new HumanName().setFamily(APELLIDOS.get(referencia)).addGiven("Elena"));

        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        HttpEntity<String> cuerpo = new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(quien), cabeceras);

        ResponseEntity<String> respuesta = rest.exchange("/fhir/" + referencia, HttpMethod.PUT, cuerpo, String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo dar de alta a quien firma: %s", respuesta.getBody())
                .isIn(HttpStatus.OK, HttpStatus.CREATED);

        return referencia;
    }
}
