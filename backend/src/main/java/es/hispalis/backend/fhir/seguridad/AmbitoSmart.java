package es.hispalis.backend.fhir.seguridad;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Un <em>scope</em> de SMART on FHIR v2, ya interpretado: {@code {patient|user|system}/{Tipo|*}.{cruds}}.
 *
 * <p>Existe como tipo y no como cadena porque la diferencia entre {@code user/Observation.rs} y
 * {@code user/Observation.cud} es la diferencia entre leer y escribir, y eso no se decide comparando
 * texto en el sitio donde hace falta la respuesta.
 *
 * <p><strong>Lo que no se entiende, no concede nada.</strong> Es la regla que gobierna todo este
 * fichero. La norma permite ignorar, sustituir o rechazar un sufijo desordenado o inventado, y de las
 * tres la única segura es no conceder: si {@code .dus} se interpretara «con buena voluntad» como
 * {@code .dsu}, un cliente que pidió actualizar habría conseguido borrar. Por eso {@link #de(String)}
 * devuelve vacío en vez de aproximar.
 *
 * @param contexto en nombre de quién se actúa: un paciente, un usuario o un sistema sin persona
 * @param tipoDeRecurso el tipo FHIR al que aplica, o {@code *} para todos
 * @param permisos qué se puede hacer, ya desplegado
 */
public record AmbitoSmart(Contexto contexto, String tipoDeRecurso, Set<Permiso> permisos) {

    /** Los tres ámbitos de SMART. No se mezclan: cada uno responde a una pregunta distinta. */
    public enum Contexto {
        /** Los datos del paciente que viene en el contexto de lanzamiento. */
        PACIENTE("patient"),
        /** Todo lo que el usuario identificado puede ver, que no es «lo suyo». */
        USUARIO("user"),
        /** Sin usuario: el cliente está preautorizado (SMART Backend Services). */
        SISTEMA("system");

        private final String prefijo;

        Contexto(String prefijo) {
            this.prefijo = prefijo;
        }

        static Optional<Contexto> delPrefijo(String prefijo) {
            for (Contexto contexto : values()) {
                if (contexto.prefijo.equals(prefijo)) {
                    return Optional.of(contexto);
                }
            }
            return Optional.empty();
        }
    }

    /** Los cinco permisos de la sintaxis v2, en el orden en el que la norma exige escribirlos. */
    public enum Permiso {
        CREAR,
        LEER,
        ACTUALIZAR,
        BORRAR,
        BUSCAR
    }

    /** El comodín de tipo de recurso. Es sobre el TIPO, nunca sobre la persona. */
    public static final String TODOS_LOS_TIPOS = "*";

    /** El orden obligatorio de los sufijos. Cualquier otro orden es un scope que no se concede. */
    private static final String ORDEN_DE_LOS_SUFIJOS = "cruds";

    /**
     * Interpreta un <em>scope</em>. Devuelve vacío si no es un permiso o si no se entiende.
     *
     * <p>Los <em>scopes</em> que no son permisos ({@code openid}, {@code fhirUser}, {@code launch},
     * {@code profile}…) devuelven vacío sin más: son legítimos y no conceden acceso a datos.
     */
    public static Optional<AmbitoSmart> de(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String scope = texto.trim();

        // Los scopes granulares por parámetro de búsqueda (`patient/Observation.rs?category=…`) son
        // experimentales y este servidor NO los implementa. Ignorar el `?` sería lo peor que se
        // puede hacer con ellos: el cliente pidió acceso ACOTADO y se le concedería el ancho.
        if (scope.indexOf('?') >= 0) {
            return Optional.empty();
        }

        int barra = scope.indexOf('/');
        int punto = scope.lastIndexOf('.');
        if (barra <= 0 || punto <= barra + 1) {
            return Optional.empty();
        }

        Optional<Contexto> contexto = Contexto.delPrefijo(scope.substring(0, barra));
        if (contexto.isEmpty()) {
            return Optional.empty();
        }

        String tipo = scope.substring(barra + 1, punto);
        if (!esTipoAceptable(tipo)) {
            return Optional.empty();
        }

        return permisosDe(scope.substring(punto + 1)).map(permisos -> new AmbitoSmart(contexto.get(), tipo, permisos));
    }

    /** ¿Cubre este ámbito la acción pedida sobre ese tipo de recurso? */
    public boolean alcanza(String tipo, Permiso permiso) {
        return permisos.contains(permiso) && (TODOS_LOS_TIPOS.equals(tipoDeRecurso) || tipoDeRecurso.equals(tipo));
    }

    public boolean todosLosTipos() {
        return TODOS_LOS_TIPOS.equals(tipoDeRecurso);
    }

    /**
     * De los sufijos al conjunto de permisos.
     *
     * <p>Acepta también las tres formas de la v1 ({@code .read}, {@code .write}, {@code .*}) con la
     * equivalencia que fija la propia norma. No es cortesía con lo viejo: mientras el servidor declare
     * la capacidad {@code permission-v1} tiene que entenderlas, y si dejara de entenderlas habría que
     * dejar de declararla en {@code .well-known/smart-configuration} el mismo día.
     */
    private static Optional<Set<Permiso>> permisosDe(String sufijos) {
        return switch (sufijos.toLowerCase(Locale.ROOT)) {
            case "read" -> Optional.of(EnumSet.of(Permiso.LEER, Permiso.BUSCAR));
            case "write" -> Optional.of(EnumSet.of(Permiso.CREAR, Permiso.ACTUALIZAR, Permiso.BORRAR));
            case "*" -> Optional.of(EnumSet.allOf(Permiso.class));
            default -> permisosDeLaV2(sufijos);
        };
    }

    private static Optional<Set<Permiso>> permisosDeLaV2(String sufijos) {
        Set<Permiso> permisos = EnumSet.noneOf(Permiso.class);
        int esperadoDesde = 0;
        for (char sufijo : sufijos.toCharArray()) {
            int posicion = ORDEN_DE_LOS_SUFIJOS.indexOf(sufijo);
            // `< esperadoDesde` cubre a la vez el sufijo desordenado y el repetido, y `< 0` el
            // inventado. Los tres significan lo mismo aquí: no se concede nada.
            if (posicion < esperadoDesde) {
                return Optional.empty();
            }
            permisos.add(Permiso.values()[posicion]);
            esperadoDesde = posicion + 1;
        }
        return permisos.isEmpty() ? Optional.empty() : Optional.of(permisos);
    }

    /**
     * Un tipo de recurso FHIR empieza por mayúscula; el comodín es el único que no.
     *
     * <p>No se comprueba contra la lista de tipos de R5 a propósito: un scope sobre un tipo que este
     * servidor no publica no concede nada igualmente, y mantener aquí una segunda lista de tipos
     * sería la lista paralela de siempre, esta vez en el peor sitio.
     */
    private static boolean esTipoAceptable(String tipo) {
        return TODOS_LOS_TIPOS.equals(tipo) || (!tipo.isEmpty() && Character.isUpperCase(tipo.charAt(0)));
    }
}
