package es.hispalis.integracion.reproceso;

import es.hispalis.integracion.almacen.AlmacenDeMensajes;
import es.hispalis.integracion.almacen.MensajeArchivado;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La consola del motor: ver la bandeja de errores y reprocesar.
 *
 * <p>Es <strong>una operación del motor, no un script suelto</strong>, y esa distinción es D11 hecha
 * código: lo que se pierde al no usar Mirth es justamente la consola desde la que un operador ve qué
 * falló y lo vuelve a lanzar. Un {@code UPDATE} a mano en la base de datos no es eso; no deja rastro,
 * no cuenta intentos y no pasa por el mismo enrutado.
 *
 * <h2>Qué NO devuelve</h2>
 *
 * <p>El listado <strong>no incluye el mensaje original</strong>. Un mensaje v2 es un volcado clínico
 * completo, y una consola de operación no es sitio para eso: lo que se ve es qué falló, de quién
 * venía y por qué. El original está en el archivo, que es donde tiene que estar para poder auditarlo.
 * El NHC sí aparece —sin él no se puede saber a quién afecta un error—, y por eso va
 * <strong>en el cuerpo de la respuesta y nunca en la URL</strong> (ADR-0016).
 *
 * <h2>Deuda conocida: esto todavía no pide credenciales</h2>
 *
 * <p>Esta consola se sirve dentro de la red del {@code compose} y <strong>no se publica hacia
 * fuera</strong>; el punto de enganche del token es el mismo {@code AutenticacionDelMotor} que ya
 * usa el cliente FHIR. Está anotado en la memoria técnica (§12.3 y §13.3), que es donde vive el
 * trabajo pendiente.
 */
@RestController
@RequestMapping("/motor/dlq")
public class ConsolaDelMotor {

    /** Un tope por defecto: la bandeja de un motor con un emisor caído tiene miles de filas. */
    private static final int LIMITE_POR_DEFECTO = 50;

    private final AlmacenDeMensajes almacen;
    private final Reproceso reproceso;

    public ConsolaDelMotor(AlmacenDeMensajes almacen, Reproceso reproceso) {
        this.almacen = almacen;
        this.reproceso = reproceso;
    }

    /** Lo que entró y no se pudo aplicar, lo más reciente primero. */
    @GetMapping
    public List<EntradaDeLaBandeja> bandeja(@RequestParam(defaultValue = "" + LIMITE_POR_DEFECTO) int limite) {
        return almacen.bandejaDeErrores(limite).stream()
                .map(EntradaDeLaBandeja::de)
                .toList();
    }

    /** Vuelve a aplicar el mensaje entero. Reprocesar uno ya aplicado no duplica nada. */
    @PostMapping("/{id}/reproceso")
    public ResponseEntity<Reproceso.Resultado> reprocesar(@PathVariable UUID id) {
        try {
            Reproceso.Resultado resultado = reproceso.reaplicar(id);
            // `200` tanto si se aplicó como si no: la operación de reproceso se ejecutó, y lo que
            // pasó con el mensaje lo dice el cuerpo. Un `4xx` diría que la orden estaba mal formada.
            return ResponseEntity.ok(resultado);
        } catch (Reproceso.MensajeDesconocido noEsta) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Una fila de la bandeja, sin el original.
     *
     * @param id lo que hay que pasarle al reproceso
     * @param emisor {@code MSH-3} y {@code MSH-4}, que es de dónde vino
     * @param controlId {@code MSH-10}
     * @param mensaje {@code ADT^A01}, {@code OML^O21}…
     * @param nhc a quién afecta
     * @param recibidoEn cuándo entró
     * @param intentos cuántas veces se ha probado
     * @param motivo por qué no se aplicó
     */
    public record EntradaDeLaBandeja(
            UUID id,
            String emisor,
            String controlId,
            String mensaje,
            String nhc,
            Instant recibidoEn,
            int intentos,
            String motivo) {

        static EntradaDeLaBandeja de(MensajeArchivado archivado) {
            return new EntradaDeLaBandeja(
                    archivado.id(),
                    "%s / %s".formatted(archivado.aplicacionEmisora(), archivado.instalacionEmisora()),
                    archivado.controlId(),
                    archivado.tipoYEvento(),
                    archivado.nhc(),
                    archivado.recibidoEn(),
                    archivado.intentos(),
                    archivado.detalle());
        }
    }
}
