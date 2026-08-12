package es.hispalis.backend.infraestructura.exportacion;

import es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Los NDJSON, en una carpeta por trabajo.
 *
 * <p>Una carpeta por trabajo y no un prefijo en el nombre, porque así <strong>borrar es borrar un
 * directorio</strong>: no hay forma de dejarse un fichero suelto por un patrón mal escrito, y detectar
 * huérfanos es listar carpetas. Cuando esto viva en MinIO será un prefijo de objeto y la idea no
 * cambia.
 *
 * <p><strong>Los nombres no dicen nada.</strong> El directorio es el id del trabajo, que es un UUID, y
 * el fichero es el tipo de recurso. Ni la cohorte ni el paciente aparecen: un nombre de fichero acaba
 * en el log de una copia de seguridad, y ahí ya no lo protege ningún <em>scope</em>.
 *
 * <p>Se escribe <strong>línea a línea</strong>: una cohorte grande concatenada en memoria es una
 * exportación que funciona en desarrollo y tumba el servidor cuando hace falta.
 */
public class AlmacenDeFicherosEnDisco implements AlmacenDeFicheros {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenDeFicherosEnDisco.class);

    private final Path raiz;

    public AlmacenDeFicherosEnDisco(Path raiz) {
        this.raiz = raiz;
        try {
            Files.createDirectories(raiz);
        } catch (IOException noSePuede) {
            throw new UncheckedIOException("No se pudo preparar el directorio de exportaciones " + raiz, noSePuede);
        }
    }

    @Override
    public long escribir(UUID trabajo, String nombre, Stream<String> lineas) {
        Path destino = carpetaDe(trabajo).resolve(nombre);
        long escritas = 0;
        try {
            Files.createDirectories(destino.getParent());
            try (BufferedWriter salida = Files.newBufferedWriter(destino, StandardCharsets.UTF_8);
                    Stream<String> aEscribir = lineas) {
                for (String linea : (Iterable<String>) aEscribir::iterator) {
                    salida.write(linea);
                    salida.write('\n');
                    escritas++;
                }
            }
        } catch (IOException noSePudo) {
            throw new UncheckedIOException("No se pudo escribir " + nombre + " de la exportación " + trabajo, noSePudo);
        }

        if (escritas == 0) {
            // Un NDJSON vacío en el manifiesto es una entrada que el cliente descarga para nada. Se
            // quita, y el manifiesto no lo menciona.
            borrarSiEsta(destino);
        }
        return escritas;
    }

    @Override
    public InputStream abrir(UUID trabajo, String nombre) {
        try {
            return Files.newInputStream(carpetaDe(trabajo).resolve(nombre));
        } catch (IOException yaNoEsta) {
            throw new UncheckedIOException(
                    "El fichero " + nombre + " de la exportación " + trabajo + " ya no está", yaNoEsta);
        }
    }

    /**
     * Borra la carpeta entera. Idempotente: quitar lo que ya no está no es un error.
     *
     * <p>Si algo se resiste, sale por el log como error y no se traga: un fichero de exportación que no
     * se puede borrar es exactamente lo que hay que ir a mirar a mano.
     */
    @Override
    public void borrar(UUID trabajo) {
        Path carpeta = carpetaDe(trabajo);
        if (!Files.exists(carpeta)) {
            return;
        }
        try (Stream<Path> dentro = Files.walk(carpeta)) {
            dentro.sorted(Comparator.reverseOrder()).forEach(AlmacenDeFicherosEnDisco::borrarSiEsta);
        } catch (IOException noSePudo) {
            LOG.error("No se ha podido borrar la exportación {} del disco. Hay que mirarlo.", trabajo, noSePudo);
        }
    }

    @Override
    public Set<UUID> trabajosConFicheros() {
        Set<UUID> encontrados = new HashSet<>();
        try (Stream<Path> carpetas = Files.list(raiz)) {
            carpetas.filter(Files::isDirectory)
                    .forEach(carpeta -> identidadDe(carpeta).ifPresent(encontrados::add));
        } catch (IOException noSePudoListar) {
            LOG.error("No se ha podido listar el directorio de exportaciones {}.", raiz, noSePudoListar);
        }
        return encontrados;
    }

    private Path carpetaDe(UUID trabajo) {
        return raiz.resolve(trabajo.toString());
    }

    private static java.util.Optional<UUID> identidadDe(Path carpeta) {
        try {
            return java.util.Optional.of(UUID.fromString(carpeta.getFileName().toString()));
        } catch (IllegalArgumentException noEsDeAqui) {
            // Alguien dejó algo en el directorio que no es una exportación. No es nuestro y no se toca.
            return java.util.Optional.empty();
        }
    }

    private static void borrarSiEsta(Path ruta) {
        try {
            Files.deleteIfExists(ruta);
        } catch (IOException noSePudo) {
            LOG.error("No se ha podido borrar {}. Hay que mirarlo.", ruta.getFileName(), noSePudo);
        }
    }
}
