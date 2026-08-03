package es.hispalis.backend;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;

/**
 * Comprobaciones de que la cadena de construcción del backend está bien montada.
 *
 * <p>No prueban comportamiento de negocio —eso llega por TDD en los ítems 6 a 12—, sino las dos
 * cosas que, si están mal, hacen fallar todo lo demás de forma confusa: que la versión de FHIR es
 * R5 y que el juego de caracteres español sobrevive a un viaje de ida y vuelta por el serializador.
 */
class AndamiajeTest extends TestDeIntegracion {

    @Test
    void el_contexto_de_spring_arranca() {
        // Que el contexto levante es la comprobación; si no lo hace, el test falla al inicializarse.
    }

    @Test
    void el_contexto_fhir_es_r5_y_no_r4() {
        FhirContext contexto = FhirContext.forR5();

        assertThat(contexto.getVersion().getVersion()).isEqualTo(FhirVersionEnum.R5);
        assertThat(contexto.getVersion().getVersion().getFhirVersionString()).isEqualTo("5.0.0");
    }

    @Test
    void los_apellidos_espanoles_sobreviven_al_serializador() {
        // MUÑOZ, ÁLVAREZ y PEÑA son casos de prueba obligatorios del proyecto, no opcionales.
        // «de la Torre Gómez» está aquí porque es el que rompe el heurístico de partir por el
        // espacio: el apellido completo va entero en `family`, nunca troceado.
        FhirContext contexto = FhirContext.forR5();
        Patient paciente = new Patient();
        paciente.addName(new HumanName().setFamily("de la Torre Muñoz").addGiven("Álvaro"));
        paciente.addName(new HumanName().setFamily("Peña Álvarez").addGiven("Begoña"));

        String json = contexto.newJsonParser().encodeResourceToString(paciente);
        Patient releido = contexto.newJsonParser().parseResource(Patient.class, json);

        assertThat(releido.getName().get(0).getFamily()).isEqualTo("de la Torre Muñoz");
        assertThat(releido.getName().get(0).getGivenAsSingleString()).isEqualTo("Álvaro");
        assertThat(releido.getName().get(1).getFamily()).isEqualTo("Peña Álvarez");
        assertThat(releido.getName().get(1).getGivenAsSingleString()).isEqualTo("Begoña");
    }
}
