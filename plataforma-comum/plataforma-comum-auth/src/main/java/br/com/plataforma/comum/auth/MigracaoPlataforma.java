package br.com.plataforma.comum.auth;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

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
        Flyway flyway = configuracao.load();
        flyway.migrate();
        conceder(flyway);
    }

    /**
     * Quando quem migra não é quem executa (papéis migrador e app), o papel de execução precisa enxergar o schema. Roda a cada
     * subida (é idempotente) e vale também para tabelas de versões futuras do módulo. O nome do papel é validado na
     * configuração e vai entre aspas.
     */
    private void conceder(Flyway flyway) {
        String papel = credencial.papelApp();
        if (papel == null || papel.isBlank()) {
            return;
        }
        String destino = "\"" + papel + "\"";
        try (Connection conexao = flyway.getConfiguration().getDataSource().getConnection(); Statement comando = conexao.createStatement()) {
            comando.execute("grant usage on schema plataforma to " + destino);
            // Todas as tabelas, menos o histórico do Flyway: o papel de execução nunca reescreve o histórico de migrations.
            comando.execute("""
                    do $$
                    declare tabela text;
                    begin
                      for tabela in select tablename from pg_tables
                                    where schemaname = 'plataforma' and tablename <> 'historico_migracao' loop
                        execute format('grant select, insert, update, delete on plataforma.%I to @PAPEL@', tabela);
                      end loop;
                    end $$""".replace("@PAPEL@", destino));
            comando.execute("alter default privileges in schema plataforma grant select, insert, update, delete on tables to " + destino);
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível conceder o schema plataforma ao papel " + papel + ".", e);
        }
    }
}
