package es.hispalis.integracion.hl7;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.llp.ExtendedMinLowerLayerProtocol;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * Cómo se configura HAPI en este motor, en un solo sitio.
 *
 * <p>Hay tres piezas que necesitan un contexto —el listener, el emisor hacia el HIS y el reproceso,
 * que vuelve a parsear lo archivado— y las tres tienen que configurarlo <strong>igual</strong>. Si el
 * reproceso parsease con el modelo por defecto en vez de con el canónico de V2.5.1, un mensaje que
 * entró bien podría reprocesarse como otra cosa, y eso es peor que no poder reprocesarlo.
 *
 * <p>Cada llamada devuelve un contexto <strong>nuevo</strong>: el del listener acaba llevando su
 * fábrica de sockets con el almacén de claves del servidor, y el del emisor la suya con el almacén de
 * confianza del cliente. Compartir uno haría que el certificado de servidor del motor se usara para
 * salir, que es un enredo que solo se descubre mirando un volcado de red.
 */
public final class ContextosHl7 {

    /** La versión que fija D12. */
    public static final String VERSION = "2.5.1";

    private ContextosHl7() {
        // Utilidad.
    }

    /**
     * Un contexto configurado como este motor habla v2.
     *
     * <ul>
     *   <li><strong>Modelo canónico de V2.5.1:</strong> un mensaje que declare otra versión se parsea
     *       contra las estructuras de esta y las diferencias saltan, en vez de pasar calladas.
     *   <li><strong>{@link ExtendedMinLowerLayerProtocol}:</strong> lee {@code MSH-18} antes de
     *       convertir los bytes a texto. Con el LLP mínimo, {@code MUÑOZ} llegaría como {@code MU?OZ}
     *       sin una sola excepción.
     *   <li><strong>Sin validación de HAPI:</strong> quien decide si un mensaje es aceptable es el
     *       canal, con un motivo que se le pueda contar al operador del HIS. La validación por defecto
     *       rechaza con errores de biblioteca que nadie sabe traducir a una acción.
     * </ul>
     */
    public static HapiContext nuevo() {
        DefaultHapiContext contexto = new DefaultHapiContext();
        contexto.setModelClassFactory(new CanonicalModelClassFactory(VERSION));
        contexto.setLowerLayerProtocol(new ExtendedMinLowerLayerProtocol());
        contexto.setValidationContext(ValidationContextFactory.noValidation());
        return contexto;
    }

    /**
     * Igual, pero con el parser en <strong>modo no voraz</strong>. Lo necesita el {@code OML^O21}.
     *
     * <h2>La gramática del OML es ambigua, y el daño es del que no se ve</h2>
     *
     * <p>Un grupo {@code ORDER} contiene un {@code OBSERVATION_REQUEST}, y ese a su vez contiene un
     * {@code PRIOR_RESULT} con su propio {@code ORDER_PRIOR}, que empieza por… {@code ORC}. Cuando
     * llega el segundo {@code ORC} de un volante con dos pruebas hay dos sitios válidos donde puede
     * ir: un {@code ORDER} nuevo al nivel de arriba, o un resultado previo anidado dentro del
     * anterior. HAPI, por defecto, elige el anidado.
     *
     * <p>Y no falla. El mensaje se parsea sin una sola queja, se reserializa <strong>idéntico</strong>
     * al original, y {@code getORDERReps()} devuelve <strong>1</strong> en vez de 2. Un volante de
     * glucosa y creatinina entra en el laboratorio como un volante de glucosa, y la creatinina no la
     * reclama nadie porque nadie sabe que se pidió.
     *
     * <h2>Por qué no se pone en todos los contextos</h2>
     *
     * <p>Porque rompe el {@code ORU^R01}. Ahí {@code ORC} es opcional dentro de
     * {@code ORDER_OBSERVATION}, y en modo no voraz el parser se queda en {@code PATIENT_RESULT} y
     * falla con <em>«ORC does not exist in the group ORU_R01_PATIENT_RESULT»</em>. No hay un ajuste
     * bueno para las dos gramáticas: <strong>el modo de parseo es una propiedad del mensaje, no del
     * motor</strong>, así que lo elige cada canal.
     */
    public static HapiContext noVoraz() {
        HapiContext contexto = nuevo();
        contexto.getParserConfiguration().setNonGreedyMode(true);
        return contexto;
    }
}
