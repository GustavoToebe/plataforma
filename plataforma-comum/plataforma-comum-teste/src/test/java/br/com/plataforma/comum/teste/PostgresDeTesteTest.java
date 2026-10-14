package br.com.plataforma.comum.teste;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDeTesteTest {

    private String imagemOriginal;

    @BeforeEach
    void guardar() {
        imagemOriginal = System.getProperty(PostgresDeTeste.PROPRIEDADE_IMAGEM);
    }

    @AfterEach
    void restaurar() {
        if (imagemOriginal == null) {
            System.clearProperty(PostgresDeTeste.PROPRIEDADE_IMAGEM);
        } else {
            System.setProperty(PostgresDeTeste.PROPRIEDADE_IMAGEM, imagemOriginal);
        }
    }

    @Test
    void imagemVemDaPropriedadeOuDoPadrao() {
        System.clearProperty(PostgresDeTeste.PROPRIEDADE_IMAGEM);
        assertThat(PostgresDeTeste.imagem()).isEqualTo("postgres:18-alpine");

        System.setProperty(PostgresDeTeste.PROPRIEDADE_IMAGEM, "  ");
        assertThat(PostgresDeTeste.imagem()).isEqualTo("postgres:18-alpine");

        System.setProperty(PostgresDeTeste.PROPRIEDADE_IMAGEM, "postgres:17-alpine");
        assertThat(PostgresDeTeste.imagem()).isEqualTo("postgres:17-alpine");
    }

    @Test
    void sobeUmPostgresNaMajorDoParentERegistraOsDadosDeConexao() throws Exception {
        String imagem = PostgresDeTeste.imagem();
        PostgreSQLContainer container = PostgresDeTeste.iniciar("app_test", "app", "senha_de_teste");

        Map<String, Supplier<Object>> propriedades = new HashMap<>();
        PostgresDeTeste.registrar(propriedades::put, container);

        assertThat(propriedades.get("spring.datasource.url").get()).isEqualTo(container.getJdbcUrl());
        assertThat(propriedades.get("spring.datasource.username").get()).isEqualTo("app");
        assertThat(propriedades.get("spring.datasource.password").get()).isEqualTo("senha_de_teste");
        try (var conexao = DriverManager.getConnection(container.getJdbcUrl(), "app", "senha_de_teste");
             var consulta = conexao.createStatement().executeQuery("show server_version_num")) {
            consulta.next();
            String major = imagem.substring(imagem.indexOf(':') + 1).replaceAll("^(\\d+).*", "$1");
            assertThat(consulta.getInt(1) / 10000).isEqualTo(Integer.parseInt(major));
        }
    }
}
