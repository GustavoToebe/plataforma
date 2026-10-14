package br.com.plataforma.comum.teste;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As regras passam no código certo e acusam, pelo nome da classe, cada violação do código errado. */
class RegrasDeArquiteturaTest {

    private static final String BASE = "br.com.plataforma.comum.teste.exemplo";

    private final JavaClasses correto = new ClassFileImporter().importPackages(BASE + ".correto");
    private final JavaClasses errado = new ClassFileImporter().importPackages(BASE + ".errado");

    private void passaNoCorretoEFalhaNoErrado(ArchRule regra, String... classesAcusadas) {
        assertThatCode(() -> regra.check(correto)).doesNotThrowAnyException();
        var falha = assertThatThrownBy(() -> regra.check(errado)).isInstanceOf(AssertionError.class);
        for (String classe : classesAcusadas) {
            falha.hasMessageContaining(classe);
        }
    }

    @Test
    void controllerSoFalaComService() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.CONTROLLER_SO_FALA_COM_SERVICE,
                "ControllerComRepositorio", "ControllerComEntityManager", "ControllerComRepositorioEmPortugues",
                "ControllerComSpringData", "ControllerMvcComRepositorio");
    }

    @Test
    void controllerNaoAbreTransacaoNemNaClasseNemNoMetodo() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.CONTROLLER_NAO_ABRE_TRANSACAO,
                "ControllerTransacionalNaClasse", "ControllerTransacionalNoMetodo", "ControllerComTransacaoDoJakarta");
    }

    @Test
    void nadaDependeDeController() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.NADA_DEPENDE_DE_CONTROLLER, "ServicoQueConheceController");
    }

    @Test
    void sqlNativoSoNaLista() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.sqlNativoSoEm(Set.of()),
                "JdbcSolto", "NativoPeloEntityManager", "NativoComDoisArgumentos", "RepositorioNativo", "RepositorioComNativeQuery",
                "JdbcPelaInterface", "JdbcComNomes", "InsercaoSimples", "ConexaoCrua");
        assertThatThrownBy(() -> RegrasDeArquitetura.sqlNativoSoEm(Set.of()).check(errado))
                .hasMessageNotContaining("RepositorioJpql");
    }

    private static final String ERRADO = BASE + ".errado.Errado$";

    @Test
    void classeNaListaPodeUsarSqlNativo() {
        var lista = Set.of(ERRADO + "JdbcSolto", ERRADO + "NativoPeloEntityManager", ERRADO + "NativoComDoisArgumentos",
                ERRADO + "RepositorioNativo", ERRADO + "RepositorioComNativeQuery", ERRADO + "JdbcPelaInterface",
                ERRADO + "JdbcComNomes", ERRADO + "InsercaoSimples", ERRADO + "ConexaoCrua");

        assertThatCode(() -> RegrasDeArquitetura.sqlNativoSoEm(lista).check(errado)).doesNotThrowAnyException();
    }

    /** A lista é por nome completo: uma classe de mesmo nome simples em outro pacote não herda a exceção. */
    @Test
    void listaPorNomeSimplesNaoLiberaNada() {
        assertThatThrownBy(() -> RegrasDeArquitetura.sqlNativoSoEm(Set.of("JdbcSolto")).check(errado))
                .isInstanceOf(AssertionError.class).hasMessageContaining("JdbcSolto");
    }

    @Test
    void listaDeExcecaoQueApodreceFalha() {
        assertThatCode(() -> RegrasDeArquitetura.exigirQueAsExcecoesExistem(errado, "SQL_NATIVO", Set.of(ERRADO + "JdbcSolto")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> RegrasDeArquitetura.exigirQueAsExcecoesExistem(errado, "SQL_NATIVO",
                Set.of(ERRADO + "JdbcSolto", ERRADO + "ClasseApagada", "JdbcSolto")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SQL_NATIVO")
                .hasMessageContaining("ClasseApagada")
                .hasMessageContaining("[JdbcSolto, " + ERRADO + "ClasseApagada]");
    }

    @Test
    void regrasGeraisDeCodigo() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.SEM_INJECAO_POR_CAMPO, "InjecaoPorCampo");
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.SEM_JAVA_UTIL_LOGGING, "LogDoJdk");
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.SEM_SAIDA_PADRAO, "SaidaPadrao");
    }

    @Test
    void jacksonTresRecusaOPacoteDoJacksonDois() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.JACKSON_TRES, "UsaJacksonDois");
    }
}
