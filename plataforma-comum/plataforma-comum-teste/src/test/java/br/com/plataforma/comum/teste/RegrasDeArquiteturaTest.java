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
                "ControllerComRepositorio", "ControllerComEntityManager");
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
                "JdbcSolto", "NativoPeloEntityManager", "NativoComDoisArgumentos", "RepositorioNativo", "RepositorioComNativeQuery");
        assertThatThrownBy(() -> RegrasDeArquitetura.sqlNativoSoEm(Set.of()).check(errado))
                .hasMessageNotContaining("RepositorioJpql");
    }

    @Test
    void classeNaListaPodeUsarSqlNativo() {
        var lista = Set.of("JdbcSolto", "NativoPeloEntityManager", "NativoComDoisArgumentos", "RepositorioNativo",
                "RepositorioComNativeQuery");

        assertThatCode(() -> RegrasDeArquitetura.sqlNativoSoEm(lista).check(errado)).doesNotThrowAnyException();
    }

    @Test
    void listaDeExcecaoQueApodreceFalha() {
        assertThatCode(() -> RegrasDeArquitetura.exigirQueAsExcecoesExistem(errado, "SQL_NATIVO", Set.of("JdbcSolto")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> RegrasDeArquitetura.exigirQueAsExcecoesExistem(errado, "SQL_NATIVO",
                Set.of("JdbcSolto", "ClasseApagada")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SQL_NATIVO")
                .hasMessageContaining("ClasseApagada")
                .hasMessageNotContaining("JdbcSolto");
    }

    @Test
    void regrasGeraisDeCodigo() {
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.SEM_INJECAO_POR_CAMPO, "InjecaoPorCampo");
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.SEM_JAVA_UTIL_LOGGING, "LogDoJdk");
        passaNoCorretoEFalhaNoErrado(RegrasDeArquitetura.SEM_SAIDA_PADRAO, "SaidaPadrao");
    }

    @Test
    void jacksonTresAceitaOCodigoDoExemplo() {
        assertThatCode(() -> RegrasDeArquitetura.JACKSON_TRES.check(correto)).doesNotThrowAnyException();
        assertThatCode(() -> RegrasDeArquitetura.JACKSON_TRES.check(errado)).doesNotThrowAnyException();
    }
}
