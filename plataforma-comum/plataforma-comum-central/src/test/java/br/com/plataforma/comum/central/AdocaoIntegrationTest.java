package br.com.plataforma.comum.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.plataforma.comum.auth.UsuariosDaAutenticacao;
import br.com.plataforma.comum.teste.PostgresDeTeste;

/**
 * Os cuidados da adoção: (1) um sistema que ainda tem classes próprias com os nomes de bean do módulo sobe com o módulo ligado;
 * (2) com papéis migrador/app separados, o papel de execução usa o schema e só ele (nada de DDL, nada no histórico); (3) o módulo
 * convive com o módulo auth e com o Flyway do sistema no mesmo banco, cada um com o seu schema e o seu histórico.
 */
@SpringBootTest(classes = {AppDeTeste.class, AdocaoIntegrationTest.BeansDoSistema.class}, properties = {
        "plataforma.central.habilitado=true",
        "plataforma.central.chaves-entrada=" + IntegracaoIntegrationTest.ENTRADA,
        "plataforma.central.migracao.papel-app=app_rt",
        "plataforma.auth.habilitado=true",
        "plataforma.auth.jwt.secret=" + IntegracaoIntegrationTest.SEGREDO_A,
        "plataforma.auth.jwt.emissor=app-de-teste",
        "plataforma.auth.login.sal=sal-do-teste",
        "plataforma.auth.mfa.chaves=k1:" + IntegracaoIntegrationTest.SEGREDO_A,
        "plataforma.auth.mfa.chave-ativa=k1",
        "spring.main.banner-mode=off"
})
class AdocaoIntegrationTest {

    static final PostgreSQLContainer POSTGRES = PostgresDeTeste.iniciar("adocao_test", "adocao_test", "adocao_test");

    static {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create role app_rt login password 'app_rt'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        PostgresDeTeste.registrar(registry, POSTGRES);
    }

    /** O que um sistema em adoção gradual já tem: beans com os mesmos nomes dos do módulo, de outros tipos, e o que o auth pede. */
    @Configuration(proxyBeanMethods = false)
    static class BeansDoSistema {
        @Bean StringBuilder centralClient() { return new StringBuilder("cliente do sistema"); }
        @Bean StringBuilder integracaoFiltro() { return new StringBuilder("filtro do sistema"); }
        @Bean StringBuilder idempotencia() { return new StringBuilder("idempotencia do sistema"); }
        @Bean StringBuilder direitosLocais() { return new StringBuilder("direitos do sistema"); }
        @Bean StringBuilder nonceStore() { return new StringBuilder("nonce do sistema"); }

        @Bean
        PasswordEncoder senhas() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        UsuariosDaAutenticacao usuarios() {
            return new UsuariosDaAutenticacao() {
                @Override
                public Optional<Credencial> buscarPorLogin(String login) {
                    return Optional.empty();
                }

                @Override
                public Optional<Long> versaoDeCredenciais(String sujeito) {
                    return Optional.empty();
                }
            };
        }
    }

    @Autowired ApplicationContext contexto;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sistemaComBeansDeMesmoNomeSobeEOsDoSistemaContinuamSendoOsDele() {
        assertThat(contexto.getBean(DireitosLocais.class)).isNotNull();
        assertThat(contexto.getBean(Idempotencia.class)).isNotNull();
        assertThat(contexto.getBean(IntegracaoFiltro.class)).isNotNull();
        assertThat(contexto.getBean("centralClient")).isInstanceOf(StringBuilder.class);
        assertThat(contexto.getBean("direitosLocais")).isInstanceOf(StringBuilder.class);
        assertThat(contexto.getBeansOfType(CentralClient.class)).isEmpty();
    }

    @Test
    void filtroDeIntegracaoNaoEhRegistradoNoConteinerSoNaCadeiaDeSeguranca() {
        FilterRegistrationBean<?> registro = contexto.getBean("plataformaIntegracaoFiltroRegistro", FilterRegistrationBean.class);
        assertThat(registro.isEnabled()).isFalse();
        assertThat(registro.getFilter()).isSameAs(contexto.getBean(IntegracaoFiltro.class));
    }

    @Test
    void convivemOFlywayDoSistemaOAuthEOCentralCadaUmComOSeuHistorico() {
        List<String> historicos = jdbc.queryForList("""
                select table_schema || '.' || table_name from information_schema.tables
                where table_name in ('historico_migracao', 'flyway_schema_history') order by 1""", String.class);
        assertThat(historicos).containsExactly("plataforma.historico_migracao", "plataforma_integracao.historico_migracao",
                "public.flyway_schema_history");
        assertThat(jdbc.queryForObject("select count(*) from public.instancia_app", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plataforma_integracao.direitos_locais", Integer.class)).isZero();
    }

    @Test
    void papelDeExecucaoUsaOSchemaMasNaoMudaEstrutura() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_rt", "app_rt"); Statement s = c.createStatement()) {
            s.execute("insert into plataforma_integracao.integracao_nonce (chave_id, nonce) values ('k', 'n')");
            s.execute("delete from plataforma_integracao.integracao_nonce where chave_id = 'k'");
            assertThatThrownBy(() -> s.execute("create table plataforma_integracao.intruso (id int)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("drop table plataforma_integracao.direitos_locais")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("delete from plataforma_integracao.historico_migracao")).isInstanceOf(SQLException.class);
        }
    }
}
