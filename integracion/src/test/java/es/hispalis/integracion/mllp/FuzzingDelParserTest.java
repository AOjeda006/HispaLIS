package es.hispalis.integracion.mllp;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.EmisorCrudoMllp;
import es.hispalis.integracion.arnes.GeneradorHostil;
import es.hispalis.integracion.arnes.GeneradorHostil.CasoHostil;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Entrada hostil por el camino real: socket, TLS y sobre MLLP escrito a mano.
 *
 * <p>El motor abre un puerto y parsea lo que llegue. Es <strong>la superficie más expuesta del
 * sistema</strong> y la única que interpreta un formato de cable ajeno, y hasta aquí todos sus tests
 * eran por ejemplo, con mensajes bien formados escritos por quien escribió el parser. Eso prueba que
 * el camino bueno funciona; no prueba nada del malo.
 *
 * <h2>El criterio no es «no se cae»</h2>
 *
 * <p>Son cuatro cosas, y las cuatro se comprueban en cada caso:
 *
 * <ol>
 *   <li><strong>Se contesta.</strong> Si el sobre MLLP está cerrado, hay acuse — {@code AA},
 *       {@code AE} o {@code AR}, pero acuse. Un emisor v2 sin respuesta o reintenta para siempre o da
 *       el mensaje por entregado, y las dos cosas son peores que un rechazo. Con el sobre sin cerrar
 *       no se exige nada: el servidor tiene derecho a seguir esperando bytes.
 *   <li><strong>No se filtra el interior.</strong> Ni traza de pila, ni nombres de clase, ni SQL. Lo
 *       que sale por el acuse lo lee un sistema ajeno.
 *   <li><strong>No se filtra filiación.</strong> Cada caso sabe qué literales de paciente lleva
 *       dentro, y se buscan en el acuse decodificado <em>con los dos juegos</em>.
 *   <li><strong>El canal sigue vivo.</strong> Después de <em>cada</em> entrada hostil se manda un
 *       mensaje bueno y se exige {@code AA}. Es la comprobación que de verdad importa: un fallo que
 *       mate el hilo del servidor deja el canal mudo para el siguiente, y el siguiente es un
 *       resultado de laboratorio de otra persona.
 * </ol>
 *
 * <h2>La semilla</h2>
 *
 * <p>Fija y <strong>impresa</strong> al arrancar. Se puede cambiar con
 * {@code -Dhispalis.fuzzing.semilla=…} para una tanda más larga, pero la de por defecto es la que
 * corre en la CI: un fuzzer con semilla de reloj encuentra un fallo el martes y el miércoles ya nadie
 * sabe con qué entrada.
 */
class FuzzingDelParserTest extends TestDelMotor {

    /** La semilla de la CI. Cambiarla es cambiar la tanda, así que se cambia a propósito. */
    private static final long SEMILLA = Long.getLong("hispalis.fuzzing.semilla", 20_260_815L);

    /** Quince por familia. Sube con {@code -Dhispalis.fuzzing.casos=…} para una tanda larga. */
    private static final int CASOS = Integer.getInteger("hispalis.fuzzing.casos", 105);

    /**
     * Lo que se espera por un acuse antes de dar la conexión por muda.
     *
     * <p>Tres segundos y no treinta: el listener está en el mismo proceso y contesta en milésimas, así
     * que un plazo largo no gana fiabilidad — solo multiplica por los casos que legítimamente no
     * contestan, que son los del sobre sin cerrar, y convierte la tanda en diez minutos de espera.
     */
    private static final int PLAZO_MS = 3_000;

    /**
     * Lo que un acuse no puede contener jamás.
     *
     * <p>No es una lista de palabras feas: es la lista de las cosas que solo pueden venir de haber
     * volcado una excepción de Java en la respuesta. Un emisor v2 que reciba esto sabe la versión de
     * PostgreSQL del laboratorio.
     */
    private static final List<String> RASTROS_INTERNOS = List.of(
            "\tat ",
            "java.lang.",
            "java.sql.",
            "org.postgresql",
            "org.springframework",
            "ca.uhn.",
            "Caused by",
            // Los cuatro de abajo se añadieron el 2026-08-15, después de encontrar la fuga: un byte
            // nulo en el texto hacía que PostgreSQL rechazase el `INSERT` del archivo y la sentencia
            // entera acababa dentro del `ERR` del acuse. La lista de antes no la cazaba.
            "INSERT INTO",
            "SQL [",
            "SQLException",
            "StatementCallback");

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(80_000_000);

    @BeforeAll
    static void decirConQueSemillaSeCorre() {
        System.out.printf("Fuzzing del parser v2: semilla %d, %d casos.%n", SEMILLA, CASOS);
    }

    static List<CasoHostil> laTanda() {
        return new GeneradorHostil(SEMILLA).generar(CASOS);
    }

    /**
     * La tanda entera, caso a caso.
     *
     * <p>Un solo test por caso y no un bucle dentro de uno: con el bucle, el primer rojo esconde los
     * ciento cuatro restantes y el nombre del fallo no dice qué se estaba mandando.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("laTanda")
    void una_entrada_hostil_se_acusa_sin_filtrar_nada_y_deja_el_canal_vivo(CasoHostil caso) {
        EmisorCrudoMllp.Respuesta respuesta = mandar(caso.bytes());

        if (caso.sobreCerrado()) {
            assertThat(respuesta.cierre())
                    .as(
                            "%s: sobre MLLP cerrado y el motor ni contesta ni cierra — el emisor se queda "
                                    + "colgado sin saber si se procesó. Se mandó: %s",
                            caso.nombre(), asomarse(caso.bytes()))
                    .isNotEqualTo(EmisorCrudoMllp.Cierre.SILENCIO);
        }
        respuesta.acuse().ifPresent(acuse -> {
            String enLosDosJuegos = EmisorCrudoMllp.enCualquierJuego(acuse);
            assertThat(enLosDosJuegos)
                    .as("%s: el acuse lleva dentro el interior del laboratorio", caso.nombre())
                    .doesNotContain(RASTROS_INTERNOS);
            caso.phi().forEach(dato -> assertThat(enLosDosJuegos)
                    .as("%s: filiación devuelta en el acuse (%s)", caso.nombre(), dato)
                    .doesNotContain(dato));
            String texto = EmisorCrudoMllp.comoTexto(acuse, StandardCharsets.ISO_8859_1);
            assertThat(codigoDeAcuse(texto))
                    .as("%s: acuse completo: %s", caso.nombre(), texto)
                    .isIn("AA", "AE", "AR");
        });

        assertThat(elCanalSigueAceptandoUnMensajeBueno())
                .as("el canal quedó mudo después de %s", caso.nombre())
                .isEqualTo("AA");
    }

    /**
     * La ráfaga por una sola conexión.
     *
     * <p>Es un escenario distinto del anterior y no una repetición: un HIS de verdad abre la conexión
     * al arrancar y manda por ella todo el día. Si una entrada hostil rompe el bucle de lectura de
     * <em>esa</em> conexión sin matar el servidor, el test de arriba no lo ve —abre una conexión nueva
     * cada vez— y en producción el HIS se queda sin poder entregar hasta que alguien lo reinicie.
     */
    @Test
    void una_rafaga_hostil_por_la_misma_conexion_no_la_deja_muda() {
        GeneradorHostil generador = new GeneradorHostil(SEMILLA + 1);
        List<CasoHostil> rafaga = generador.generar(21).stream()
                // Los que rompen el sobre a propósito quedan fuera: por una conexión compartida
                // desincronizan el flujo, y eso es cierto también con un emisor bueno. Esa familia se
                // prueba arriba, cada caso con su conexión, que es como llega en la realidad.
                .filter(CasoHostil::sobreCerrado)
                .filter(caso -> !caso.nombre().startsWith("SOBRE_MLLP"))
                .toList();

        String nhc = siguienteNhc();
        try (EmisorCrudoMllp emisor = EmisorCrudoMllp.conectar("127.0.0.1", puertoDelListener(), PLAZO_MS)) {
            rafaga.forEach(caso -> emisor.enviar(caso.bytes()));

            Optional<byte[]> acuse = emisor.enviar(sobreDe(MensajesDePrueba.adt(
                            "A01", "TRAS" + nhc, nhc, MensajesDePrueba.MUNOZ, "Rocío^Ana", "8859/1")))
                    .acuse();

            assertThat(acuse)
                    .as("tras %d entradas hostiles por la misma conexión, no contesta al mensaje bueno", rafaga.size())
                    .isPresent();
            assertThat(codigoDeAcuse(EmisorCrudoMllp.comoTexto(acuse.orElseThrow(), StandardCharsets.ISO_8859_1)))
                    .isEqualTo("AA");
        }
    }

    /**
     * La peor entrada que se sabe escribir, y detrás un mensaje bueno.
     *
     * <p>La tanda generada ya comprueba esto después de cada caso, pero esta lista no es aleatoria:
     * son las ocho cosas que <em>a mano</em> se consideran lo peor que se puede meter por un socket
     * MLLP, escritas literales para que se lean. Si algún día una de ellas tumba el listener, el rojo
     * lo dirá con nombre y sin depender de que la semilla la genere.
     */
    @Test
    void despues_de_la_peor_entrada_el_canal_sigue_aceptando_un_mensaje_bueno() {
        byte[] bueno = MensajesDePrueba.adt(
                        "A01", "PEOR1", "79000001", MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1")
                .getBytes(StandardCharsets.ISO_8859_1);

        List<byte[]> loPeor = List.of(
                // Nada: sobre vacío.
                new byte[] {EmisorCrudoMllp.INICIO, EmisorCrudoMllp.FIN, EmisorCrudoMllp.RETORNO},
                // Solo el cierre, sin haber abierto.
                new byte[] {EmisorCrudoMllp.FIN, EmisorCrudoMllp.RETORNO},
                // Un `MSH` que se queda en el nombre del segmento.
                sobreDe("MSH"),
                // Los delimitadores a medias: el parser no sabe ni por dónde cortar.
                sobreDe("MSH|^~"),
                // Bytes nulos incrustados: PostgreSQL no los admite en una columna de texto.
                conNulos(bueno),
                // Cuarenta mil caracteres en `MSH-10`, que es parte de la clave única del archivo.
                sobreDe(MensajesDePrueba.adt(
                        "A01", "X".repeat(40_000), "79000002", MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1")),
                // Un sobre que se abre y no se cierra: el servidor se queda esperando.
                abrirYNoCerrar(bueno),
                // Un principio de bloque a mitad del texto: el flujo queda desincronizado y lo que el
                // lector entrega al parser es medio mensaje.
                conInicioIncrustado(bueno));

        loPeor.forEach(FuzzingDelParserTest::mandar);

        assertThat(elCanalSigueAceptandoUnMensajeBueno())
                .as("el canal quedó mudo después de la peor entrada")
                .isEqualTo("AA");
    }

    // ── Regresiones: cada entrada que rompió algo se queda aquí con nombre propio ─────────────────

    /**
     * Regresión del 2026-08-15: un {@code MSH} ilegible se quedaba <strong>sin acuse ninguno</strong>.
     *
     * <p>Para componer el acuse, HAPI necesita leer el {@code MSH} del mensaje entrante; cuando no
     * puede, llama al manejador de excepciones con el acuse a nulo y {@link AcusePorFalloInterno} lo
     * devolvía tal cual. HAPI entonces lanza <em>«Application exception handler may not return
     * null»</em> y <strong>el emisor no recibe nada</strong>: exactamente lo que la regla «siempre se
     * responde» existe para impedir, y en el caso peor —un {@code MSH} truncado o unos delimitadores a
     * medias, que es lo que llega cuando se cae una conexión—. Cuarenta y cinco de las ciento cinco
     * entradas generadas caían por aquí.
     *
     * <p>Las tres cabeceras de abajo son literales a propósito: son las que hay que poder repetir
     * dentro de diez meses sin depender de que la semilla las vuelva a generar. Son las tres que HAPI
     * no consigue leer <em>en absoluto</em>, así que el acuse lo compone {@link AcusePorFalloInterno} y
     * es {@code AR}. Una cabecera que HAPI sí sepa leer —por ejemplo una a la que solo le falte
     * {@code MSH-9}— nunca llega a este camino: HAPI compone su propio acuse de error, con el código
     * que él elija, y eso está bien porque acuse hay.
     */
    @ParameterizedTest(name = "«{0}»")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MSH|", "MSH|^~", "MSH|^~\\&|HIS_VIRGEN"})
    void un_msh_ilegible_se_acusa_con_un_rechazo_y_no_con_silencio(String cabecera) {
        EmisorCrudoMllp.Respuesta respuesta = mandar(sobreDe(cabecera));

        assertThat(respuesta.acuse())
                .as("«%s»: sin acuse, el emisor v2 reintenta para siempre o da el mensaje por entregado", cabecera)
                .isPresent();
        String texto = EmisorCrudoMllp.comoTexto(respuesta.acuse().orElseThrow(), StandardCharsets.ISO_8859_1);
        assertThat(codigoDeAcuse(texto)).as("acuse completo: %s", texto).isEqualTo("AR");
        assertThat(texto)
                .as("ni traza de pila ni nombres de clase en lo que sale por el cable")
                .doesNotContain(RASTROS_INTERNOS);
        assertThat(texto)
                .as("un acuse sin emisor no le sirve de nada a quien lo recibe")
                .contains("HISPALIS");
    }

    /**
     * Regresión del 2026-08-15: un byte nulo dentro del texto sacaba la sentencia {@code SQL} por el
     * cable.
     *
     * <p>Un {@code 0x00} viaja perfectamente por MLLP y PostgreSQL <strong>no lo admite</strong> en una
     * columna de texto, así que el {@code INSERT} del archivo revienta. Ese fallo ocurría
     * <em>antes</em> del despachador —el archivo es lo único que queda fuera de su red— y se escapaba
     * hasta HAPI, que compone el acuse metiendo dentro el mensaje de la excepción: el {@code ERR} del
     * acuse salía con la sentencia {@code INSERT INTO integracion.mensaje …} entera y el error del
     * motor de base de datos. El HIS del hospital recibía el esquema del laboratorio por el puerto
     * MLLP.
     *
     * <p>Ahora se acusa {@code AE} con una frase fija y el detalle técnico se queda en el archivo,
     * que es donde lo busca quien diagnostica.
     */
    @Test
    void un_byte_nulo_en_el_texto_no_saca_la_sentencia_sql_por_el_cable() {
        // Un solo byte nulo, y dentro del nombre de pila: el mensaje sigue siendo v2 perfectamente
        // parseable, así que llega hasta el archivo — que es donde está el fallo. Sembrarlo de nulos a
        // voleo rompería el `MSH` y el mensaje moriría antes, sin tocar la base de datos: parecería
        // que el camino está probado y no lo estaría.
        String bueno =
                MensajesDePrueba.adt("A01", "NULO0001", "79000010", MensajesDePrueba.MUNOZ, "Bego\0ña^María", "8859/1");

        EmisorCrudoMllp.Respuesta respuesta = mandar(sobreDe(bueno));

        assertThat(respuesta.acuse())
                .as("un mensaje que el archivo no admite tiene que acusarse igual")
                .isPresent();
        String texto = EmisorCrudoMllp.comoTexto(respuesta.acuse().orElseThrow(), StandardCharsets.ISO_8859_1);
        assertThat(texto)
                .as("el acuse llevaba dentro el esquema del laboratorio: %s", texto)
                .doesNotContain(RASTROS_INTERNOS)
                .doesNotContain("integracion.mensaje");
        assertThat(codigoDeAcuse(texto))
                .as("es un fallo del laboratorio, no del mensaje: AE, que lo mire una persona. Acuse: %s", texto)
                .isEqualTo("AE");
        assertThat(texto).as("y se le dice al emisor qué hacer, que es nada").contains("no hace falta reenviarlo");
    }

    /**
     * Lo que <strong>no</strong> se arregla, dicho aquí para que no se descubra tarde.
     *
     * <p>Hay <strong>una</strong> entrada que ni se acusa ni puede acusarse, y muere en el lector MLLP
     * de HAPI, antes de que exista mensaje al que responder: un cuerpo de menos de cuatro bytes. El
     * lector busca el {@code MSH-18} en los bytes crudos —necesita saber con qué juego decodificar
     * antes de convertirlos a texto— y se sale del array, con lo que no llega a haber mensaje entrante
     * ni, por tanto, manejador de excepciones al que llamar.
     *
     * <p><strong>Se acepta, y por un motivo concreto:</strong> el motor <em>cierra la conexión</em>. El
     * emisor se entera en el acto de que la entrega ha fallado, no da el mensaje por entregado y vuelve
     * a conectar. Eso no es el silencio que la regla prohíbe —el silencio malo es la conexión abierta y
     * muda, donde el emisor se queda esperando—. Arreglarlo exigiría envolver el lector MLLP de HAPI, y
     * el <em>framing</em> de MLLP es justo lo que este proyecto no escribe a mano: su documento
     * normativo está retirado desde mayo de 2025.
     *
     * <p>El {@code 0x0B} incrustado a mitad del texto estuvo en esta lista mientras se escribía la
     * tanda, y ya no: desde que {@link AcusePorFalloInterno} compone el rechazo de último recurso, esa
     * entrada <strong>sí</strong> se acusa. Está ahora entre lo peor que se manda a mano, más arriba.
     */
    @Test
    void un_cuerpo_demasiado_corto_cierra_la_conexion_en_vez_de_quedarse_mudo() {
        assertThat(mandar(sobreDe("MSH")).cierre())
                .as("la conexión se quedó abierta y muda, que es el final que no vale")
                .isEqualTo(EmisorCrudoMllp.Cierre.CONEXION_CERRADA);

        assertThat(elCanalSigueAceptandoUnMensajeBueno())
                .as("y el listener sigue atendiendo a quien vuelva a conectar")
                .isEqualTo("AA");
    }

    // ── Utilidades ───────────────────────────────────────────────────────────────────────────────

    /**
     * Los primeros bytes de lo que se mandó, legibles.
     *
     * <p>Sin esto, un rojo dice «no hubo acuse» y no dice de qué. Los bytes de control salen con su
     * nombre y los no imprimibles en hexadecimal, que es lo que hace falta para reproducirlo a mano.
     */
    private static String asomarse(byte[] bytes) {
        StringBuilder legible = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 160); i++) {
            int octeto = bytes[i] & 0xFF;
            legible.append(
                    switch (octeto) {
                        case 0x0B -> "<VT>";
                        case 0x1C -> "<FS>";
                        case 0x0D -> "<CR>";
                        case 0x00 -> "<NUL>";
                        default ->
                            octeto >= 0x20 && octeto < 0x7F
                                    ? String.valueOf((char) octeto)
                                    : "<%02X>".formatted(octeto);
                    });
        }
        return legible + (bytes.length > 160 ? "… (%d bytes)".formatted(bytes.length) : "");
    }

    private static EmisorCrudoMllp.Respuesta mandar(byte[] bytes) {
        try (EmisorCrudoMllp emisor = EmisorCrudoMllp.conectar("127.0.0.1", puertoDelListener(), PLAZO_MS)) {
            return emisor.enviar(bytes);
        }
    }

    /** Manda un {@code ADT} bien formado por una conexión nueva y devuelve su {@code MSA-1}. */
    private static String elCanalSigueAceptandoUnMensajeBueno() {
        String nhc = siguienteNhc();
        String bueno = MensajesDePrueba.adt("A01", "VIVO" + nhc, nhc, MensajesDePrueba.PENA, "Rocío^Ana", "8859/1");
        return mandar(sobreDe(bueno))
                .acuse()
                .map(acuse -> EmisorCrudoMllp.comoTexto(acuse, StandardCharsets.ISO_8859_1))
                .map(FuzzingDelParserTest::codigoDeAcuse)
                .orElse("(sin acuse)");
    }

    private static byte[] sobreDe(String mensaje) {
        byte[] cuerpo = mensaje.getBytes(StandardCharsets.ISO_8859_1);
        byte[] sobre = new byte[cuerpo.length + 3];
        sobre[0] = EmisorCrudoMllp.INICIO;
        System.arraycopy(cuerpo, 0, sobre, 1, cuerpo.length);
        sobre[sobre.length - 2] = EmisorCrudoMllp.FIN;
        sobre[sobre.length - 1] = EmisorCrudoMllp.RETORNO;
        return sobre;
    }

    /** El mismo cuerpo con un byte nulo cada diez octetos. */
    private static byte[] conNulos(byte[] cuerpo) {
        java.io.ByteArrayOutputStream sobre = new java.io.ByteArrayOutputStream();
        sobre.write(EmisorCrudoMllp.INICIO);
        for (int i = 0; i < cuerpo.length; i++) {
            sobre.write(cuerpo[i]);
            if (i % 10 == 0) {
                sobre.write(0x00);
            }
        }
        sobre.write(EmisorCrudoMllp.FIN);
        sobre.write(EmisorCrudoMllp.RETORNO);
        return sobre.toByteArray();
    }

    /** Un principio de bloque incrustado a mitad del texto: el flujo queda desincronizado. */
    private static byte[] conInicioIncrustado(byte[] cuerpo) {
        java.io.ByteArrayOutputStream sobre = new java.io.ByteArrayOutputStream();
        sobre.write(EmisorCrudoMllp.INICIO);
        sobre.write(cuerpo, 0, cuerpo.length / 2);
        sobre.write(EmisorCrudoMllp.INICIO);
        sobre.write(cuerpo, cuerpo.length / 2, cuerpo.length - cuerpo.length / 2);
        sobre.write(EmisorCrudoMllp.FIN);
        sobre.write(EmisorCrudoMllp.RETORNO);
        return sobre.toByteArray();
    }

    /** El sobre se abre y nunca se cierra: el servidor se queda esperando el resto. */
    private static byte[] abrirYNoCerrar(byte[] cuerpo) {
        byte[] sobre = new byte[cuerpo.length + 1];
        sobre[0] = EmisorCrudoMllp.INICIO;
        System.arraycopy(cuerpo, 0, sobre, 1, cuerpo.length);
        return sobre;
    }

    /**
     * {@code MSA-1}, leído con <strong>el separador de campo del propio acuse</strong>.
     *
     * <p>No es rebuscado: cuando el mensaje entrante redefine {@code MSH-1}, el acuse que HAPI compone
     * sale con <em>ese</em> separador, así que un lector que dé por hecha la barra vertical lee
     * {@code MSA!AE!…} y concluye que no hay {@code MSA}. Fue el segundo error del arnés, y de los que
     * hay que mirar con cuidado: un fuzzer que se equivoca al leer la respuesta inventa fallos que no
     * existen y, lo que es peor, tapa los que sí.
     */
    private static String codigoDeAcuse(String acuse) {
        char separador = acuse.startsWith("MSH") && acuse.length() > 3 ? acuse.charAt(3) : '|';
        String prefijoMsa = "MSA" + separador;
        return List.of(acuse.split("\r")).stream()
                .filter(segmento -> segmento.startsWith(prefijoMsa))
                .map(segmento -> segmento.split(java.util.regex.Pattern.quote(String.valueOf(separador)), -1)[1])
                .findFirst()
                .orElse("(sin MSA)");
    }

    private static String siguienteNhc() {
        return String.valueOf(SIGUIENTE.incrementAndGet());
    }
}
