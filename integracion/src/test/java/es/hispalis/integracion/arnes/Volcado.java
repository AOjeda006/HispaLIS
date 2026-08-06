package es.hispalis.integracion.arnes;

import ca.uhn.fhir.context.FhirContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Deja en {@code target/canal/} lo que los canales producen, para el validador oficial.
 *
 * <p>Que un canal funcione y que lo que produce sea <strong>conforme</strong> son dos cosas
 * distintas: un mapeo v2 → FHIR puede ejecutarse entero y producir un recurso que ningún servidor
 * acepte. Los tests comprueban lo primero; el paso del validador en la CI, lo segundo.
 */
public final class Volcado {

    private static final Path DESTINO = Path.of("target", "canal");
    private static final FhirContext CONTEXTO = FhirContext.forR5();

    private Volcado() {
        // Utilidad.
    }

    /** Escribe el recurso con el nombre indicado. */
    public static void escribir(String nombre, IBaseResource recurso) {
        try {
            Files.createDirectories(DESTINO);
            Files.writeString(
                    DESTINO.resolve(nombre + ".json"),
                    CONTEXTO.newJsonParser().setPrettyPrint(true).encodeResourceToString(recurso));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo volcar %s para el validador".formatted(nombre), e);
        }
    }
}
