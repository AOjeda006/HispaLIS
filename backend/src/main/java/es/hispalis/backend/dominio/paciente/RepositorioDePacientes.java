package es.hispalis.backend.dominio.paciente;

import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de salida del agregado {@link Paciente}.
 *
 * <p>Vive en el dominio, y su implementación en infraestructura: es la regla de dependencia de
 * Clean Architecture aplicada a la ubicación física del código. El núcleo declara qué necesita
 * —guardar un paciente, buscarlo por NHC— sin saber si detrás hay PostgreSQL, un fichero o nada.
 */
public interface RepositorioDePacientes {

    /**
     * Guarda un paciente nuevo.
     *
     * @param paciente el agregado, ya válido
     * @throws es.hispalis.backend.dominio.ConflictoDeNegocio si su NHC ya está emitido
     */
    void guardar(Paciente paciente);

    /** Guarda la filiación corregida de un paciente que ya existe. */
    void actualizar(Paciente paciente);

    /** Busca un paciente por el número de historia clínica, que es único en el laboratorio. */
    Optional<Paciente> buscarPorNhc(Nhc nhc);

    /** Recupera un paciente por su identidad. */
    Optional<Paciente> buscarPorId(UUID id);
}
