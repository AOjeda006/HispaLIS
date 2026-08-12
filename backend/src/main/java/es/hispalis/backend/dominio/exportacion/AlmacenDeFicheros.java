package es.hispalis.backend.dominio.exportacion;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Dónde se dejan los NDJSON mientras existen.
 *
 * <p>Es un puerto y no una llamada a {@code Files} por una razón concreta: <strong>el borrado</strong>.
 * El día que estos ficheros vivan en MinIO o en un bucket, lo que no puede cambiar es que hay alguien
 * a quien pedirle que los quite y que sabe decir cuáles quedan. Las tres operaciones que importan aquí
 * son {@link #borrar}, {@link #trabajosConFicheros} y, solo después, escribir y leer.
 *
 * <p><strong>Se escribe por líneas, no por cadenas.</strong> Una cohorte de mil personas cargada en
 * memoria para concatenarla es el anti-patrón que la propia IG de Bulk Data señala, y es el mismo por
 * los dos lados: quien ingiere procesa línea a línea, y quien produce también.
 */
public interface AlmacenDeFicheros {

    /**
     * Escribe un NDJSON.
     *
     * @param trabajo a qué exportación pertenece. Es lo único que decide dónde va: nunca el paciente
     * @param nombre el nombre del fichero, derivado del tipo de recurso
     * @param lineas los recursos, uno por línea, ya serializados
     * @return cuántas líneas se escribieron
     */
    long escribir(UUID trabajo, String nombre, Stream<String> lineas);

    /** Abre un fichero para servirlo. Vacío si ya no está — que es lo normal pasada la caducidad. */
    InputStream abrir(UUID trabajo, String nombre);

    /** Quita todo lo de un trabajo. Idempotente: borrar lo que ya no está no es un error. */
    void borrar(UUID trabajo);

    /**
     * Qué trabajos tienen ficheros ahora mismo.
     *
     * <p>Lo pregunta el barrendero para encontrar <strong>huérfanos</strong>: lo que está en el disco y
     * no está en el registro no lo va a reclamar nadie, y es exactamente lo que queda tras un reinicio
     * a mitad de exportación.
     */
    Set<UUID> trabajosConFicheros();
}
