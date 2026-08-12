package es.hispalis.backend.dominio.exportacion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dónde viven los trabajos de exportación.
 *
 * <p>Se persisten, y eso no es evidente: un trabajo asíncrono en memoria sería más simple y bastaría
 * para que el circuito funcione. Lo que no sobreviviría es <strong>el reinicio</strong>, y ahí está el
 * problema: los ficheros sí sobreviven. Un backend que se reinicia a mitad de una exportación dejaría
 * un NDJSON con la cohorte de una enfermedad en un disco y a nadie que supiera que está ahí. De eso va
 * {@link #vivas()}.
 */
public interface RepositorioDeExportaciones {

    void guardar(TrabajoDeExportacion trabajo);

    Optional<TrabajoDeExportacion> buscar(UUID id);

    /** Los trabajos terminados cuyo plazo de descarga ya pasó. */
    List<TrabajoDeExportacion> caducadas(Instant ahora);

    /**
     * Los identificadores de todo trabajo que todavía puede tener ficheros suyos en el disco.
     *
     * <p>Es la mitad que permite detectar <strong>huérfanos</strong>: una carpeta con el id de un
     * trabajo que no está aquí es un volcado que sobrevivió a un reinicio o a un borrado a medias, y
     * nadie lo iba a reclamar nunca.
     */
    Set<UUID> vivas();
}
