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
                new AuthProperties.Login(60, 10, 600, "sal"), new AuthProperties.Mfa(null, null, "x"),
                new AuthProperties.Migracao(null, null, null, null));
    }

    private static AuthProperties padrao() {
        return propriedades(new AuthProperties.Cookie("central_refresh", "/api/auth", null, true, "Strict"),
                new AuthProperties.Csrf("CENTRAL-XSRF-TOKEN", "X-XSRF-TOKEN", "/", null, true, "Lax", true),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of()));
    }

    @Test
    void sameSiteNoneSemSecureNaoSobe() {
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, false, "None"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SameSite=None exige secure");
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, true, "Lax"),
                new AuthProperties.Csrf("c", "h", "/", null, false, "None", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plataforma.auth.csrf");
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, true, "Qualquer"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void papelDoAppPrecisaSerNomeDeIdentificador() {
        assertThatThrownBy(() -> new AuthProperties(true, new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of()),
                new AuthProperties.Refresh(Duration.ofDays(7)), new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false), new AuthProperties.Login(60, 10, 600, "sal"),
                new AuthProperties.Mfa(null, null, "x"), new AuthProperties.Migracao(null, null, null, "app\"; drop table x; --")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("papel-app");
    }

    @Test
    void claimsExtrasNaoPodemUsarNomesDoModuloNemTiposEstranhos() {
        JwtService jwt = new JwtService(padrao(), new RelogioMutavel());
        assertThatThrownBy(() -> jwt.emitirAcesso("ana", 1, java.util.Map.of("cv", 99))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.emitirAcesso("ana", 1, java.util.Map.of("purpose", "suporte"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.emitirAcesso("ana", 1, java.util.Map.of("obj", new Object()))).isInstanceOf(IllegalArgumentException.class);
        String token = jwt.emitirAcesso("ana", 1, java.util.Map.of("tenant", "t1", "roles", java.util.List.of("ADMIN", "LEITOR")));
        JwtService.Token validado = jwt.validar(token, JwtService.ACESSO);
        assertThat(validado.extras()).containsEntry("tenant", "t1").containsEntry("roles", java.util.List.of("ADMIN", "LEITOR"));
        assertThat(validado.versao()).isEqualTo(1);
    }

    @Test
    void comOModuloLigadoEmissorDoJwtESalDoLimiteSaoObrigatorios() {
        AuthProperties.Cookie cookie = new AuthProperties.Cookie("r", "/", null, true, "Strict");
        AuthProperties.Csrf csrf = new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false);
        AuthProperties.Refresh refresh = new AuthProperties.Refresh(Duration.ofDays(7));
        AuthProperties.Mfa mfa = new AuthProperties.Mfa(null, null, "x");
        AuthProperties.Migracao migracao = new AuthProperties.Migracao(null, null, null, null);
        AuthProperties.Login comSal = new AuthProperties.Login(60, 10, 600, "sal");
        assertThatThrownBy(() -> new AuthProperties(true, new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), " ", java.util.Map.of()),
                refresh, cookie, csrf, comSal, mfa, migracao))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plataforma.auth.jwt.emissor");
        AuthProperties.Jwt jwt = new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app", java.util.Map.of());
        // Sem sal próprio, trocar o segredo do JWT zeraria os contadores de login: o sal não deriva de nada.
        assertThatThrownBy(() -> new AuthProperties(true, jwt, refresh, cookie, csrf, new AuthProperties.Login(60, 10, 600, null),
                mfa, migracao))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plataforma.auth.login.sal");
        // Desligado, nada é exigido (adoção gradual).
        assertThat(new AuthProperties(false, new AuthProperties.Jwt(null, Duration.ofMinutes(15), Duration.ofHours(1), null, null),
                refresh, cookie, csrf, new AuthProperties.Login(60, 10, 600, null), mfa, migracao).habilitado()).isFalse();
    }

    /** O jjwt aceita para sempre um token sem {@code exp}; e um token de outro aplicativo com o mesmo segredo não serve aqui. */
    @Test
    void tokenSemValidadeOuDeOutroEmissorEhRecusado() {
        JwtService jwt = new JwtService(padrao(), new RelogioMutavel());
        javax.crypto.SecretKey chave = io.jsonwebtoken.security.Keys.hmacShaKeyFor(Base64.getDecoder().decode(SEGREDO));
        String semValidade = io.jsonwebtoken.Jwts.builder().subject("ana").issuer("app-de-teste").claim("purpose", "access")
                .claim("cv", 1).signWith(chave).compact();
        assertThatThrownBy(() -> jwt.validar(semValidade, JwtService.ACESSO))
                .isInstanceOf(br.com.plataforma.comum.web.UnauthorizedException.class);

        java.util.Date daqui = java.util.Date.from(new RelogioMutavel().instant().plus(Duration.ofMinutes(5)));
        String outroApp = io.jsonwebtoken.Jwts.builder().subject("ana").issuer("outro-app").claim("purpose", "access")
                .claim("cv", 1).expiration(daqui).signWith(chave).compact();
        assertThatThrownBy(() -> jwt.validar(outroApp, JwtService.ACESSO))
                .isInstanceOf(br.com.plataforma.comum.web.UnauthorizedException.class);
        String semEmissor = io.jsonwebtoken.Jwts.builder().subject("ana").claim("purpose", "access")
                .claim("cv", 1).expiration(daqui).signWith(chave).compact();
        assertThatThrownBy(() -> jwt.validar(semEmissor, JwtService.ACESSO))
                .isInstanceOf(br.com.plataforma.comum.web.UnauthorizedException.class);

        assertThat(jwt.validar(jwt.emitirAcesso("ana", 1), JwtService.ACESSO).sujeito()).isEqualTo("ana");
    }

    /**
     * Os fronts (SPA) leem o cookie e devolvem o valor cru no cabeçalho. O tratador padrão do Spring Security (XOR) não aceita
     * o valor cru, e toda escrita viraria 403; o tratador da política aceita o cru no cabeçalho e o XOR no parâmetro.
     */
    @Test
    void tratadorDoCsrfAceitaOValorCruQueOFrontDevolveNoCabecalho() {
        PoliticaCsrf politica = new PoliticaCsrf(padrao(), new CookieDeRefresh(padrao()));
        org.springframework.security.web.csrf.CsrfToken token =
                new org.springframework.security.web.csrf.DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "valor-do-cookie");
        MockHttpServletRequest requisicao = new MockHttpServletRequest("POST", "/api/qualquer");
        requisicao.addHeader("X-XSRF-TOKEN", "valor-do-cookie");

        assertThat(new org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler()
                .resolveCsrfTokenValue(requisicao, token)).isNotEqualTo("valor-do-cookie");
        assertThat(politica.tratador().resolveCsrfTokenValue(requisicao, token)).isEqualTo("valor-do-cookie");

        // Sem cabeçalho (formulário), vale o valor mascarado pelo XOR que a página renderizou.
        MockHttpServletRequest formulario = new MockHttpServletRequest("POST", "/api/qualquer");
        org.springframework.mock.web.MockHttpServletResponse resposta = new org.springframework.mock.web.MockHttpServletResponse();
        politica.tratador().handle(formulario, resposta, () -> token);
        String mascarado = ((org.springframework.security.web.csrf.CsrfToken) formulario.getAttribute("_csrf")).getToken();
        formulario.setParameter("_csrf", mascarado);
        assertThat(politica.tratador().resolveCsrfTokenValue(formulario, token)).isEqualTo("valor-do-cookie");
    }

    @Test
    void propositosDoAplicativoTemNomeELimiteDeValidade() {
        java.util.function.Function<java.util.Map<String, Duration>, AuthProperties.Jwt> com =
                mapa -> new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app", mapa);
        AuthProperties.Cookie cookie = new AuthProperties.Cookie("r", "/", null, true, "Strict");
        AuthProperties.Csrf csrf = new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false);
        java.util.function.Function<java.util.Map<String, Duration>, AuthProperties> props = mapa -> new AuthProperties(true, com.apply(mapa),
                new AuthProperties.Refresh(Duration.ofDays(7)), cookie, csrf, new AuthProperties.Login(60, 10, 600, "sal"),
                new AuthProperties.Mfa(null, null, "x"), new AuthProperties.Migracao(null, null, null, null));

        assertThat(props.apply(java.util.Map.of("tenant_selection", Duration.ofMinutes(5))).jwt().propositos()).hasSize(1);
        assertThatThrownBy(() -> props.apply(java.util.Map.of("access", Duration.ofMinutes(5)))).hasMessageContaining("reservado");
        assertThatThrownBy(() -> props.apply(java.util.Map.of("suporte", Duration.ofMinutes(5)))).hasMessageContaining("reservado");
        assertThatThrownBy(() -> props.apply(java.util.Map.of("Nome Ruim", Duration.ofMinutes(5)))).hasMessageContaining("inválido");
        assertThatThrownBy(() -> props.apply(java.util.Map.of("passo", Duration.ofDays(1)))).hasMessageContaining("entre 30 segundos e 1 hora");
        assertThatThrownBy(() -> props.apply(java.util.Map.of("passo", Duration.ofSeconds(1)))).hasMessageContaining("entre 30 segundos e 1 hora");
        // Sem a chave, o módulo funciona como antes: nenhum propósito do aplicativo.
        assertThat(new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app", null).propositos()).isEmpty();
    }

    @Test
    void validadeDoAccessTemTeto() {
        assertThatThrownBy(() -> propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(SEGREDO, Duration.ofHours(2), Duration.ofHours(1), "app-de-teste", java.util.Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("access-ttl");
    }

    @Test
    void segredoDoJwtPrecisaTer32Bytes() {
        AuthProperties curto = propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt(Base64.getEncoder().encodeToString(new byte[16]), Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of()));
        assertThatThrownBy(() -> new JwtService(curto, new RelogioMutavel())).isInstanceOf(IllegalStateException.class);
        AuthProperties invalido = propriedades(new AuthProperties.Cookie("r", "/", null, true, "Strict"),
                new AuthProperties.Csrf("c", "h", "/", null, true, "Lax", false),
                new AuthProperties.Jwt("não é base64!", Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of()));
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
                        Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of()));
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
                new AuthProperties.Jwt(SEGREDO, Duration.ofMinutes(15), Duration.ofHours(1), "app-de-teste", java.util.Map.of()));
        PoliticaCsrf toda = new PoliticaCsrf(sempre, new CookieDeRefresh(sempre));
        assertThat(toda.exigido().matches(post)).isTrue();
        assertThat(toda.exigido().matches(get)).isFalse();
    }
}
