package br.com.plataforma.comum.central;

import java.time.Clock;
import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

/**
 * Liga o lado do app do contrato de integração v1 quando {@code plataforma.central.habilitado=true}. Todos os beans têm nome com
 * prefixo {@code plataforma} e cedem a um bean do sistema do mesmo tipo (adoção gradual: o sistema pode ter um
 * {@code CentralClient} ou {@code IntegracaoFiltro} próprio até migrar). O filtro de entrada não é registrado no contêiner:
 * veja {@link IntegracaoFiltro}. O cliente de saída só existe com {@code central-url} configurado.
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "plataforma.central", name = "habilitado", havingValue = "true")
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(CentralProperties.class)
public class PlataformaCentralAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock plataformaRelogio() {
        return Clock.systemUTC();
    }

    @Bean(initMethod = "executar")
    MigracaoIntegracao plataformaMigracaoIntegracao(DataSource dataSource, CentralProperties propriedades) {
        return new MigracaoIntegracao(dataSource, propriedades.migracao());
    }

    @Bean
    @DependsOn("plataformaMigracaoIntegracao")
    @ConditionalOnMissingBean
    NonceStore plataformaNonceStore(JdbcTemplate jdbc, PlatformTransactionManager transacoes, Clock relogio) {
        return new NonceStore(jdbc, transacoes, relogio);
    }

    @Bean
    @ConditionalOnMissingBean
    public IntegracaoFiltro plataformaIntegracaoFiltro(CentralProperties propriedades, NonceStore nonces, Clock relogio, JsonMapper json) {
        ChavesDeIntegracao chaves = new ChavesDeIntegracao(propriedades.chavesEntrada());
        if (chaves.vazia()) {
            throw new IllegalStateException("plataforma.central.chaves-entrada é obrigatório com o módulo habilitado.");
        }
        return new IntegracaoFiltro(propriedades, chaves, nonces, relogio, json);
    }

    @Bean
    @DependsOn("plataformaMigracaoIntegracao")
    @ConditionalOnMissingBean
    public Idempotencia plataformaIdempotencia(JdbcTemplate jdbc, PlatformTransactionManager transacoes) {
        return new Idempotencia(jdbc, transacoes);
    }

    @Bean
    @DependsOn("plataformaMigracaoIntegracao")
    @ConditionalOnMissingBean
    public DireitosLocais plataformaDireitosLocais(JdbcTemplate jdbc, PlatformTransactionManager transacoes, Clock relogio,
                                                   CentralProperties propriedades) {
        return new DireitosLocais(jdbc, transacoes, relogio, Duration.ofHours(propriedades.toleranciaHoras()));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${plataforma.central.central-url:}' != ''")
    public CentralClient plataformaCentralClient(CentralProperties propriedades, Clock relogio) {
        return new CentralClient(propriedades, relogio);
    }
}
