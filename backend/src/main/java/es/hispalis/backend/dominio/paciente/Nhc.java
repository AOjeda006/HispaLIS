package es.hispalis.backend.dominio.paciente;

import es.hispalis.backend.dominio.DatoInvalido;
import java.util.regex.Pattern;

/**
 * Número de historia clínica del laboratorio: ocho dígitos.
 *
 * <p>Es el <strong>único identificador con formato validado</strong> de todo el sistema, y lo es
 * precisamente porque es el único que emite el laboratorio (D16, §4.1 del diseño). Validar el
 * formato del DNI, del NUHSA o del CIP-SNS solo produciría falsos rechazos de pacientes reales: no
 * los emitimos nosotros y su estructura cambia por Real Decreto.
 *
 * <p>La Ley 41/2002 obliga a los centros privados no vinculados a la red pública a asignar uno.
 *
 * @param valor los ocho dígitos, con los ceros a la izquierda que hagan falta
 */
public record Nhc(String valor) {

    private static final Pattern OCHO_DIGITOS = Pattern.compile("^[0-9]{8}$");

    public Nhc {
        if (valor == null || !OCHO_DIGITOS.matcher(valor).matches()) {
            throw new DatoInvalido(
                    "El número de historia clínica son exactamente ocho dígitos, y llegó «" + valor + "».");
        }
    }

    @Override
    public String toString() {
        return valor;
    }
}
