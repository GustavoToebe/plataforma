package br.com.plataforma.comum.central;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Migrations do módulo: schema {@code plataforma_integracao}, histórico próprio e scripts em
 * {@code classpath:plataforma/db/central}. Schema separado do módulo auth (que usa {@code plataforma}) para os dois módulos
 * conviverem: o Flyway recusa um schema não vazio que não tenha o seu histórico. Não é um bean {@link Flyway} de propósito
 * (o do Spring Boot só liga quando não existe nenhum).
 */
class MigracaoIntegracao {

    static final String SCHEMA = "plataforma_integracao";

    private final DataSource dataSource;
    private final CentralProperties.Migracao credencial;

    MigracaoIntegracao(DataSource dataSource, CentralProperties.Migracao credencial) {
        this.dataSource = dataSource;
        this.credencial = credencial;
    }

    void executar() {
        FluentConfiguration configuracao = Flyway.configure()
                .locations("classpath:plataforma/db/central")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
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

    /** Papel de execução diferente do migrador: uso do schema e DML nas tabelas, nunca o histórico do Flyway. */
    private void conceder(Flyway flyway) {
        String papel = credencial.papelApp();
        if (papel == null || papel.isBlank()) {
            return;
        }
        String destino = "\"" + papel + "\"";
        try (Connection conexao = flyway.getConfiguration().getDataSource().getConnection(); Statement comando = conexao.createStatement()) {
            comando.execute("grant usage on schema " + SCHEMA + " to " + destino);
            comando.execute("""
                    do $$
                    declare tabela text;
                    begin
                      for tabela in select tablename from pg_tables
                                    where schemaname = '@SCHEMA@' and tablename <> 'historico_migracao' loop
                        execute format('grant select, insert, update, delete on @SCHEMA@.%I to @PAPEL@', tabela);
                      end loop;
                    end $$""".replace("@SCHEMA@", SCHEMA).replace("@PAPEL@", destino));
            comando.execute("alter default privileges in schema " + SCHEMA + " grant select, insert, update, delete on tables to " + destino);
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível conceder o schema " + SCHEMA + " ao papel " + papel + ".", e);
        }
    }
}
