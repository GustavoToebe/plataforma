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
 *     // Nome completo: uma classe de mesmo nome em outro pacote não herda a exceção.
 *     static final Set<String> SQL_NATIVO = Set.of("br.com.app.api.auth.ContadorBanco", "br.com.app.api.comercial.BillingService");
 *
 *     @Test void controller() { RegrasDeArquitetura.CONTROLLER_SO_FALA_COM_SERVICE.check(codigo); }
 *     @Test void sql() { RegrasDeArquitetura.sqlNativoSoEm(SQL_NATIVO).check(codigo); }
 *     @Test void lista() { RegrasDeArquitetura.exigirQueAsExcecoesExistem(codigo, "SQL_NATIVO", SQL_NATIVO); }
 * }
 * }</pre>
 */
public final class RegrasDeArquitetura {

    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String REPOSITORIO_SPRING_DATA = "org.springframework.data.repository.Repository";
    /** Acesso direto ao banco: JDBC do Spring (classes e interfaces) e JDBC cru. */
    private static final Set<String> JDBC = Set.of(
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.springframework.jdbc.core.simple.SimpleJdbcInsert",
            "org.springframework.jdbc.core.simple.SimpleJdbcCall",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            "java.sql.Connection",
            "java.sql.Statement",
            "java.sql.PreparedStatement");
    private static final DescribedPredicate<JavaClass> E_JDBC =
            DescribedPredicate.describe("são de acesso direto ao banco (JDBC)", (JavaClass c) -> JDBC.contains(c.getName()));
    private static final DescribedPredicate<JavaClass> E_CONTROLLER = DescribedPredicate.describe("são controllers",
            (JavaClass c) -> c.isAnnotatedWith(REST_CONTROLLER) || c.isAnnotatedWith(CONTROLLER));
    private static final DescribedPredicate<JavaClass> E_REPOSITORIO = DescribedPredicate.describe(
            "são repositórios (nome em Repository ou Repositorio, ou do Spring Data)",
            (JavaClass c) -> c.getSimpleName().endsWith("Repository") || c.getSimpleName().endsWith("Repositorio")
                    || c.isAssignableTo(REPOSITORIO_SPRING_DATA));
    private static final Set<String> TRANSACTIONAL = Set.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    private static final String QUERY = "org.springframework.data.jpa.repository.Query";
    private static final String NATIVE_QUERY = "org.springframework.data.jpa.repository.NativeQuery";

    private RegrasDeArquitetura() { }

    /**
     * Controller ({@code @RestController} ou {@code @Controller}) não toca repositório (nome em {@code Repository} ou
     * {@code Repositorio}, ou do Spring Data), {@code EntityManager} nem JDBC: regra de negócio e transação ficam no service.
     */
    public static final ArchRule CONTROLLER_SO_FALA_COM_SERVICE = noClasses().that(E_CONTROLLER)
            .should().dependOnClassesThat(E_REPOSITORIO)
            .orShould().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.EntityManager")
            .orShould().dependOnClassesThat(E_JDBC)
            .because("regra de negócio e transação ficam no service");

    /** Controller não abre transação, nem na classe nem em método. */
    public static final ArchRule CONTROLLER_NAO_ABRE_TRANSACAO = noClasses().that(E_CONTROLLER)
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
    public static final ArchRule NADA_DEPENDE_DE_CONTROLLER = noClasses().that(DescribedPredicate.not(E_CONTROLLER))
            .should().dependOnClassesThat(E_CONTROLLER)
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
     * SQL nativo (JDBC do Spring em qualquer forma, JDBC cru com {@code Connection}/{@code Statement},
     * {@code createNativeQuery} ou {@code @Query(nativeQuery = true)}) só nas classes da lista, pelo <b>nome completo</b>
     * ({@code br.com.app.api.auth.ContadorBanco}; classe interna com {@code $}). A lista é o lugar visível da decisão:
     * entrada nova passa por revisão, com o porquê. Combine com {@link #exigirQueAsExcecoesExistem}.
     */
    public static ArchRule sqlNativoSoEm(Set<String> permitidas) {
        DescribedPredicate<JavaClass> foraDaLista =
                DescribedPredicate.describe("não estão na lista de SQL nativo permitido", (JavaClass c) -> !permitidas.contains(c.getName()));
        return noClasses().that(foraDaLista)
                .should().dependOnClassesThat(E_JDBC)
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
     * Falha se a lista de exceções cita uma classe que não existe mais (renomeada ou apagada) ou um nome que não é o
     * completo: lista que apodrece deixa a exceção valendo para outra classe de mesmo nome no futuro.
     */
    public static void exigirQueAsExcecoesExistem(JavaClasses codigo, String nomeDaLista, Set<String> excecoes) {
        Set<String> existentes = codigo.stream().map(JavaClass::getName).collect(Collectors.toSet());
        Set<String> sobrando = excecoes.stream().filter(nome -> !existentes.contains(nome)).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!sobrando.isEmpty()) {
            throw new AssertionError(nomeDaLista + " cita classes que não existem mais: " + sobrando);
        }
    }
}
