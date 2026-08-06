package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * La facultativa que firma los resultados en los tests.
 *
 * <p>Existe porque <strong>quien valida tiene que estar dado de alta</strong>: el servidor comprueba
 * la integridad referencial al escribir, así que un {@code Provenance.agent.who} que apunte a nadie
 * se rechaza. Es lo correcto —una firma con un nombre inventado no es una firma—, pero significa que
 * todo test que valide un resultado necesita a esta persona en el servidor.
 *
 * <p>Se da de alta con un {@code PUT} de identificador fijo, no con un {@code POST}: así es siempre
 * la misma persona, la operación es idempotente y cada clase de test puede pedirla sin saber si otra
 * lo hizo antes.
 */
public final class FacultativaDePrueba {

    /** Lo que se manda en el parámetro {@code facultativo} de {@code $validar}. */
    public static final String REFERENCIA = "Practitioner/analisis-clinicos";

    private static final String IDENTIDAD = "analisis-clinicos";

    private static final AtomicBoolean YA_ESTA = new AtomicBoolean(false);

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
        if (YA_ESTA.get()) {
            return REFERENCIA;
        }

        Practitioner quien = new Practitioner();
        quien.setId(IDENTIDAD);
        quien.addName(new HumanName().setFamily("Núñez Peña").addGiven("Elena"));

        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        HttpEntity<String> cuerpo = new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(quien), cabeceras);

        ResponseEntity<String> respuesta = rest.exchange("/fhir/" + REFERENCIA, HttpMethod.PUT, cuerpo, String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo dar de alta a quien firma: %s", respuesta.getBody())
                .isIn(HttpStatus.OK, HttpStatus.CREATED);

        YA_ESTA.set(true);
        return REFERENCIA;
    }
}
