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
 * metade deixaria a autenticação sem dono). Os beans cedem a um bean do sistema com o mesmo tipo.
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
    public Clock relogioDaAutenticacao() {
        return Clock.systemUTC();
    }

    @Bean(initMethod = "executar")
    MigracaoPlataforma migracaoPlataforma(DataSource dataSource, AuthProperties propriedades) {
        return new MigracaoPlataforma(dataSource, propriedades.migracao());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(AuthProperties propriedades, Clock relogio) {
        return new JwtService(propriedades, relogio);
    }

    @Bean
    @DependsOn("migracaoPlataforma")
    @ConditionalOnMissingBean
    public RefreshTokens refreshTokens(JdbcTemplate jdbc, PlatformTransactionManager transacoes, AuthProperties propriedades,
                                       UsuariosDaAutenticacao usuarios, Clock relogio) {
        return new RefreshTokens(jdbc, transacoes, propriedades, usuarios, relogio);
    }

    @Bean
    @DependsOn("migracaoPlataforma")
    @ConditionalOnMissingBean
    public Mfa mfa(JdbcTemplate jdbc, PlatformTransactionManager transacoes, AuthProperties propriedades, RefreshTokens refresh,
                   Clock relogio) {
        return new Mfa(jdbc, transacoes, new MfaCifra(propriedades.mfa()), refresh, relogio);
    }

    @Bean
    @DependsOn("migracaoPlataforma")
    @ConditionalOnMissingBean
    public LimiteLogin limiteLogin(JdbcTemplate jdbc, AuthProperties propriedades, Clock relogio) {
        AuthProperties.Login login = propriedades.login();
        String sal = login.sal() == null || login.sal().isBlank() ? "limite-login:" + propriedades.jwt().secret() : login.sal();
        return new LimiteLogin(login.limiteIp(), login.limiteConta(), login.janelaSegundos(), new ContadorBanco(jdbc), relogio, sal);
    }

    @Bean
    @ConditionalOnMissingBean
    public Autenticador autenticador(UsuariosDaAutenticacao usuarios, PasswordEncoder senhas, LimiteLogin limite, Mfa mfa,
                                     JwtService jwt, RefreshTokens refresh) {
        return new Autenticador(usuarios, senhas, limite, mfa, jwt, refresh);
    }

    @Bean
    @ConditionalOnMissingBean
    public CookieDeRefresh cookieDeRefresh(AuthProperties propriedades) {
        return new CookieDeRefresh(propriedades);
    }

    @Bean
    @ConditionalOnMissingBean
    public PoliticaCsrf politicaCsrf(AuthProperties propriedades, CookieDeRefresh cookie) {
        return new PoliticaCsrf(propriedades, cookie);
    }
}
