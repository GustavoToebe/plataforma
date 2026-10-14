package br.com.plataforma.comum.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.Cookie;

class ConfiguracaoTest {

    private static final String SEGREDO = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static AuthProperties propriedades(AuthProperties.Cookie cookie, AuthProperties.Csrf csrf, AuthProperties.Jwt jwt) {
        return new AuthProperties(true, jwt, new AuthProperties.Refresh(Duration.ofDays(7)), cookie, csrf,
                new AuthProperties.Login(60, 10, 600, null), new AuthProperties.Mfa(null, null, "x"),
                new AuthProperties.Migracao(null, null, null));
    }

    private static AuthProperties padrao() {
        return propriedades(new AuthProperties.Cookie("central_refresh", "/api/auth", null, true, "Strict"),
                new AuthProperties.Csrf("CENTRAL-XSRF-TOKEN", "X-XSRF-TOKEN", "/", null, true, "Lax", true),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1)));
    }

    @Test
    void sameSiteNoneSemSecureNaoSobe() {
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, false, "None"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SameSite=None exige secure");
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, true, "Lax"),
                new AuthProperties.Csrf("c", "h", "/", null, false, "None", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plataforma.auth.csrf");
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, true, "Qualquer"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validadeDoAccessTemTeto() {
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofHours(2), Duration.ofHours(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("access-ttl");
    }

    @Test
    void segredoDoJwtPrecisaTer32Bytes() {
        AuthProperties curto = propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(Base64.getEncoder().encodeToString(new byte[16]), Duration.ofMinutes(15), Duration.ofHours(1)));
        assertThatThrownBy(() -> new JwtService(curto, new RelogioMutavel())).isInstanceOf(IllegalStateException.class);
        AuthProperties invalido = propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt("não é base64!", Duration.ofMinutes(15), Duration.ofHours(1)));
        assertThatThrownBy(() -> new JwtService(invalido, new RelogioMutavel())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suporteExigeOperadorMotivoEPrazoDentroDoTeto() {
        JwtService jwt = new JwtService(padrao(), new RelogioMutavel());
        assertThatThrownBy(() -> jwt.emitirSuporte("ana", 1, " ", "chamado", Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.emitirSuporte("ana", 1, "op", "", Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.emitirSuporte("ana", 1, "op", "chamado", Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jwt.validar(jwt.emitirSuporte("ana", 1, "op", "chamado", Duration.ofMinutes(5)), JwtService.SUPORTE).operador())
                .isEqualTo("op");
    }

    @Test
    void tokenAdulteradoOuSemVersaoEhRecusado() {
        JwtService jwt = new JwtService(padrao(), new RelogioMutavel());
        String token = jwt.emitirAcesso("ana", 1);
        String adulterado = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        assertThatThrownBy(() -> jwt.validar(adulterado, JwtService.ACESSO))
                .isInstanceOf(br.com.plataforma.comum.web.UnauthorizedException.class);
        assertThatThrownBy(() -> jwt.validar("", JwtService.ACESSO))
                .isInstanceOf(br.com.plataforma.comum.web.UnauthorizedException.class);
        // Outra chave: assinatura não confere.
        AuthProperties outra = propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(Base64.getEncoder().encodeToString("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ".getBytes(StandardCharsets.UTF_8)),
                        Duration.ofMinutes(15), Duration.ofHours(1)));
        assertThatThrownBy(() -> new JwtService(outra, new RelogioMutavel()).validar(token, JwtService.ACESSO))
                .isInstanceOf(br.com.plataforma.comum.web.UnauthorizedException.class);
    }

    @Test
    void cookieDeRefreshEhHttpOnlyComOsAtributosDoAplicativo() {
        CookieDeRefresh cookie = new CookieDeRefresh(padrao());
        ResponseCookie criado = cookie.criar("abc");
        assertThat(criado.getName()).isEqualTo("central_refresh");
        assertThat(criado.isHttpOnly()).isTrue();
        assertThat(criado.isSecure()).isTrue();
        assertThat(criado.getSameSite()).isEqualTo("Strict");
        assertThat(criado.getPath()).isEqualTo("/api/auth");
        assertThat(criado.getMaxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(cookie.limpar().getMaxAge()).isEqualTo(Duration.ZERO);

        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        assertThat(cookie.presente(requisicao)).isFalse();
        requisicao.setCookies(new Cookie("central_refresh", "valor"), new Cookie("outro", "x"));
        assertThat(cookie.ler(requisicao)).contains("valor");
    }

    @Test
    void csrfPorAplicativoEExigidoSoComCookieDeRefreshQuandoConfigurado() {
        PoliticaCsrf politica = new PoliticaCsrf(padrao(), new CookieDeRefresh(padrao()));
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/qualquer");
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/qualquer");
        MockHttpServletRequest postComCookie = new MockHttpServletRequest("POST", "/api/auth/refresh");
        postComCookie.setCookies(new Cookie("central_refresh", "x"));

        assertThat(politica.exigido().matches(post)).isFalse();
        assertThat(politica.exigido().matches(get)).isFalse();
        assertThat(politica.exigido().matches(postComCookie)).isTrue();
        assertThat(politica.repositorio()).isNotNull();

        AuthProperties sempre = propriedades(new AuthProperties.Cookie("central_refresh", "/api/auth", null, true, "Strict"),
                new AuthProperties.Csrf("CENTRAL-XSRF-TOKEN", "X-XSRF-TOKEN", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1)));
        PoliticaCsrf toda = new PoliticaCsrf(sempre, new CookieDeRefresh(sempre));
        assertThat(toda.exigido().matches(post)).isTrue();
        assertThat(toda.exigido().matches(get)).isFalse();
    }
}
