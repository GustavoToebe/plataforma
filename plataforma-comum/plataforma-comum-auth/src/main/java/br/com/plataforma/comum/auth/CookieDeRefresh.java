package br.com.plataforma.comum.auth;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/** Cookie do refresh token: sempre HttpOnly; nome, caminho, domínio, Secure e SameSite vêm da configuração do aplicativo. */
public class CookieDeRefresh {

    private final AuthProperties.Cookie config;
    private final Duration validade;

    public CookieDeRefresh(AuthProperties propriedades) {
        this.config = propriedades.cookie();
        this.validade = propriedades.refresh().ttl();
    }

    public ResponseCookie criar(String refreshToken) {
        return base(refreshToken).maxAge(validade).build();
    }

    /** Cookie que apaga o do navegador (logout). */
    public ResponseCookie limpar() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> ler(HttpServletRequest requisicao) {
        Cookie[] cookies = requisicao.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (config.nome().equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    public boolean presente(HttpServletRequest requisicao) {
        return ler(requisicao).isPresent();
    }

    private ResponseCookie.ResponseCookieBuilder base(String valor) {
        ResponseCookie.ResponseCookieBuilder construtor = ResponseCookie.from(config.nome(), valor)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite(config.sameSite())
                .path(config.path());
        if (config.dominio() != null && !config.dominio().isBlank()) {
            construtor.domain(config.dominio());
        }
        return construtor;
    }
}
