package br.com.plataforma.comum.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do módulo de autenticação ({@code plataforma.auth.*}). O módulo só liga com {@code habilitado=true}, para a
 * adoção ser gradual. Nada aqui tem segredo no código: o segredo do JWT e as chaves do MFA vêm do ambiente do sistema.
 *
 * @param habilitado liga o módulo (migrations, serviços e beans)
 */
@ConfigurationProperties(prefix = "plataforma.auth")
public record AuthProperties(
        @DefaultValue("false") boolean habilitado,
        @DefaultValue Jwt jwt,
        @DefaultValue Refresh refresh,
        @DefaultValue Cookie cookie,
        @DefaultValue Csrf csrf,
        @DefaultValue Login login,
        @DefaultValue Mfa mfa,
        @DefaultValue Migracao migracao
) {

    private static final Set<String> SAMESITE = Set.of("strict", "lax", "none");

    /**
     * @param secret         Base64 de pelo menos 32 bytes (exigido quando o módulo está habilitado)
     * @param accessTtl      validade do token de acesso (padrão e máximo aceito: 15 minutos a 1 hora)
     * @param suporteTtlMax  teto da validade do token de suporte
     * @param emissor        {@code iss} dos tokens, por aplicativo (ex.: {@code central}); exigido com o módulo ligado.
     *                       Um token de outro aplicativo que por engano use o mesmo segredo não vale aqui.
     * @param propositos     tokens de curta duração que só o aplicativo conhece (ex.: {@code tenant_selection}, o passo entre
     *                       a senha e a escolha da paróquia), com a validade de cada um. Os nomes {@code access} e
     *                       {@code suporte} são do módulo. Padrão: nenhum.
     */
    public record Jwt(String secret,
                      @DefaultValue("PT15M") Duration accessTtl,
                      @DefaultValue("PT1H") Duration suporteTtlMax,
                      String emissor,
                      @DefaultValue Map<String, Duration> propositos) {
        public Jwt {
            propositos = propositos == null ? Map.of() : Map.copyOf(propositos);
        }
    }

    /** @param ttl validade do refresh token (cada rotação começa um novo prazo) */
    public record Refresh(@DefaultValue("P7D") Duration ttl) {
    }

    /**
     * Cookie do refresh token. Sempre HttpOnly. {@code SameSite=None} exige {@code secure=true}.
     *
     * @param nome     nome do cookie, por aplicativo (ex.: {@code central_refresh_token})
     * @param path     caminho do cookie, o mesmo prefixo da rota de refresh que o front chama
     * @param dominio  domínio opcional
     * @param secure   só HTTPS (padrão: true; desligar só em desenvolvimento local)
     * @param sameSite {@code Strict}, {@code Lax} ou {@code None}
     */
    public record Cookie(@DefaultValue("refresh_token") String nome,
                         @DefaultValue("/api/auth") String path,
                         String dominio,
                         @DefaultValue("true") boolean secure,
                         @DefaultValue("Strict") String sameSite) {
    }

    /**
     * Cookie e cabeçalho do CSRF (legível pelo JavaScript do front, por isso não é HttpOnly).
     *
     * @param somenteComCookieDeRefresh só exige o token CSRF quando o cookie do refresh vem na requisição (as rotas com
     *                                  Bearer não dependem de cookie e não precisam dele)
     */
    public record Csrf(@DefaultValue("XSRF-TOKEN") String cookie,
                       @DefaultValue("X-XSRF-TOKEN") String header,
                       @DefaultValue("/") String path,
                       String dominio,
                       @DefaultValue("true") boolean secure,
                       @DefaultValue("Lax") String sameSite,
                       @DefaultValue("false") boolean somenteComCookieDeRefresh) {
    }

    /**
     * @param limiteIp           tentativas por IP na janela
     * @param limiteConta        tentativas por conta na janela
     * @param janelaSegundos     tamanho da janela
     * @param sal                sal do hash das chaves do contador, exigido com o módulo ligado e sem valor padrão. Não
     *                           deriva do segredo do JWT: trocar o segredo zeraria todos os contadores.
     */
    public record Login(@DefaultValue("60") int limiteIp,
                        @DefaultValue("10") int limiteConta,
                        @DefaultValue("600") long janelaSegundos,
                        String sal) {
    }

    /**
     * @param chaves     {@code id:base64,id2:base64}, cada chave com 32 bytes (AES-256); fora do banco
     * @param chaveAtiva id da chave que cifra os segredos novos
     * @param contexto   prefixo do dado autenticado do AES-GCM (por aplicativo): impede levar um segredo de um sistema
     *                   ou de um usuário para outro
     */
    public record Mfa(String chaves,
                      String chaveAtiva,
                      @DefaultValue("plataforma") String contexto) {
    }

    /**
     * Credencial de migração, quando o usuário do banco em execução não pode criar schema (padrão: o DataSource do sistema).
     *
     * @param papelApp papel do banco que a API usa em execução, quando difere de quem migra (separação migrador/app): ele
     *                 recebe uso do schema {@code plataforma} e SELECT/INSERT/UPDATE/DELETE nas tabelas, a cada subida. Vazio =
     *                 o mesmo usuário nos dois papéis, nada a conceder.
     */
    public record Migracao(String url, String user, String password, String papelApp) {
    }

    public AuthProperties {
        if (habilitado && (jwt.emissor() == null || jwt.emissor().isBlank())) {
            throw new IllegalArgumentException("plataforma.auth.jwt.emissor é obrigatório (um nome por aplicativo).");
        }
        if (habilitado && (login.sal() == null || login.sal().isBlank())) {
            throw new IllegalArgumentException("plataforma.auth.login.sal é obrigatório (segredo do ambiente, sem valor padrão).");
        }
        validarCookie("plataforma.auth.cookie", cookie.sameSite(), cookie.secure());
        validarCookie("plataforma.auth.csrf", csrf.sameSite(), csrf.secure());
        if (jwt.accessTtl().compareTo(Duration.ofSeconds(30)) < 0 || jwt.accessTtl().compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("plataforma.auth.jwt.access-ttl precisa ficar entre 30 segundos e 1 hora.");
        }
        jwt.propositos().forEach((nome, ttl) -> {
            if (!nome.matches("[a-z][a-z_]{0,31}") || JwtService.ACESSO.equals(nome) || JwtService.SUPORTE.equals(nome)) {
                throw new IllegalArgumentException("plataforma.auth.jwt.propositos: nome inválido ou reservado: " + nome);
            }
            if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0 || ttl.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("plataforma.auth.jwt.propositos." + nome + " precisa ficar entre 30 segundos e 1 hora.");
            }
        });
        if (jwt.suporteTtlMax().compareTo(Duration.ofHours(8)) > 0 || jwt.suporteTtlMax().isNegative() || jwt.suporteTtlMax().isZero()) {
            throw new IllegalArgumentException("plataforma.auth.jwt.suporte-ttl-max precisa ficar entre 1 segundo e 8 horas.");
        }
        if (migracao.papelApp() != null && !migracao.papelApp().isBlank() && !migracao.papelApp().matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("plataforma.auth.migracao.papel-app não é um nome de papel válido.");
        }
        if (refresh.ttl().isNegative() || refresh.ttl().isZero()) {
            throw new IllegalArgumentException("plataforma.auth.refresh.ttl precisa ser positivo.");
        }
    }

    private static void validarCookie(String prefixo, String sameSite, boolean secure) {
        if (sameSite == null || !SAMESITE.contains(sameSite.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(prefixo + ".same-site precisa ser Strict, Lax ou None.");
        }
        if ("none".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalArgumentException(prefixo + ": SameSite=None exige secure=true (o navegador recusa o cookie).");
        }
    }
}
