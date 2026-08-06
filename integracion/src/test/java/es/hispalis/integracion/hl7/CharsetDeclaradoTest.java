package es.hispalis.integracion.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.hl7v2.llp.ExtendedMinLowerLayerProtocol;
import ca.uhn.hl7v2.llp.LLPException;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * La lista de juegos de caracteres que este laboratorio acepta, cruzada contra lo que HAPI
 * <strong>de verdad</strong> hace con esos mismos literales.
 *
 * <p>La clase {@code HL7Charsets} de HAPI, que es su tabla 0211, no es pública: no se puede consultar
 * desde aquí. Así que el cruce se hace <strong>por comportamiento</strong>, dando la vuelta completa
 * por su capa MLLP: si nuestro literal y el suyo no significaran lo mismo, el nombre volvería
 * cambiado.
 *
 * <p>No es celo excesivo. Si divergieran, HAPI decodificaría los bytes con un juego y nosotros
 * validaríamos contra otro — el mensaje pasaría el control y llegaría corrupto al laboratorio, que es
 * la peor combinación de las posibles.
 */
class CharsetDeclaradoTest {

    static java.util.stream.Stream<String> losQueAceptamos() {
        return CharsetDeclarado.aceptados().stream();
    }

    @ParameterizedTest(name = "MSH-18 = {0}")
    @MethodSource("losQueAceptamos")
    void lo_que_declaramos_aceptar_es_lo_que_hapi_usa_para_decodificar(String literal) throws Exception {
        // ASCII no puede llevar acentos por definición. Se prueba con un nombre que sí cabe: declarar
        // ASCII y mandar una Ñ es un emisor mal configurado, y de eso va el otro test.
        boolean soloAscii = "ASCII".equals(literal);
        String apellidos = soloAscii ? "MUNOZ DE LA TORRE" : MensajesDePrueba.MUNOZ;
        String nombre = soloAscii ? "Begona^Maria" : "Begoña^María";
        String original = MensajesDePrueba.adt("A01", "MSG1", "70000001", apellidos, nombre, literal);

        String idaYVuelta = porElCable(original);

        assertThat(idaYVuelta).isEqualTo(original);
    }

    @Test
    void sin_msh_18_se_asume_latin_1_y_no_utf_8() {
        CharsetDeclarado sinDeclarar = CharsetDeclarado.de("");

        assertThat(sinDeclarar.juego()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(sinDeclarar.literal())
                .as("no se inventa un valor: el almacén tiene que poder decir que no venía")
                .isEmpty();
    }

    /**
     * La red que caza al emisor que declara una cosa y manda otra.
     *
     * <p>{@code U+FFFD} no aparece en ningún mensaje legítimo: lo pone el decodificador de Java
     * cuando los bytes no son del juego con el que está leyendo.
     */
    @Test
    void un_mensaje_con_caracteres_de_reemplazo_no_es_el_mensaje_que_mandaron() {
        CharsetDeclarado utf8 = CharsetDeclarado.de("UNICODE UTF-8");

        assertThatThrownBy(() -> utf8.exigirQueLoLeidoCuadre("PID|1||...||MU\uFFFDOZ^Bego\uFFFDa"))
                .isInstanceOf(CharsetDeclarado.CharsetNoCuadra.class)
                .hasMessageContaining("UNICODE UTF-8");
    }

    @Test
    void un_mensaje_bien_decodificado_pasa_la_comprobacion() {
        CharsetDeclarado latin1 = CharsetDeclarado.de("8859/1");

        latin1.exigirQueLoLeidoCuadre("PID|1||...||" + MensajesDePrueba.MUNOZ + "^Begoña");
    }

    @Test
    void un_juego_que_no_esta_en_la_lista_se_rechaza_y_dice_cuales_valen() {
        assertThatThrownBy(() -> CharsetDeclarado.de("8859/8"))
                .isInstanceOf(CharsetDeclarado.CharsetNoSoportado.class)
                .hasMessageContaining("8859/8")
                .hasMessageContaining("8859/1");
    }

    /** Escribe el mensaje con la capa MLLP de HAPI y lo vuelve a leer con ella. */
    private static String porElCable(String mensaje) throws IOException, LLPException {
        ExtendedMinLowerLayerProtocol llp = new ExtendedMinLowerLayerProtocol();
        ByteArrayOutputStream cable = new ByteArrayOutputStream();
        llp.getWriter(cable).writeMessage(mensaje);
        return llp.getReader(new ByteArrayInputStream(cable.toByteArray())).getMessage();
    }
}
