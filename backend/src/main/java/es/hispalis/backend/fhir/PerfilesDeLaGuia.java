package es.hispalis.backend.fhir;

import java.util.List;
import java.util.stream.Stream;

/**
 * Los perfiles de la guía de implementación de HispaLIS que este servidor soporta.
 *
 * <p>Es la misma lista que vive en {@code ig/input/fsh/profiles/}, escrita aquí porque el
 * {@code CapabilityStatement} tiene que declararla y el backend no puede leer la guía en tiempo de
 * ejecución. <strong>Que sean dos copias es un riesgo real</strong>, así que hay un test que las
 * cruza contra el FSH y falla en cuanto divergen: si añades un perfil a la guía, añádelo aquí en el
 * mismo commit.
 *
 * <p>La base canónica es la de D19 (§4.8 del diseño) y es <strong>propia, no oficial</strong>.
 */
public enum PerfilesDeLaGuia {
    PACIENTE_LAB_ES("Patient", "paciente-lab-es"),
    PETICION_LAB("ServiceRequest", "peticion-lab"),
    ESPECIMEN_LAB("Specimen", "especimen-lab"),
    RESULTADO_LAB("Observation", "resultado-lab"),
    INFORME_LAB("DiagnosticReport", "informe-lab"),
    LABORATORIO_ORG("Organization", "laboratorio-org"),
    FACULTATIVO_LAB("Practitioner", "facultativo-lab"),
    COBERTURA_LAB("Coverage", "cobertura-lab"),
    NOTIFICACION_EDO("Task", "notificacion-edo"),
    PROCEDENCIA_VALIDACION("Provenance", "procedencia-validacion"),
    COHORTE_VIGILANCIA("Group", "cohorte-vigilancia"),
    TRAZA_DE_ACCESO("AuditEvent", "traza-de-acceso");

    private static final String BASE_CANONICA = "https://aojeda006.github.io/HispaLIS/fhir/StructureDefinition/";

    private final String tipoDeRecurso;
    private final String id;

    PerfilesDeLaGuia(String tipoDeRecurso, String id) {
        this.tipoDeRecurso = tipoDeRecurso;
        this.id = id;
    }

    /**
     * Devuelve los perfiles que restringen un tipo de recurso dado.
     *
     * @param tipoDeRecurso nombre del recurso FHIR, tal y como lo escribe el estándar
     *     ({@code Patient}, {@code ServiceRequest}…)
     * @return los perfiles de ese recurso; lista vacía si la guía no perfila ninguno
     */
    public static List<PerfilesDeLaGuia> deTipo(String tipoDeRecurso) {
        return Stream.of(values())
                .filter(perfil -> perfil.tipoDeRecurso.equals(tipoDeRecurso))
                .toList();
    }

    /** Devuelve el recurso FHIR que este perfil restringe. */
    public String tipoDeRecurso() {
        return tipoDeRecurso;
    }

    /** Devuelve el identificador del perfil, que es también el último segmento de su canónica. */
    public String id() {
        return id;
    }

    /** Devuelve la URI canónica con la que este perfil se identifica y se publica. */
    public String canonica() {
        return BASE_CANONICA + id;
    }
}
