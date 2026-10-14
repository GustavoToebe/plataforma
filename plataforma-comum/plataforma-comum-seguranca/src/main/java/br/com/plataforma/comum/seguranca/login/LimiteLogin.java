package br.com.plataforma.comum.seguranca.login;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Limita tentativas de login por IP e por conta (e-mail normalizado) antes do BCrypt. A aplicação fornece o contador: em produção, deve persistir
 * as janelas entre réplicas e reinícios. Compromisso conhecido: como a conta é limitada pelo e-mail, quem erra a senha de outra pessoa 10
 * vezes a bloqueia pela janela (10 minutos); o limite por IP é maior para não punir NAT de igreja.
 */
public class LimiteLogin {
    private final ContadorJanelas contador;
    private final int porIp, porConta;
    private final long janelaMs;
    private final Clock clock;
    private final String sal;

    /** Contador em memória, para testes de unidade. */
    public LimiteLogin(int porIp, int porConta, long segundos, int maxChaves, Clock clock) {
        this(porIp, porConta, segundos, new ContadorEmMemoria(maxChaves), clock, UUID.randomUUID().toString());
    }

    public LimiteLogin(int porIp, int porConta, long segundos, ContadorJanelas contador, Clock clock, String sal) {
        if (porIp < 1 || porConta < 1 || segundos < 1 || segundos > 86400) {
            throw new IllegalArgumentException("Limites de login inválidos.");
        }
        this.porIp = porIp;
        this.porConta = porConta;
        this.janelaMs = segundos * 1000;
        this.contador = contador;
        this.clock = clock;
        this.sal = sal;
    }

    public void registrar(String ip, String email) {
        long agora = clock.millis();
        var a = contador.tentar(chave("ip", ip == null ? "desconhecido" : ip), agora, janelaMs);
        var b = contador.tentar(chave("conta", email == null ? "" : email.trim().toLowerCase(Locale.ROOT)), agora, janelaMs);
        boolean bloqueiaIp = a.tentativas() > porIp;
        boolean bloqueiaConta = b.tentativas() > porConta;
        if (bloqueiaIp || bloqueiaConta) {
            long ate = Math.max(bloqueiaIp ? a.ateMs() : agora, bloqueiaConta ? b.ateMs() : agora);
            throw new LimiteLoginException(Math.max(1, (ate - agora + 999) / 1000));
        }
    }

    private String chave(String tipo, String valor) {
        try {
            return tipo + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((sal + ":" + valor).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível.", e);
        }
    }
}
