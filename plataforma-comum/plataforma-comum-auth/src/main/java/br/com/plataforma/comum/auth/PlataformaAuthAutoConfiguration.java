package br.com.plataforma.comum.auth;

import java.time.Clock;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
@EnableConfigurationProperties({AuthProperties.class, EmailProperties.class, RedefinicaoProperties.class})
public class PlataformaAuthAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlataformaAuthAutoConfiguration.class);

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

    /**
     * E-mail (1.9.0), só com {@code plataforma.auth.email.habilitado=true}: o provedor {@code resend} envia de verdade, o {@code log}
     * (padrão) só registra que houve envio. Um {@link EnviadorDeEmail} do sistema toma o lugar.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "plataforma.auth.email", name = "habilitado", havingValue = "true")
    public EnviadorDeEmail plataformaEnviadorDeEmail(EmailProperties propriedades) {
        if (propriedades.usaResend()) {
            return new EnviadorResend(propriedades);
        }
        LOG.warn("plataforma.auth.email.provedor=log: nenhum e-mail de acesso será enviado de verdade.");
        return new EnviadorRegistro();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "plataforma.auth.email", name = "habilitado", havingValue = "true")
    public EmailsDeAcesso plataformaEmailsDeAcesso(EnviadorDeEmail enviador, EmailProperties propriedades) {
        return new EmailsDeAcesso(enviador, propriedades);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "plataforma.auth.redefinicao", name = "habilitado", havingValue = "true")
    public DespachoDeEmail plataformaDespachoDeEmail() {
        return new DespachoVirtual();
    }

    /** Redefinição de senha e convite (1.9.0), só com {@code plataforma.auth.redefinicao.habilitado=true} (e o e-mail ligado). */
    @Bean
    @DependsOn("plataformaMigracao")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "plataforma.auth.redefinicao", name = "habilitado", havingValue = "true")
    public RedefinicaoDeSenha plataformaRedefinicaoDeSenha(JdbcTemplate jdbc, PlatformTransactionManager transacoes,
                                                           RedefinicaoProperties redefinicao, AuthProperties propriedades, Mfa mfa,
                                                           RefreshTokens refresh, UsuariosDaAutenticacao usuarios, PasswordEncoder senhas,
                                                           ObjectProvider<EmailsDeAcesso> emails, DespachoDeEmail despacho, Clock relogio) {
        EmailsDeAcesso envio = emails.getIfAvailable();
        if (envio == null) {
            throw new IllegalStateException("plataforma.auth.redefinicao.habilitado=true exige plataforma.auth.email.habilitado=true.");
        }
        return new RedefinicaoDeSenha(jdbc, transacoes, redefinicao, new ContadorBanco(jdbc, transacoes), propriedades.login().sal(), mfa,
                refresh, usuarios, senhas, envio, despacho, relogio);
    }
}
