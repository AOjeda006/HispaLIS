package es.hispalis.integracion.arnes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Genera entrada <strong>hostil</strong> para el listener MLLP, con semilla fija.
 *
 * <p>El generador es propio y no de librería a propósito. Lo que hay que producir aquí no son valores
 * de un tipo —para eso sobra cualquier <em>property-based</em>— sino <strong>bytes de cable
 * deliberadamente rotos</strong>: sobres MLLP a medias, delimitadores que se redefinen a sí mismos y
 * secuencias que no son texto válido en el juego que el propio mensaje declara. Eso se escribe a mano
 * o no se escribe.
 *
 * <p><strong>La semilla se fija y se imprime.</strong> Un fuzzer cuyo contraejemplo no se puede
 * repetir no sirve de nada: encuentra el fallo una vez y luego nadie sabe con qué. Cada caso lleva
 * además su nombre —familia y número de orden— para que el mensaje de un rojo diga qué se estaba
 * mandando.
 *
 * <p>Las siete familias son las siete formas en las que se rompe una tubería v2 de verdad. No son
 * ruido aleatorio: el ruido aleatorio puro casi nunca llega al parser, porque muere en el sobre.
 */
public final class GeneradorHostil {

    /** Las siete formas de romper la entrada. */
    public enum Familia {
        /** El mensaje se corta a mitad de segmento, como cuando se cae la conexión. */
        TRUNCADO,
        /** El {@code MSH} no llega a estar completo: sin delimitadores, sin tipo, sin versión. */
        MSH_INCOMPLETO,
        /** Segmentos repetidos, en orden imposible, o que no existen. */
        SEGMENTOS_IMPOSIBLES,
        /** Los delimitadores se redefinen, se repiten o se dejan a medias. */
        DELIMITADORES,
        /** Un campo con miles de caracteres. */
        CAMPOS_ENORMES,
        /** Bytes que no son texto válido en el juego que declara {@code MSH-18}. */
        CHARSET_MENTIROSO,
        /** Los bytes de sobre de MLLP puestos donde no van. */
        SOBRE_MLLP
    }

    /**
     * Un caso listo para mandar por el hilo.
     *
     * @param nombre familia y orden, para que el rojo diga qué se mandó
     * @param bytes lo que viaja, <strong>sobre incluido</strong>: aquí no se añade nada después
     * @param phi los literales de filiación que el caso lleva dentro y que el acuse no puede repetir
     */
    public record CasoHostil(String nombre, byte[] bytes, Set<String> phi) {

        /**
         * Si por el hilo ha viajado un sobre MLLP <strong>completo</strong>: un {@code 0x0B} y, por
         * detrás, un {@code 0x1C 0x0D}.
         *
         * <p>Se calcula sobre los bytes en vez de anotarse a mano al construir el caso, y la
         * diferencia no es de estilo: es lo que separa «el motor no ha contestado» de «el motor no
         * tiene nada que contestar todavía». Sin sobre cerrado, el servidor está esperando el resto
         * del mensaje y callar es lo correcto; anotarlo a mano fue el primer error del arnés y
         * convirtió tres formas legítimas de silencio en tres rojos.
         */
        public boolean sobreCerrado() {
            int inicio = indiceDe(bytes, EmisorCrudoMllp.INICIO, 0);
            if (inicio < 0) {
                return false;
            }
            for (int i = inicio + 1; i < bytes.length - 1; i++) {
                if (bytes[i] == EmisorCrudoMllp.FIN && bytes[i + 1] == EmisorCrudoMllp.RETORNO) {
                    return true;
                }
            }
            return false;
        }

        private static int indiceDe(byte[] donde, byte que, int desde) {
            for (int i = desde; i < donde.length; i++) {
                if (donde[i] == que) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String toString() {
            return "%s (%d bytes)".formatted(nombre, bytes.length);
        }
    }

    /** Los literales de filiación del mensaje base. Ninguno puede volver dentro de un acuse. */
    private static final List<String> FILIACION =
            List.of(MensajesDePrueba.MUNOZ, "Begoña", "María", "12345678Z", "AN0123456789", "19810314");

    private final Random azar;

    public GeneradorHostil(long semilla) {
        this.azar = new Random(semilla);
    }

    /**
     * Produce los casos, repartidos a partes iguales entre las siete familias.
     *
     * <p>El reparto es por turno y no al azar: con reparto aleatorio, una semilla desafortunada deja
     * una familia entera sin representar y el fuzzer pasa en verde sin haber tocado el sobre MLLP.
     */
    public List<CasoHostil> generar(int cuantos) {
        List<CasoHostil> casos = new ArrayList<>(cuantos);
        Familia[] familias = Familia.values();
        for (int i = 0; i < cuantos; i++) {
            Familia familia = familias[i % familias.length];
            casos.add(unoDe(familia, i));
        }
        return casos;
    }

    /** Un caso de la familia indicada. Público para poder pedir uno suelto desde un test. */
    public CasoHostil unoDe(Familia familia, int orden) {
        return switch (familia) {
            case TRUNCADO -> truncado(orden);
            case MSH_INCOMPLETO -> mshIncompleto(orden);
            case SEGMENTOS_IMPOSIBLES -> segmentosImposibles(orden);
            case DELIMITADORES -> delimitadores(orden);
            case CAMPOS_ENORMES -> camposEnormes(orden);
            case CHARSET_MENTIROSO -> charsetMentiroso(orden);
            case SOBRE_MLLP -> sobreMllp(orden);
        };
    }

    // ── Las siete familias ───────────────────────────────────────────────────────────────────────

    private CasoHostil truncado(int orden) {
        String base = baseAlAzar(orden);
        int corte = 1 + azar.nextInt(base.length() - 1);
        return enSobre("TRUNCADO#" + orden, base.substring(0, corte), StandardCharsets.ISO_8859_1);
    }

    private CasoHostil mshIncompleto(int orden) {
        List<String> cabeceras = List.of(
                "MSH",
                "MSH|",
                "MSH|^~\\&",
                "MSH|^~\\&|",
                "MSH|^~\\&|HIS_VIRGEN",
                "MSH|^~\\&|HIS_VIRGEN|H_VIRGEN_MACARENA|HISPALIS|LAB_SEVILLA|20260806120000",
                // Sin `MSH-9`: no hay tipo de mensaje al que enrutar.
                "MSH|^~\\&|HIS_VIRGEN|H_VIRGEN_MACARENA|HISPALIS|LAB_SEVILLA|20260806120000|||MSG1|P|2.5.1",
                // Sin `MSH-12`: no hay versión que comparar con la que este motor habla.
                "MSH|^~\\&|HIS_VIRGEN|H_VIRGEN_MACARENA|HISPALIS|LAB_SEVILLA|20260806120000||ADT^A01|MSG1|P",
                // `MSH-9` presente pero vacío por dentro.
                "MSH|^~\\&|HIS_VIRGEN|H_VIRGEN_MACARENA|HISPALIS|LAB_SEVILLA|20260806120000||^^|MSG1|P|2.5.1",
                // Un `MSH` con los campos corridos una posición: el clásico de contar barras a mano.
                "MSH|^~\\&||HIS_VIRGEN|H_VIRGEN_MACARENA|HISPALIS|LAB_SEVILLA|20260806120000|ADT^A01|MSG1|P|2.5.1");
        String cabecera = cabeceras.get(azar.nextInt(cabeceras.size()));
        boolean conCuerpo = azar.nextBoolean();
        String texto = conCuerpo ? cabecera + "\r" + "PID|1|||||||F" : cabecera;
        return enSobre("MSH_INCOMPLETO#" + orden, texto, StandardCharsets.ISO_8859_1);
    }

    private CasoHostil segmentosImposibles(int orden) {
        List<String> segmentos = new ArrayList<>(List.of(baseAlAzar(orden).split("\r")));
        switch (azar.nextInt(6)) {
            case 0 -> segmentos.add(1, segmentos.get(0)); // dos MSH seguidos
            case 1 -> java.util.Collections.reverse(segmentos); // el MSH al final
            case 2 -> segmentos.remove(0); // sin MSH
            case 3 -> {
                String pid = segmentos.stream()
                        .filter(s -> s.startsWith("PID"))
                        .findFirst()
                        .orElse("PID|1");
                for (int i = 0; i < 200; i++) {
                    segmentos.add(pid); // doscientos PID: un paciente no tiene doscientas filiaciones
                }
            }
            case 4 -> segmentos.add(1 + azar.nextInt(segmentos.size() - 1), "ZZZ|no|existe|este|segmento");
            default -> segmentos.add(1 + azar.nextInt(segmentos.size() - 1), ""); // segmento vacío
        }
        return enSobre("SEGMENTOS_IMPOSIBLES#" + orden, String.join("\r", segmentos), StandardCharsets.ISO_8859_1);
    }

    private CasoHostil delimitadores(int orden) {
        String base = baseAlAzar(orden);
        List<String> segmentos = new ArrayList<>(List.of(base.split("\r")));
        String msh = segmentos.get(0);

        String texto =
                switch (azar.nextInt(7)) {
                    // El separador de campo pasa a ser `!` en el mensaje entero: legal según la norma.
                    case 0 -> base.replace('|', '!').replace("MSH!", "MSH!");
                    // ...y aquí solo se cambia en `MSH-1`, dejando el resto con `|`: ilegal y frecuente.
                    case 1 ->
                        "MSH!" + msh.substring(4) + "\r" + String.join("\r", segmentos.subList(1, segmentos.size()));
                    // `MSH-2` con caracteres repetidos: el mismo carácter como componente y como escape.
                    case 2 ->
                        msh.replace("^~\\&", "^^^^") + "\r" + String.join("\r", segmentos.subList(1, segmentos.size()));
                    // `MSH-2` a medias: dos caracteres en vez de cuatro.
                    case 3 ->
                        msh.replace("^~\\&", "^~") + "\r" + String.join("\r", segmentos.subList(1, segmentos.size()));
                    // `MSH-2` vacío.
                    case 4 ->
                        msh.replace("^~\\&", "") + "\r" + String.join("\r", segmentos.subList(1, segmentos.size()));
                    // El separador de campo es un dígito: aparece dentro de los propios datos.
                    case 5 -> base.replace('|', '0');
                    // `MSH-2` con caracteres de control.
                    default ->
                        msh.replace("^~\\&", "") + "\r" + String.join("\r", segmentos.subList(1, segmentos.size()));
                };
        return enSobre("DELIMITADORES#" + orden, texto, StandardCharsets.ISO_8859_1);
    }

    private CasoHostil camposEnormes(int orden) {
        int largo = 3_000 + azar.nextInt(40_000);
        String relleno = "X".repeat(largo);
        String texto =
                switch (azar.nextInt(4)) {
                    // `MSH-10` enorme: es parte de la clave única del archivo, no un campo cualquiera.
                    case 0 ->
                        MensajesDePrueba.adt(
                                "A01", relleno, "70000001", MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1");
                    // El apellido enorme.
                    case 1 -> MensajesDePrueba.adt("A01", "MSG" + orden, "70000002", relleno, "Begoña^María", "8859/1");
                    // El NHC enorme: es el índice del archivo.
                    case 2 ->
                        MensajesDePrueba.adt(
                                "A01", "MSG" + orden, relleno, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1");
                    // Un segmento entero de relleno detrás de un mensaje bueno.
                    default ->
                        MensajesDePrueba.adt(
                                        "A01",
                                        "MSG" + orden,
                                        "70000003",
                                        MensajesDePrueba.MUNOZ,
                                        "Begoña^María",
                                        "8859/1")
                                + "\rNTE|1||" + relleno;
                };
        return enSobre("CAMPOS_ENORMES#" + orden, texto, StandardCharsets.ISO_8859_1);
    }

    private CasoHostil charsetMentiroso(int orden) {
        String declarado =
                switch (azar.nextInt(4)) {
                    case 0 -> "UNICODE UTF-8";
                    case 1 -> "ASCII";
                    case 2 -> "8859/1";
                    default -> "KOI8-R"; // ni siquiera está en la lista corta que este laboratorio lee
                };
        String base = MensajesDePrueba.adt(
                "A01",
                "MSG" + orden,
                "7100" + String.format("%04d", orden),
                MensajesDePrueba.MUNOZ,
                "Begoña^María",
                declarado);

        byte[] texto = base.getBytes(StandardCharsets.ISO_8859_1);
        // Se ensucian entre uno y treinta bytes del cuerpo con octetos altos sueltos, que no forman
        // secuencia válida en UTF-8 y no son imprimibles en ASCII.
        int cuantos = 1 + azar.nextInt(30);
        for (int i = 0; i < cuantos; i++) {
            int donde = base.indexOf("PID") + azar.nextInt(Math.max(1, texto.length - base.indexOf("PID")));
            texto[Math.min(donde, texto.length - 1)] = (byte) (0x80 + azar.nextInt(0x40));
        }
        return enSobre("CHARSET_MENTIROSO#" + orden, texto, phiDe(base));
    }

    private CasoHostil sobreMllp(int orden) {
        byte[] cuerpo = baseAlAzar(orden).getBytes(StandardCharsets.ISO_8859_1);
        Set<String> phi = phiDe(new String(cuerpo, StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream sobre = new ByteArrayOutputStream();

        switch (azar.nextInt(8)) {
            case 0 -> { // sin principio de bloque
                sobre.writeBytes(cuerpo);
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
            }
            case 1 -> { // sin final de bloque: el servidor tiene derecho a seguir esperando
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.writeBytes(cuerpo);
            }
            case 2 -> { // dos principios seguidos
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.writeBytes(cuerpo);
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
            }
            case 3 -> { // final duplicado
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.writeBytes(cuerpo);
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
            }
            case 4 -> { // un principio de bloque incrustado a mitad del texto
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.writeBytes(java.util.Arrays.copyOfRange(cuerpo, 0, cuerpo.length / 2));
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.writeBytes(java.util.Arrays.copyOfRange(cuerpo, cuerpo.length / 2, cuerpo.length));
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
            }
            case 5 -> { // solo el final, sin mensaje ninguno
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
                phi = Set.of();
            }
            case 6 -> { // bytes nulos dentro del texto
                sobre.write(EmisorCrudoMllp.INICIO);
                for (int i = 0; i < cuerpo.length; i++) {
                    sobre.write(cuerpo[i]);
                    if (azar.nextInt(50) == 0) {
                        sobre.write(0x00);
                    }
                }
                sobre.write(EmisorCrudoMllp.FIN);
                sobre.write(EmisorCrudoMllp.RETORNO);
            }
            default -> { // final sin retorno de carro
                sobre.write(EmisorCrudoMllp.INICIO);
                sobre.writeBytes(cuerpo);
                sobre.write(EmisorCrudoMllp.FIN);
            }
        }
        return new CasoHostil("SOBRE_MLLP#" + orden, sobre.toByteArray(), phi);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────────────────────────

    /** Un mensaje bien formado de los tres tipos que el motor atiende, para romperlo después. */
    private String baseAlAzar(int orden) {
        String control = "FUZZ%06d".formatted(orden);
        String nhc = "72%06d".formatted(orden);
        return switch (azar.nextInt(3)) {
            case 0 -> MensajesDePrueba.adt("A01", control, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1");
            case 1 -> MensajesDePrueba.oml(control, nhc, "P-2026-000001", "26-0000001", "119364003", "GLU");
            default -> MensajesDePrueba.oru(control, nhc, "26-0000001", "99HISPALIS", "GLU|NM|92|mg/dL");
        };
    }

    private static CasoHostil enSobre(String nombre, String texto, Charset juego) {
        return enSobre(nombre, texto.getBytes(juego), phiDe(texto));
    }

    private static CasoHostil enSobre(String nombre, byte[] cuerpo, Set<String> phi) {
        ByteArrayOutputStream sobre = new ByteArrayOutputStream();
        sobre.write(EmisorCrudoMllp.INICIO);
        sobre.writeBytes(cuerpo);
        sobre.write(EmisorCrudoMllp.FIN);
        sobre.write(EmisorCrudoMllp.RETORNO);
        return new CasoHostil(nombre, sobre.toByteArray(), phi);
    }

    /** Qué literales de filiación han sobrevivido a la mutación y, por tanto, hay que vigilar. */
    private static Set<String> phiDe(String texto) {
        return FILIACION.stream().filter(texto::contains).collect(Collectors.toUnmodifiableSet());
    }
}
