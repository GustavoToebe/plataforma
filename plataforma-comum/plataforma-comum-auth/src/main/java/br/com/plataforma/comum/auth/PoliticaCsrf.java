package br.com.plataforma.comum.auth;

import java.util.Set;

import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * CSRF por cookie (padrão "double submit"): o cookie é legível pelo JavaScript do front e o token volta no cabeçalho. Os nomes
 * são por aplicativo. Uso na cadeia de segurança do sistema:
 * <pre>
 * http.csrf(c -&gt; c.csrfTokenRepository(politica.repositorio()).requireCsrfProtectionMatcher(politica.exigido()))
 * </pre>
 */
public class PoliticaCsrf {

    private static final Set<String> METODOS_SEGUROS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AuthProperties.Csrf config;
    private final CookieDeRefresh cookieDeRefresh;

    public PoliticaCsrf(AuthProperties propriedades, CookieDeRefresh cookieDeRefresh) {
        this.config = propriedades.csrf();
        this.cookieDeRefresh = cookieDeRefresh;
    }

    public CookieCsrfTokenRepository repositorio() {
        CookieCsrfTokenRepository repositorio = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repositorio.setCookieName(config.cookie());
        repositorio.setHeaderName(config.header());
        repositorio.setCookiePath(config.path());
        repositorio.setCookieCustomizer(cookie -> {
            cookie.secure(config.secure()).sameSite(config.sameSite());
            if (config.dominio() != null && !config.dominio().isBlank()) {
                cookie.domain(config.dominio());
            }
        });
        return repositorio;
    }

    /**
     * Quais requisições exigem o token: toda escrita ou, com {@code somente-com-cookie-de-refresh}, só a escrita que traz o
     * cookie do refresh (o navegador só envia cookie por conta própria; um Bearer não pode ser forjado entre sites).
     */
    public RequestMatcher exigido() {
        return requisicao -> !METODOS_SEGUROS.contains(requisicao.getMethod())
                && (!config.somenteComCookieDeRefresh() || cookieDeRefresh.presente(requisicao));
    }
}
