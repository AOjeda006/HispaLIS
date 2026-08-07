package es.hispalis.backend.aplicacion.reconciliacion;

import es.hispalis.backend.aplicacion.reconciliacion.Divergencia.Clase;
import java.util.List;

/**
 * Lo que el reconciliador encontró, y si lo arregló.
 *
 * <p>Existe para que <strong>revisar sea un modo de ejecución y no otra ruta de código</strong>: el
 * mismo recorrido produce el mismo informe con {@code aplicado} a falso o a cierto. Si la revisión
 * fuese un método aparte, sería posible que dijera una cosa y la reparación hiciera otra, que es
 * exactamente lo que no puede pasar en una vía de recuperación oficial (§15).
 *
 * @param aplicado si además de mirar se escribió
 * @param divergencias lo encontrado, en el orden del recorrido
 */
public record InformeDeReconciliacion(boolean aplicado, List<Divergencia> divergencias) {

    public InformeDeReconciliacion {
        divergencias = List.copyOf(divergencias);
    }

    public boolean todoCuadra() {
        return divergencias.isEmpty();
    }

    public List<Divergencia> de(Clase clase) {
        return divergencias.stream()
                .filter(divergencia -> divergencia.clase() == clase)
                .toList();
    }
}
