package es.hispalis.backend.dominio.edo;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Una declaración de enfermedad obligatoria: la obligación legal convertida en algo que se sigue.
 *
 * <h2>El invariante: sin acuse no hay declaración</h2>
 *
 * <p>Es la razón de que esto sea un agregado y no un par de columnas. El estado {@link
 * EstadoDeDeclaracion#ACUSADA} <strong>solo se alcanza por {@link #acusar}</strong>, y {@code acusar}
 * exige un número de registro que no está en blanco. No hay ningún camino que lleve a «declarado»
 * pasando por «el envío no dio error»: la diferencia entre las dos cosas es lo único que se puede
 * enseñar el día que alguien pregunte por qué un brote se detectó tarde, y una comprobación en el
 * caso de uso se la saltaría la segunda puerta de entrada que apareciese.
 *
 * <h2>El plazo se congela al abrirla</h2>
 *
 * <p>El vencimiento se calcula una vez, cuando nace la obligación, y se guarda. No se recalcula al
 * enviar. Si mañana el catálogo cambia el plazo de la legionelosis, las declaraciones ya abiertas
 * conservan la ventana que tenían — que es lo correcto: el plazo que se incumplió es el que estaba
 * vigente ese día, no el de hoy. Es la misma decisión que las firmas exigidas de un resultado crítico.
 *
 * <h2>Aquí tampoco hay filiación</h2>
 *
 * <p>El agregado guarda el identificador interno del paciente y nada más. Ni nombre, ni NHC, ni
 * NUHSA. Quien monte el mensaje que sale hacia Salud Pública no tiene de dónde sacarlos.
 */
public final class NotificacionEdo {

    private final UUID id;
    private final UUID resultadoId;
    private final UUID pacienteId;
    private final String declarante;
    private final String codigoDeEnfermedad;
    private final String nombreDeLaEnfermedad;
    private final ModalidadDeDeclaracion modalidad;
    private final Instant abiertaEn;
    private final Instant vencimiento;
    private final EstadoDeDeclaracion estado;
    private final int intentos;
    private final String ultimoError;
    private final Acuse acuse;

    /**
     * El recibo de Salud Pública.
     *
     * @param sistema de quién es el número; el laboratorio no lo emite y no puede fingirlo
     * @param numero el registro con el que la administración identifica la declaración
     * @param recibidoEn cuándo llegó
     */
    public record Acuse(String sistema, String numero, Instant recibidoEn) {

        public Acuse {
            if (numero == null || numero.isBlank()) {
                throw new DatoInvalido(
                        "Un acuse sin número de registro no acredita nada: es un mensaje que salió, no una "
                                + "declaración recibida.");
            }
            if (recibidoEn == null) {
                recibidoEn = Instant.now();
            }
        }
    }

    private NotificacionEdo(
            UUID id,
            UUID resultadoId,
            UUID pacienteId,
            String declarante,
            String codigoDeEnfermedad,
            String nombreDeLaEnfermedad,
            ModalidadDeDeclaracion modalidad,
            Instant abiertaEn,
            Instant vencimiento,
            EstadoDeDeclaracion estado,
            int intentos,
            String ultimoError,
            Acuse acuse) {
        this.id = id;
        this.resultadoId = resultadoId;
        this.pacienteId = pacienteId;
        this.declarante = declarante;
        this.codigoDeEnfermedad = codigoDeEnfermedad;
        this.nombreDeLaEnfermedad = nombreDeLaEnfermedad;
        this.modalidad = modalidad;
        this.abiertaEn = abiertaEn;
        this.vencimiento = vencimiento;
        this.estado = estado;
        this.intentos = intentos;
        this.ultimoError = ultimoError;
        this.acuse = acuse;
    }

    /**
     * Abre la declaración de un resultado que ya obliga a declarar.
     *
     * @param resultadoId el resultado validado que la motiva
     * @param pacienteId de quién es el caso, por identificador interno
     * @param declarante el centro que declara, como referencia; el laboratorio que emitió el resultado
     * @param regla qué se declara, con qué criterio y en cuánto tiempo
     * @param validadoEn cuándo nació la obligación, que es cuando se firmó el resultado
     * @throws DatoInvalido si falta el resultado, el paciente o el momento de la validación
     */
    public static NotificacionEdo abrir(
            UUID resultadoId, UUID pacienteId, String declarante, ReglaDeDeclaracion regla, Instant validadoEn) {
        if (resultadoId == null || pacienteId == null) {
            throw new DatoInvalido("Una declaración sin resultado y sin caso detrás no se puede seguir ni auditar.");
        }
        if (regla == null || validadoEn == null) {
            throw new DatoInvalido(
                    "Una declaración necesita la regla que la obliga y el momento en que nació la obligación: "
                            + "sin lo segundo no hay plazo que contar.");
        }
        return new NotificacionEdo(
                UUID.randomUUID(),
                resultadoId,
                pacienteId,
                declarante,
                regla.codigoDeEnfermedad(),
                regla.nombreDeLaEnfermedad(),
                regla.modalidad(),
                validadoEn,
                regla.venceDesde(validadoEn),
                EstadoDeDeclaracion.PENDIENTE,
                0,
                null,
                null);
    }

    /** Reconstruye una que ya estaba guardada. Solo la usa el repositorio. */
    public static NotificacionEdo rehidratar(
            UUID id,
            UUID resultadoId,
            UUID pacienteId,
            String declarante,
            String codigoDeEnfermedad,
            String nombreDeLaEnfermedad,
            ModalidadDeDeclaracion modalidad,
            Instant abiertaEn,
            Instant vencimiento,
            EstadoDeDeclaracion estado,
            int intentos,
            String ultimoError,
            Acuse acuse) {
        return new NotificacionEdo(
                id,
                resultadoId,
                pacienteId,
                declarante,
                codigoDeEnfermedad,
                nombreDeLaEnfermedad,
                modalidad,
                abiertaEn,
                vencimiento,
                estado,
                intentos,
                ultimoError,
                acuse);
    }

    /**
     * El destinatario la ha recibido y no ha devuelto número de registro.
     *
     * <p>Avanza a {@link EstadoDeDeclaracion#ENVIADA}, que es «mandado sí, declarado no». Es el estado
     * que más fácil se confunde con el éxito, porque a nivel de transporte todo ha ido bien.
     */
    public NotificacionEdo marcarEnviadaSinAcuse(String motivo) {
        exigirQueSigaAbierta();
        return copiaCon(EstadoDeDeclaracion.ENVIADA, intentos + 1, motivo, null);
    }

    /**
     * Cierra la declaración con el acuse de Salud Pública. <strong>Es el único camino a
     * {@code ACUSADA}.</strong>
     */
    public NotificacionEdo acusar(Acuse recibo) {
        exigirQueSigaAbierta();
        if (recibo == null) {
            throw new ReglaDeNegocioIncumplida(
                    ("La declaración de %s no se puede dar por hecha sin acuse. Un envío que no da error es un "
                                    + "mensaje que salió; declarado está cuando Salud Pública devuelve su número "
                                    + "de registro.")
                            .formatted(codigoDeEnfermedad));
        }
        return copiaCon(EstadoDeDeclaracion.ACUSADA, intentos + 1, null, recibo);
    }

    /** Salud Pública contesta que no la admite. No se reintenta sola: se resuelve a mano. */
    public NotificacionEdo rechazar(String motivo) {
        exigirQueSigaAbierta();
        return copiaCon(EstadoDeDeclaracion.RECHAZADA, intentos + 1, motivo, null);
    }

    /** El intento no llegó a su destino. Sigue {@code PENDIENTE}: el destinatario no la tiene. */
    public NotificacionEdo anotarIntentoFallido(String motivo) {
        exigirQueSigaAbierta();
        return copiaCon(EstadoDeDeclaracion.PENDIENTE, intentos + 1, motivo, null);
    }

    /**
     * Si la ventana legal se ha agotado sin que la declaración esté hecha.
     *
     * <p>Se calcula y no se guarda: un estado «vencida» en la base de datos sería verdad solo hasta el
     * segundo siguiente y habría que ir refrescándolo con un proceso. Lo que se guarda es la fecha
     * límite, que no cambia.
     */
    public boolean estaFueraDePlazo(Instant ahora) {
        return estado != EstadoDeDeclaracion.ACUSADA && ahora.isAfter(vencimiento);
    }

    /** Si el notificador todavía tiene algo que hacer con ella. */
    public boolean sigueAbierta() {
        return !estado.esFinal();
    }

    private void exigirQueSigaAbierta() {
        if (estado.esFinal()) {
            throw new ReglaDeNegocioIncumplida(
                    ("La declaración de %s ya está %s: volver a tocarla taparía lo que se registró en su "
                                    + "momento, y el registro de qué se dijo y cuándo es justamente lo que hay que "
                                    + "conservar. Una rectificación es otra declaración.")
                            .formatted(codigoDeEnfermedad, estado.name().toLowerCase()));
        }
    }

    private NotificacionEdo copiaCon(EstadoDeDeclaracion nuevo, int intentos, String error, Acuse recibo) {
        return new NotificacionEdo(
                id,
                resultadoId,
                pacienteId,
                declarante,
                codigoDeEnfermedad,
                nombreDeLaEnfermedad,
                modalidad,
                abiertaEn,
                vencimiento,
                nuevo,
                intentos,
                error,
                recibo);
    }

    public UUID id() {
        return id;
    }

    public UUID resultadoId() {
        return resultadoId;
    }

    public UUID pacienteId() {
        return pacienteId;
    }

    /**
     * El centro que declara.
     *
     * <p>Se guarda en el agregado y no se vuelve a leer del resultado en cada paso: quién declaró es
     * parte de lo que hay que poder enseñar años después, y el {@code performer} de un
     * {@code Observation} se puede corregir.
     */
    public Optional<String> declarante() {
        return Optional.ofNullable(declarante);
    }

    public String codigoDeEnfermedad() {
        return codigoDeEnfermedad;
    }

    public String nombreDeLaEnfermedad() {
        return nombreDeLaEnfermedad;
    }

    public ModalidadDeDeclaracion modalidad() {
        return modalidad;
    }

    public Instant abiertaEn() {
        return abiertaEn;
    }

    public Instant vencimiento() {
        return vencimiento;
    }

    public EstadoDeDeclaracion estado() {
        return estado;
    }

    public int intentos() {
        return intentos;
    }

    public Optional<String> ultimoError() {
        return Optional.ofNullable(ultimoError);
    }

    public Optional<Acuse> acuse() {
        return Optional.ofNullable(acuse);
    }
}
