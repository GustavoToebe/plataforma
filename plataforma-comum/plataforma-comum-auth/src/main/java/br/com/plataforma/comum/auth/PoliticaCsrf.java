package br.com.plataforma.comum.auth;

import java.util.Set;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * CSRF por cookie (padrão "double submit"): o cookie é legível pelo JavaScript do front e o token volta no cabeçalho. Os nomes
 * são por aplicativo. Uso na cadeia de segurança do sistema (as três peças juntas):
 * <pre>
 * http.csrf(c -&gt; c.csrfTokenRepository(politica.repositorio())
 *         .csrfTokenRequestHandler(politica.tratador())
 *         .requireCsrfProtectionMatcher(politica.exigido()))
 * </pre>
 * Sem o {@link #tratador()} vale o tratador padrão do Spring Security (XOR), que recusa o valor cru que o front lê do cookie
 * e devolve no cabeçalho: toda escrita viraria 403. É o mesmo comportamento do {@code csrf.spa()}.
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

    /**
     * Tratador para front SPA: o valor que chega no cabeçalho é o cru do cookie; sem cabeçalho (formulário renderizado pelo
     * servidor), vale o parâmetro mascarado pelo XOR, que protege contra BREACH. Igual ao {@code csrf.spa()} do Spring Security.
     */
    public CsrfTokenRequestHandler tratador() {
        return new TratadorSpa();
    }

    private static final class TratadorSpa implements CsrfTokenRequestHandler {

        private final CsrfTokenRequestHandler cru = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler mascarado = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest requisicao, HttpServletResponse resposta, Supplier<CsrfToken> token) {
            mascarado.handle(requisicao, resposta, token);
            // Carrega o token já: é isso que grava o cookie na primeira resposta (o carregamento é adiado por padrão).
            CsrfToken carregado = token.get();
            if (carregado == null) {
                throw new IllegalStateException("Repositório de CSRF não devolveu token.");
            }
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest requisicao, CsrfToken token) {
            String cabecalho = requisicao.getHeader(token.getHeaderName());
            return (cabecalho != null && !cabecalho.isBlank() ? cru : mascarado).resolveCsrfTokenValue(requisicao, token);
        }
    }
}
