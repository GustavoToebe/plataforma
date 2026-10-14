package br.com.plataforma.comum.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import br.com.plataforma.comum.web.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT HMAC do aplicativo. Todo token leva {@code sub}, {@code purpose} e {@code cv} (versão das credenciais do usuário: trocar
 * a senha ou o MFA sobe a versão e invalida os tokens já emitidos). Quem valida diz qual propósito aceita; um token de um
 * propósito nunca serve para outro. Este serviço só confere assinatura, propósito e validade: a comparação do {@code cv} com o
 * banco é feita por {@link Autenticador#autenticar}, que é o caminho que os sistemas devem usar.
 */
public final class JwtService {

    public static final String ACESSO = "access";
    public static final String SUPORTE = "suporte";

    private static final String CLAIM_PROPOSITO = "purpose";
    private static final String CLAIM_VERSAO = "cv";
    private static final String CLAIM_OPERADOR = "op";
    private static final String CLAIM_MOTIVO = "motivo";
    private static final String RECUSADO = "Token recusado.";

    private final SecretKey chave;
    private final AuthProperties.Jwt config;
    private final Clock relogio;

    public JwtService(AuthProperties propriedades, Clock relogio) {
        this.config = propriedades.jwt();
        this.relogio = relogio;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(config.secret() == null ? "" : config.secret());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("plataforma.auth.jwt.secret precisa ser Base64 válido.", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("plataforma.auth.jwt.secret precisa decodificar para pelo menos 32 bytes.");
        }
        this.chave = Keys.hmacShaKeyFor(bytes);
    }

    /** Token de acesso comum. */
    public String emitirAcesso(String sujeito, long versao) {
        return emitir(ACESSO, sujeito, versao, config.accessTtl(), null, null);
    }

    /**
     * Token de sessão de suporte: acesso de um operador da plataforma como o sujeito, com prazo curto (limitado por
     * {@code suporte-ttl-max}). O operador e o motivo vão no token para a auditoria do sistema.
     */
    public String emitirSuporte(String sujeito, long versao, String operador, String motivo, Duration ttl) {
        if (operador == null || operador.isBlank() || motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("Sessão de suporte exige operador e motivo.");
        }
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(config.suporteTtlMax()) > 0) {
            throw new IllegalArgumentException("Prazo da sessão de suporte fora do limite.");
        }
        return emitir(SUPORTE, sujeito, versao, ttl, operador, motivo);
    }

    public long expiraEmSegundos() {
        return config.accessTtl().toSeconds();
    }

    /** Confere assinatura, validade e propósito. Qualquer falha vira 401 com a mesma mensagem. */
    public Token validar(String token, String proposito) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException(RECUSADO);
        }
        try {
            Claims claims = Jwts.parser().verifyWith(chave).clock(() -> Date.from(relogio.instant())).build()
                    .parseSignedClaims(token).getPayload();
            if (!proposito.equals(claims.get(CLAIM_PROPOSITO))) {
                throw new UnauthorizedException(RECUSADO);
            }
            Object versao = claims.get(CLAIM_VERSAO);
            String sujeito = claims.getSubject();
            if (!(versao instanceof Number numero) || sujeito == null || sujeito.isBlank()) {
                throw new UnauthorizedException(RECUSADO);
            }
            return new Token(sujeito, numero.longValue(), proposito,
                    claims.get(CLAIM_OPERADOR, String.class), claims.get(CLAIM_MOTIVO, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(RECUSADO);
        }
    }

    private String emitir(String proposito, String sujeito, long versao, Duration ttl, String operador, String motivo) {
        if (sujeito == null || sujeito.isBlank() || sujeito.length() > 120) {
            throw new IllegalArgumentException("Sujeito inválido.");
        }
        Instant agora = relogio.instant();
        var construtor = Jwts.builder()
                .subject(sujeito)
                .claim(CLAIM_PROPOSITO, proposito)
                .claim(CLAIM_VERSAO, versao)
                .issuedAt(Date.from(agora))
                .expiration(Date.from(agora.plus(ttl)));
        if (operador != null) {
            construtor.claim(CLAIM_OPERADOR, operador).claim(CLAIM_MOTIVO, motivo);
        }
        return construtor.signWith(chave).compact();
    }

    /** Conteúdo já verificado de um token. {@code operador} e {@code motivo} só existem no token de suporte. */
    public record Token(String sujeito, long versao, String proposito, String operador, String motivo) {
        public boolean suporte() {
            return SUPORTE.equals(proposito);
        }
    }
}
