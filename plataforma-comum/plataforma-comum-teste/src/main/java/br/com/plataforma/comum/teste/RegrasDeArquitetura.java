package br.com.plataforma.comum.teste;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * Regras de arquitetura que valem para todo backend do ecossistema, prontas para o ArchUnit. Cada sistema importa o seu
 * código e chama {@code regra.check(codigo)} no seu {@code ArquiteturaTest}; o que é só dele (por exemplo "não é
 * multi-tenant") continua no teste do sistema.
 *
 * <p>As regras citam anotações e tipos do Spring por nome, de modo que este módulo não obriga o sistema a ter no
 * classpath de teste nada que ele não use.
 *
 * <pre>{@code
 * class ArquiteturaTest {
 *     static JavaClasses codigo = new ClassFileImporter()
 *             .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("br.com.app.api");
 *     static final Set<String> SQL_NATIVO = Set.of("ContadorBanco", "BillingService");
 *
 *     @Test void controller() { RegrasDeArquitetura.CONTROLLER_SO_FALA_COM_SERVICE.check(codigo); }
 *     @Test void sql() { RegrasDeArquitetura.sqlNativoSoEm(SQL_NATIVO).check(codigo); }
 *     @Test void lista() { RegrasDeArquitetura.exigirQueAsExcecoesExistem(codigo, "SQL_NATIVO", SQL_NATIVO); }
 * }
 * }</pre>
 */
public final class RegrasDeArquitetura {

    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final Set<String> TRANSACTIONAL = Set.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    private static final String QUERY = "org.springframework.data.jpa.repository.Query";
    private static final String NATIVE_QUERY = "org.springframework.data.jpa.repository.NativeQuery";

    private RegrasDeArquitetura() { }

    /** Controller não toca repositório, {@code EntityManager} nem JDBC: regra de negócio e transação ficam no service. */
    public static final ArchRule CONTROLLER_SO_FALA_COM_SERVICE = noClasses().that().areAnnotatedWith(REST_CONTROLLER)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.EntityManager")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
            .because("regra de negócio e transação ficam no service");

    /** Controller não abre transação, nem na classe nem em método. */
    public static final ArchRule CONTROLLER_NAO_ABRE_TRANSACAO = noClasses().that().areAnnotatedWith(REST_CONTROLLER)
            .should(new ArchCondition<JavaClass>("ter @Transactional na classe ou em método") {
                @Override
                public void check(JavaClass classe, ConditionEvents eventos) {
                    if (TRANSACTIONAL.stream().anyMatch(classe::isAnnotatedWith)) {
                        eventos.add(SimpleConditionEvent.satisfied(classe, classe.getName() + " é @Transactional"));
                    }
                    classe.getMethods().stream().filter(m -> TRANSACTIONAL.stream().anyMatch(m::isAnnotatedWith))
                            .forEach(m -> eventos.add(SimpleConditionEvent.satisfied(m, m.getFullName() + " é @Transactional")));
                }
            })
            .because("a transação é do service");

    /** Nada, fora os próprios controllers, depende de controller. */
    public static final ArchRule NADA_DEPENDE_DE_CONTROLLER = noClasses().that().areNotAnnotatedWith(REST_CONTROLLER)
            .should().dependOnClassesThat().areAnnotatedWith(REST_CONTROLLER)
            .because("service e repositório não conhecem a camada web");

    /** Jackson 3 ({@code tools.jackson.*}); só as anotações ficam em {@code com.fasterxml.jackson.annotation}. */
    public static final ArchRule JACKSON_TRES = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
            .because("o ecossistema usa Jackson 3 (tools.jackson.*); só as anotações continuam em com.fasterxml.jackson.annotation");

    /** Injeção por construtor, log por SLF4J e nada de {@code System.out}. */
    public static final ArchRule SEM_INJECAO_POR_CAMPO = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    public static final ArchRule SEM_JAVA_UTIL_LOGGING = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    public static final ArchRule SEM_SAIDA_PADRAO = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    /**
     * SQL nativo ({@code JdbcTemplate}, {@code JdbcClient}, {@code createNativeQuery} ou
     * {@code @Query(nativeQuery = true)}) só nas classes da lista, por nome simples. A lista é o lugar visível da
     * decisão: entrada nova passa por revisão, com o porquê. Combine com {@link #exigirQueAsExcecoesExistem}.
     */
    public static ArchRule sqlNativoSoEm(Set<String> permitidas) {
        DescribedPredicate<JavaClass> foraDaLista =
                DescribedPredicate.describe("não estão na lista de SQL nativo permitido", (JavaClass c) -> !permitidas.contains(c.getSimpleName()));
        return noClasses().that(foraDaLista)
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                .orShould(chamaConsultaNativa())
                .orShould(declaraQueryNativa())
                .because("SQL nativo só onde a lista de exceções permite, cada uma com o porquê");
    }

    /** Qualquer chamada {@code createNative...} ({@code createNativeQuery} em todas as sobrecargas, e as do Hibernate). */
    private static ArchCondition<JavaClass> chamaConsultaNativa() {
        return new ArchCondition<>("chamar createNative...") {
            @Override
            public void check(JavaClass classe, ConditionEvents eventos) {
                classe.getMethodCallsFromSelf().stream()
                        .filter(chamada -> chamada.getName().startsWith("createNative"))
                        .forEach(chamada -> eventos.add(SimpleConditionEvent.satisfied(chamada, chamada.getDescription())));
            }
        };
    }

    /** {@code @Query(nativeQuery = true)} ou {@code @NativeQuery}, em qualquer método. */
    private static ArchCondition<JavaClass> declaraQueryNativa() {
        return new ArchCondition<>("declarar @Query(nativeQuery = true) ou @NativeQuery") {
            @Override
            public void check(JavaClass classe, ConditionEvents eventos) {
                classe.getMethods().stream()
                        .filter(m -> m.isAnnotatedWith(NATIVE_QUERY) || m.tryGetAnnotationOfType(QUERY)
                                .flatMap(a -> a.tryGetExplicitlyDeclaredProperty("nativeQuery"))
                                .filter(Boolean.TRUE::equals).isPresent())
                        .forEach(m -> eventos.add(SimpleConditionEvent.satisfied(m, m.getFullName() + " usa SQL nativo")));
            }
        };
    }

    /**
     * Falha se a lista de exceções cita uma classe que não existe mais (renomeada ou apagada): lista que apodrece
     * deixa a exceção valendo para outra classe de mesmo nome no futuro.
     */
    public static void exigirQueAsExcecoesExistem(JavaClasses codigo, String nomeDaLista, Set<String> excecoes) {
        Set<String> existentes = codigo.stream().map(JavaClass::getSimpleName).collect(Collectors.toSet());
        Set<String> sobrando = excecoes.stream().filter(nome -> !existentes.contains(nome)).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!sobrando.isEmpty()) {
            throw new AssertionError(nomeDaLista + " cita classes que não existem mais: " + sobrando);
        }
    }
}
