package br.com.plataforma.comum.auth;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Migrations do módulo: schema {@code plataforma}, histórico próprio ({@code plataforma.historico_migracao}) e scripts em
 * {@code classpath:plataforma/db/auth} (um caminho que o Flyway do sistema, em {@code db/migration}, não varre). Não é um bean
 * {@link Flyway} de propósito: o do Spring Boot só liga quando não existe nenhum, e um segundo bean desligaria o do sistema.
 */
class MigracaoPlataforma {

    private final DataSource dataSource;
    private final AuthProperties.Migracao credencial;

    MigracaoPlataforma(DataSource dataSource, AuthProperties.Migracao credencial) {
        this.dataSource = dataSource;
        this.credencial = credencial;
    }

    void executar() {
        FluentConfiguration configuracao = Flyway.configure()
                .locations("classpath:plataforma/db/auth")
                .schemas("plataforma")
                .defaultSchema("plataforma")
                .createSchemas(true)
                .table("historico_migracao")
                .cleanDisabled(true);
        if (credencial.url() != null && !credencial.url().isBlank()) {
            configuracao.dataSource(credencial.url(), credencial.user(), credencial.password());
        } else {
            configuracao.dataSource(dataSource);
        }
        configuracao.load().migrate();
    }
}
