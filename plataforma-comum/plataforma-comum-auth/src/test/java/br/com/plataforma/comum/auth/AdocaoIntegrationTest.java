package br.com.plataforma.comum.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.plataforma.comum.teste.PostgresDeTeste;

/**
 * Os dois cuidados da adoção: (1) um sistema que ainda tem classes próprias com os nomes de bean do módulo (jwtService,
 * cookieDeRefresh...) sobe com o módulo ligado; (2) com papéis migrador/app separados, o papel de execução enxerga o schema
 * plataforma e só ele (nada de DDL).
 */
@SpringBootTest(classes = {AppDeTeste.class, AdocaoIntegrationTest.BeansDoSistema.class}, properties = {
        "plataforma.auth.habilitado=true",
        "plataforma.auth.jwt.secret=" + AuthIntegrationTest.SEGREDO,
        "plataforma.auth.migracao.papel-app=app_rt",
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

    /** O que um sistema em adoção gradual já tem: beans com os mesmos nomes dos do módulo, de outros tipos. */
    @Configuration(proxyBeanMethods = false)
    static class BeansDoSistema {
        @Bean StringBuilder jwtService() { return new StringBuilder("jwt do sistema"); }
        @Bean StringBuilder cookieDeRefresh() { return new StringBuilder("cookie do sistema"); }
        @Bean StringBuilder refreshTokens() { return new StringBuilder("refresh do sistema"); }
        @Bean StringBuilder autenticador() { return new StringBuilder("autenticador do sistema"); }
        @Bean StringBuilder mfa() { return new StringBuilder("mfa do sistema"); }
        @Bean StringBuilder limiteLogin() { return new StringBuilder("limite do sistema"); }
        @Bean StringBuilder politicaCsrf() { return new StringBuilder("csrf do sistema"); }
    }

    @Autowired ApplicationContext contexto;

    @Test
    void sistemaComBeansDeMesmoNomeSobeEOsDoSistemaContinuamSendoOsDele() {
        assertThat(contexto.getBean(Autenticador.class)).isNotNull();
        assertThat(contexto.getBean(JwtService.class)).isNotNull();
        assertThat(contexto.getBean("jwtService")).isInstanceOf(StringBuilder.class);
        assertThat(contexto.getBean("cookieDeRefresh")).isInstanceOf(StringBuilder.class);
    }

    @Test
    void papelDeExecucaoUsaOSchemaMasNaoMudaEstrutura() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_rt", "app_rt"); Statement s = c.createStatement()) {
            s.execute("insert into plataforma.refresh_token (sujeito, token_hash, expira_em) values "
                    + "('x', repeat('a', 64), now() + interval '1 day')");
            assertThat(s.executeQuery("select count(*) from plataforma.refresh_token").next()).isTrue();
            s.execute("delete from plataforma.refresh_token where sujeito = 'x'");
            assertThatThrownBy(() -> s.execute("create table plataforma.intruso (id int)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("drop table plataforma.refresh_token")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("delete from plataforma.historico_migracao")).isInstanceOf(SQLException.class);
        }
    }
}
