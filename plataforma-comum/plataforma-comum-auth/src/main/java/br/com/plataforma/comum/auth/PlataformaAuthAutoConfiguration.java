package br.com.plataforma.comum.auth;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.plataforma.comum.seguranca.login.LimiteLogin;

/**
 * Liga a autenticação comum quando {@code plataforma.auth.habilitado=true}. O sistema precisa fornecer um
 * {@link UsuariosDaAutenticacao} e um {@link PasswordEncoder}; sem eles a aplicação não sobe (de propósito: ligar o módulo pela
 * metade deixaria a autenticação sem dono). Os beans cedem a um bean do sistema com o mesmo tipo e todos têm nome com prefixo {@code plataforma}, para não colidir com as classes
 * que o sistema ainda tem (um {@code JwtService} ou {@code CookieDeRefresh} próprio) durante a adoção.
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "plataforma.auth", name = "habilitado", havingValue = "true")
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(AuthProperties.class)
public class PlataformaAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock plataformaRelogio() {
        return Clock.systemUTC();
    }

    @Bean(initMethod = "executar")
    MigracaoPlataforma plataformaMigracao(DataSource dataSource, AuthProperties propriedades) {
        return new MigracaoPlataforma(dataSource, propriedades.migracao());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtService plataformaJwtService(AuthProperties propriedades, Clock relogio) {
        return new JwtService(propriedades, relogio);
    }

    @Bean
    @DependsOn("plataformaMigracao")
    @ConditionalOnMissingBean
    public RefreshTokens plataformaRefreshTokens(JdbcTemplate jdbc, PlatformTransactionManager transacoes, AuthProperties propriedades,
                                       UsuariosDaAutenticacao usuarios, Clock relogio) {
        return new RefreshTokens(jdbc, transacoes, propriedades, usuarios, relogio);
    }

    @Bean
    @DependsOn("plataformaMigracao")
    @ConditionalOnMissingBean
    public Mfa plataformaMfa(JdbcTemplate jdbc, PlatformTransactionManager transacoes, AuthProperties propriedades, RefreshTokens refresh,
                   Clock relogio) {
        return new Mfa(jdbc, transacoes, new MfaCifra(propriedades.mfa()), refresh, relogio);
    }

    @Bean
    @DependsOn("plataformaMigracao")
    @ConditionalOnMissingBean
    public LimiteLogin plataformaLimiteLogin(JdbcTemplate jdbc, PlatformTransactionManager transacoes, AuthProperties propriedades,
                                             Clock relogio) {
        AuthProperties.Login login = propriedades.login();
        return new LimiteLogin(login.limiteIp(), login.limiteConta(), login.janelaSegundos(), new ContadorBanco(jdbc, transacoes),
                relogio, login.sal());
    }

    @Bean
    @ConditionalOnMissingBean
    public Autenticador plataformaAutenticador(UsuariosDaAutenticacao usuarios, PasswordEncoder senhas, LimiteLogin limite, Mfa mfa,
                                     JwtService jwt, RefreshTokens refresh) {
        return new Autenticador(usuarios, senhas, limite, mfa, jwt, refresh);
    }

    @Bean
    @ConditionalOnMissingBean
    public CookieDeRefresh plataformaCookieDeRefresh(AuthProperties propriedades) {
        return new CookieDeRefresh(propriedades);
    }

    @Bean
    @ConditionalOnMissingBean
    public PoliticaCsrf plataformaPoliticaCsrf(AuthProperties propriedades, CookieDeRefresh cookie) {
        return new PoliticaCsrf(propriedades, cookie);
    }
}
