package es.hispalis.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Que ninguna clase con {@code @SpringBootTest} propio se deje un interruptor del padre.
 *
 * <h2>Por qué existe este test</h2>
 *
 * <p>Una clase que declara su <strong>propio</strong> {@code @SpringBootTest} oculta el de {@link
 * TestDeIntegracion} <strong>entero</strong>, propiedades incluidas, y arranca con los valores de
 * producción. Con la seguridad eso da la cara enseguida —el contexto no levanta sin emisor—, pero con
 * los dos interruptores que consumen {@code outbox.hecho} no da la cara <em>aquí</em>: Spring
 * <strong>cachea</strong> los contextos y no los destruye al terminar la clase, así que el notificador
 * de la clase descuidada sigue vaciando el outbox mientras corre otra, le quita el hecho y lo descarta
 * con su propio catálogo. Lo que se ve, tres ficheros más allá, es una espera agotada sin una sola
 * excepción.
 *
 * <p>Ha pasado <strong>seis veces</strong>: cinco en el ítem 51 y la sexta en {@code
 * RelayDelOutboxTest}, que repetía la línea de la seguridad —con su comentario y todo— y se dejaba las
 * otras dos. Que una trampa reaparezca cinco veces después de documentarla dice que la documentación
 * no es el sitio.
 *
 * <h2>La lista se deduce, no se escribe</h2>
 *
 * <p>Las claves exigidas <strong>se leen de la anotación de {@link TestDeIntegracion}</strong>. Una
 * lista escrita a mano aquí nacería correcta y quedaría vieja el día que el padre gane un interruptor
 * nuevo — que es exactamente la forma de esta trampa, no su cura. Y no se exige un valor: se exige que
 * la clase <strong>lo diga</strong>. {@code SeguridadSmartTest} enciende la seguridad a propósito y
 * {@code NotificacionesTest} enciende la entrega; lo que no vale es heredar el valor de producción sin
 * enterarse.
 */
class InterruptoresDeContextoTest {

    @Test
    void ninguna_clase_con_arranque_propio_se_deja_un_interruptor_del_padre() {
        Set<String> exigidas = clavesDe(TestDeIntegracion.class);
        assertThat(exigidas)
                .as("TestDeIntegracion tiene que declarar los interruptores; sin ellos este test no vigila nada")
                .isNotEmpty();

        Map<String, Set<String>> descuidadas = new LinkedHashMap<>();
        for (Class<?> clase : lasQueDeclaranSuPropioArranque()) {
            if (clase.equals(TestDeIntegracion.class)) {
                continue;
            }
            Set<String> suyas = clavesDe(clase);
            Set<String> faltan = new TreeSet<>(exigidas);
            faltan.removeAll(suyas);
            if (!faltan.isEmpty()) {
                descuidadas.put(clase.getSimpleName(), faltan);
            }
        }

        assertThat(descuidadas)
                .as(
                        """
                        Estas clases declaran su propio @SpringBootTest y no repiten interruptores que \
                        TestDeIntegracion sí fija, así que arrancan con el valor de PRODUCCIÓN. Si el \
                        interruptor consume `outbox.hecho`, el fallo no sale aquí: sale en otra clase, \
                        como una espera agotada y sin excepción, porque Spring cachea el contexto y este \
                        sigue vivo. Repite la línea en la anotación de la clase, con el valor que le \
                        convenga.""")
                .isEmpty();
    }

    /** Las claves {@code hispalis.…} que una clase fija en su propio {@code @SpringBootTest}. */
    private static Set<String> clavesDe(Class<?> clase) {
        SpringBootTest anotacion = clase.getDeclaredAnnotation(SpringBootTest.class);
        if (anotacion == null) {
            return Set.of();
        }
        return Arrays.stream(anotacion.properties())
                .map(propiedad -> propiedad.split("=", 2)[0].trim())
                .filter(clave -> clave.startsWith("hispalis."))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Recorre las clases de test compiladas y se queda con las que llevan la anotación puesta encima.
     *
     * <p>Se carga sin inicializar ({@code initialize = false}): esto mira anotaciones, y ejecutar el
     * bloque estático de doscientas clases de test para leer una anotación sería pagar el arranque de
     * todas para no usar ninguna.
     */
    private static List<Class<?>> lasQueDeclaranSuPropioArranque() {
        Path raiz = raizDeLasClasesDeTest();
        ClassLoader cargador = InterruptoresDeContextoTest.class.getClassLoader();
        List<Class<?>> encontradas = new ArrayList<>();
        try (Stream<Path> ficheros = Files.walk(raiz)) {
            for (Path fichero :
                    ficheros.filter(f -> f.toString().endsWith(".class")).toList()) {
                String nombre = raiz.relativize(fichero)
                        .toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                try {
                    Class<?> clase = Class.forName(nombre, false, cargador);
                    if (clase.getDeclaredAnnotation(SpringBootTest.class) != null) {
                        encontradas.add(clase);
                    }
                } catch (ClassNotFoundException | LinkageError ignorada) {
                    // Una clase que no se deja cargar sin inicializar no puede llevar la anotación
                    // puesta y arrancar un contexto: si no carga, tampoco corre.
                }
            }
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
        assertThat(encontradas)
                .as("no se encontró ninguna clase con @SpringBootTest: el rastreo no está mirando donde debe")
                .isNotEmpty();
        return encontradas;
    }

    /** El directorio del que salieron las clases de test, deducido de una de ellas. */
    private static Path raizDeLasClasesDeTest() {
        try {
            return Paths.get(TestDeIntegracion.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (java.net.URISyntaxException fallo) {
            throw new IllegalStateException("No se pudo localizar el directorio de clases de test", fallo);
        }
    }
}
