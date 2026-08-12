package es.hispalis.backend.fhir.exportacion;

import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import java.util.Locale;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Group;
import org.springframework.stereotype.Component;

/**
 * La cohorte de vigilancia, publicada como {@code Group}.
 *
 * <p><strong>El id se deriva de la enfermedad y no se sortea.</strong> {@code Group/cohorte-legionelosis}
 * es estable, legible y —lo que importa— <em>calculable</em>: el notificador puede meter a alguien en
 * la cohorte sin haber leído antes qué id le tocó, y dos declaraciones simultáneas de la misma
 * enfermedad acaban en el mismo recurso en vez de en dos cohortes gemelas.
 *
 * <p>⚠️ <strong>R5 no es R4:</strong> el booleano {@code Group.actual} ya no existe y lo sustituye
 * {@code Group.membership}. Un {@code Group} construido copiando código de R4 no compila aquí, que es
 * la forma buena de fallar; lo que sí se cuela es un <em>ejemplo</em> de R4 en la guía, y ese no
 * valida. Y {@code Group.description} pasó de {@code string} a {@code markdown}.
 */
@Component
public class TraductorDeCohorte {

    /** El {@code system} con el que se busca una cohorte sin saberse su id: {@code ?identifier=…}. */
    public static final String SISTEMA_DE_COHORTES = "https://aojeda006.github.io/HispaLIS/sid/cohorte-vigilancia";

    private static final String ENFERMEDADES = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/enfermedades-edo";

    private static final String RASGOS = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/rasgos-de-cohorte";

    /** El id del {@code Group} de una enfermedad. Calculable, para que no haya que consultarlo. */
    public static String idDeLaCohorte(String codigoDeEnfermedad) {
        return "cohorte-" + codigoDeEnfermedad.toLowerCase(Locale.ROOT);
    }

    /**
     * La cohorte vacía de una enfermedad. Los miembros los añade quien declara.
     *
     * @param codigoDeEnfermedad el código de {@code CodeSystem/enfermedades-edo}
     * @param nombreDeLaEnfermedad su nombre, para que el {@code Group} se lea sin resolver el código
     */
    public Group nueva(String codigoDeEnfermedad, String nombreDeLaEnfermedad) {
        Group cohorte = new Group();
        cohorte.setId(idDeLaCohorte(codigoDeEnfermedad));
        cohorte.getMeta().addProfile(PerfilesDeLaGuia.COHORTE_VIGILANCIA.canonica());

        cohorte.addIdentifier().setSystem(SISTEMA_DE_COHORTES).setValue(codigoDeEnfermedad);
        cohorte.setActive(true);
        cohorte.setType(Group.GroupType.PERSON);
        // ⚠️ R5: `membership`, no `actual`. `enumerated` porque el laboratorio sabe exactamente a quién
        // ha declarado; `definitional` dejaría el criterio a interpretación de quien lo resuelva.
        cohorte.setMembership(Group.GroupMembershipBasis.ENUMERATED);
        cohorte.setName("Casos declarados de " + nombreDeLaEnfermedad.toLowerCase(Locale.ROOT));
        cohorte.setDescription("Pacientes con al menos un resultado validado por el que se ha abierto declaración "
                + "obligatoria de " + nombreDeLaEnfermedad.toLowerCase(Locale.ROOT) + ".");

        // EL RASGO: por qué se pertenece. Sin él, la cohorte es una lista de personas de la que nadie
        // puede deducir el criterio — y una lista así no se audita.
        Group.GroupCharacteristicComponent rasgo = cohorte.addCharacteristic();
        rasgo.setCode(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem(RASGOS)
                        .setCode("enfermedad-declarada")
                        .setDisplay("Enfermedad declarada a Salud Pública")));
        rasgo.setValue(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem(ENFERMEDADES)
                        .setCode(codigoDeEnfermedad)
                        .setDisplay(nombreDeLaEnfermedad)));
        rasgo.setExclude(false);

        return cohorte;
    }
}
