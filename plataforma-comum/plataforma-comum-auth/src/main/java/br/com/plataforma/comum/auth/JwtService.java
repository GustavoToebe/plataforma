package br.com.plataforma.comum.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>Todo token leva {@code iss} (o emissor do aplicativo) e {@code exp}; na validação os dois são obrigatórios. O jjwt
 * aceitaria para sempre um token sem {@code exp}, e um token de outro aplicativo que use o mesmo segredo não vale aqui.
 */
public final class JwtService {

    public static final String ACESSO = "access";
    public static final String SUPORTE = "suporte";

    private static final String CLAIM_PROPOSITO = "purpose";
    private static final String CLAIM_VERSAO = "cv";
    private static final String CLAIM_OPERADOR = "op";
    private static final String CLAIM_MOTIVO = "motivo";
    private static final String RECUSADO = "Token recusado.";
    /** Nomes que o módulo controla; um claim extra do sistema nunca pode usá-los. */
    private static final Set<String> RESERVADOS = Set.of("sub", "purpose", "cv", "exp", "iat", "nbf", "iss", "aud", "jti", "op", "motivo");

    private final SecretKey chave;
    private final String emissor;
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
        if (config.emissor() == null || config.emissor().isBlank()) {
            throw new IllegalStateException("plataforma.auth.jwt.emissor é obrigatório.");
        }
        this.emissor = config.emissor();
    }

    /** Token de acesso comum. */
    public String emitirAcesso(String sujeito, long versao) {
        return emitir(ACESSO, sujeito, versao, config.accessTtl(), null, null, Map.of());
    }

    /**
     * Token de acesso com claims do sistema (tenant, papéis...). Os nomes {@code sub}, {@code purpose}, {@code cv}, {@code exp},
     * {@code iat} e afins são do módulo e recusados; os valores só podem ser texto, número, booleano ou lista de texto.
     */
    public String emitirAcesso(String sujeito, long versao, Map<String, ?> extras) {
        return emitir(ACESSO, sujeito, versao, config.accessTtl(), null, null, extras);
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
        return emitir(SUPORTE, sujeito, versao, ttl, operador, motivo, Map.of());
    }

    public long expiraEmSegundos() {
        return config.accessTtl().toSeconds();
    }

    /**
     * Confere assinatura, validade e propósito. Qualquer falha vira 401 com a mesma mensagem. Fica no pacote de propósito: o
     * caminho público é {@link Autenticador#autenticar}, que também compara o {@code cv} com a versão atual do usuário.
     */
    Token validar(String token, String proposito) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException(RECUSADO);
        }
        try {
            Claims claims = Jwts.parser().verifyWith(chave).requireIssuer(emissor)
                    .clock(() -> Date.from(relogio.instant())).build()
                    .parseSignedClaims(token).getPayload();
            if (claims.getExpiration() == null || !proposito.equals(claims.get(CLAIM_PROPOSITO))) {
                throw new UnauthorizedException(RECUSADO);
            }
            Object versao = claims.get(CLAIM_VERSAO);
            String sujeito = claims.getSubject();
            if (!(versao instanceof Number numero) || sujeito == null || sujeito.isBlank()) {
                throw new UnauthorizedException(RECUSADO);
            }
            Map<String, Object> extras = new HashMap<>();
            claims.forEach((nome, valor) -> {
                if (!RESERVADOS.contains(nome)) {
                    extras.put(nome, valor);
                }
            });
            return new Token(sujeito, numero.longValue(), proposito,
                    claims.get(CLAIM_OPERADOR, String.class), claims.get(CLAIM_MOTIVO, String.class), Map.copyOf(extras));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException(RECUSADO);
        }
    }

    private String emitir(String proposito, String sujeito, long versao, Duration ttl, String operador, String motivo,
                         Map<String, ?> extras) {
        if (sujeito == null || sujeito.isBlank() || sujeito.length() > 120) {
            throw new IllegalArgumentException("Sujeito inválido.");
        }
        Instant agora = relogio.instant();
        var construtor = Jwts.builder()
                .issuer(emissor)
                .subject(sujeito)
                .claim(CLAIM_PROPOSITO, proposito)
                .claim(CLAIM_VERSAO, versao)
                .issuedAt(Date.from(agora))
                .expiration(Date.from(agora.plus(ttl)));
        if (operador != null) {
            construtor.claim(CLAIM_OPERADOR, operador).claim(CLAIM_MOTIVO, motivo);
        }
        extras.forEach((nome, valor) -> construtor.claim(validarExtra(nome, valor), valor));
        return construtor.signWith(chave).compact();
    }

    private static String validarExtra(String nome, Object valor) {
        if (nome == null || nome.isBlank() || RESERVADOS.contains(nome)) {
            throw new IllegalArgumentException("Claim extra com nome reservado ou vazio: " + nome);
        }
        boolean simples = valor instanceof String || valor instanceof Number || valor instanceof Boolean;
        boolean listaDeTexto = valor instanceof Collection<?> itens && itens.stream().allMatch(String.class::isInstance);
        if (!simples && !listaDeTexto) {
            throw new IllegalArgumentException("Claim extra '" + nome + "' precisa ser texto, número, booleano ou lista de texto.");
        }
        return nome;
    }

    /** Conteúdo já verificado de um token. {@code operador} e {@code motivo} só existem no token de suporte; {@code extras} são os claims do sistema. */
    public record Token(String sujeito, long versao, String proposito, String operador, String motivo, Map<String, Object> extras) {
        public boolean suporte() {
            return SUPORTE.equals(proposito);
        }
    }
}
