package es.hispalis.integracion.arnes;

import java.util.List;
import java.util.Random;

/**
 * Genera nombres españoles de verdad, con semilla fija, para las pruebas de propiedades.
 *
 * <p>Existe porque una propiedad afirmada sobre {@code MUÑOZ} y dos más no es una propiedad: es tres
 * ejemplos. Lo que hay que poder decir es «<em>para cualquier nombre</em> que este laboratorio pueda
 * recibir, el nombre que sale es el que entró», y para eso las entradas se generan.
 *
 * <p>Lo que el generador garantiza que aparece, porque es lo que rompe las tuberías:
 *
 * <ul>
 *   <li><strong>La eñe</strong> — {@code Muñoz}, {@code Peña}, {@code Núñez}, {@code Ibáñez}.
 *   <li><strong>Las tildes</strong>, incluidas las de las mayúsculas: {@code ÁLVAREZ}.
 *   <li><strong>La cedilla</strong> — {@code Vicenç}, {@code Esperança}. Un laboratorio de Sevilla
 *       atiende a catalanes y valencianos, y la {@code ç} vive en {@code 8859/1} igual que la
 *       {@code ñ}: lo que la pierde no es el juego, es leerlo con otro.
 *   <li><strong>Apellidos dobles y con partícula</strong> — {@code de la Torre Gómez},
 *       {@code Fernández de Córdoba Ruiz}, {@code Camps i Vicenç}. Son los que rompen el heurístico
 *       de partir el nombre familiar por el espacio, que es exactamente lo que el proyecto prohíbe
 *       hacer.
 * </ul>
 *
 * <p>Todo en mayúsculas cuando va a un {@code PID-5}, que es como lo manda un HIS español.
 */
public final class NombresEspanoles {

    private static final List<String> PILA = List.of(
            "Begoña",
            "María",
            "Rocío",
            "Ángel",
            "Jesús",
            "Iñaki",
            "Núria",
            "Esperança",
            "Vicenç",
            "Concepción",
            "Ainhoa",
            "Andrés",
            "José",
            "Xoán");

    private static final List<String> APELLIDOS = List.of(
            "Muñoz",
            "Álvarez",
            "Peña",
            "Núñez",
            "Ibáñez",
            "Ordóñez",
            "García",
            "Fernández",
            "Rodríguez",
            "Martínez",
            "Sánchez",
            "Piñero",
            "Yáñez",
            "Alcañiz",
            "Cañizares",
            "Bermúdez",
            "Vicenç",
            "Torre",
            "Córdoba",
            "Berrocal");

    /** Las partículas que hacen que el nombre familiar tenga espacios y no se pueda partir por ellos. */
    private static final List<String> PARTICULAS = List.of("de", "de la", "del", "de los", "i", "y");

    private final Random azar;

    public NombresEspanoles(long semilla) {
        this.azar = new Random(semilla);
    }

    /**
     * Un nombre familiar completo, tal y como viaja en {@code PID-5.1} y se guarda en
     * {@code HumanName.family}: <strong>entero</strong>, con sus partículas y sus dos apellidos.
     */
    public String apellidos() {
        String primero = uno(APELLIDOS);
        String segundo = uno(APELLIDOS);
        return switch (azar.nextInt(5)) {
            case 0 -> primero; // uno solo: hay pacientes con un apellido
            case 1 -> "%s %s".formatted(primero, segundo);
            case 2 -> "%s %s %s".formatted(primero, uno(PARTICULAS), segundo); // Fernández de Córdoba
            case 3 -> "%s %s %s".formatted(uno(PARTICULAS), primero, segundo); // de la Torre Gómez
            default -> "%s %s %s %s".formatted(uno(PARTICULAS), primero, uno(PARTICULAS), segundo);
        };
    }

    /** Uno o dos nombres de pila, ya unidos por el separador de componente de {@code PID-5}. */
    public String nombreDePila() {
        return azar.nextBoolean() ? uno(PILA) : "%s^%s".formatted(uno(PILA), uno(PILA));
    }

    /** El mismo nombre familiar en mayúsculas, que es como lo escribe un HIS español. */
    public String apellidosEnMayusculas() {
        return apellidos()
                .toUpperCase(new java.util.Locale.Builder().setLanguage("es").build());
    }

    private String uno(List<String> de) {
        return de.get(azar.nextInt(de.size()));
    }
}
