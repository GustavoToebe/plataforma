package br.com.plataforma.comum.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.plataforma.comum.web.UnauthorizedException;

/**
 * Refresh token opaco no schema {@code plataforma}: rotação a cada uso e, se um token já revogado aparece de novo, todas as
 * sessões do sujeito são revogadas.
 *
 * <p>Tudo que muda as sessões de um sujeito (rotação, reuso, encerramento) roda sob um {@code pg_advisory_xact_lock} por
 * sujeito, na mesma transação. Assim a revogação por reuso é confirmada junto com a decisão (o 401 sai depois do commit,
 * sem transação aninhada que travaria contra a própria transação) e duas rotações do mesmo sujeito nunca se cruzam.
 */
public class RefreshTokens {

    private static final String INVALIDA = "Sessão inválida. Entre de novo.";
    private static final String EXPIRADA = "Sessão expirada. Entre de novo.";

    /** Resultado de uma rotação, decidido dentro da transação. */
    enum Estado { OK, INVALIDO, REUTILIZADO, EXPIRADO, INATIVO }

    private record Linha(String sujeito, Instant expiraEm, boolean revogado) {
    }

    /** @param versao versão das credenciais do sujeito no momento da rotação */
    public record Rotacao(String sujeito, long versao, String refreshToken) {
    }

    private record Decisao(Estado estado, Rotacao rotacao) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final AuthProperties propriedades;
    private final UsuariosDaAutenticacao usuarios;
    private final Clock relogio;
    private final AtomicLong proximaLimpeza = new AtomicLong();

    public RefreshTokens(JdbcTemplate jdbc, PlatformTransactionManager gerenteDeTransacao, AuthProperties propriedades,
                         UsuariosDaAutenticacao usuarios, Clock relogio) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
        this.propriedades = propriedades;
        this.usuarios = usuarios;
        this.relogio = relogio;
    }

    /** Novo refresh token para o sujeito (login). Devolve o valor bruto; só o hash fica no banco. */
    public String emitir(String sujeito, String ip) {
        String bruto = inserir(sujeito, ip);
        limparSeNecessario();
        return bruto;
    }

    /**
     * Troca o token por um novo. Qualquer falha vira 401 depois do commit: token desconhecido, expirado, usuário inativo ou
     * reuso de token revogado (que revoga todas as sessões do sujeito antes de responder).
     */
    public Rotacao rotacionar(String bruto, String ip) {
        if (bruto == null || bruto.isBlank()) {
            throw new UnauthorizedException(INVALIDA);
        }
        Decisao decisao = transacao.execute(status -> decidir(TokenOpaco.hash(bruto), ip));
        return switch (decisao.estado()) {
            case OK -> decisao.rotacao();
            case EXPIRADO -> throw new UnauthorizedException(EXPIRADA);
            case INVALIDO, REUTILIZADO, INATIVO -> throw new UnauthorizedException(INVALIDA);
        };
    }

    /** Logout: revoga só este token. */
    public void revogar(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return;
        }
        jdbc.update("update plataforma.refresh_token set revogado_em = ? where token_hash = ? and revogado_em is null",
                agora(), TokenOpaco.hash(bruto));
    }

    /**
     * Troca de senha ou de MFA: apaga todas as sessões do sujeito. Apaga em vez de revogar porque um token revogado que
     * reaparece conta como reuso e derrubaria também a sessão nova.
     */
    public void encerrarTodas(String sujeito) {
        transacao.executeWithoutResult(status -> {
            travar(sujeito);
            jdbc.update("delete from plataforma.refresh_token where sujeito = ?", sujeito);
        });
    }

    private Decisao decidir(String hash, String ip) {
        Optional<String> dono = jdbc.query("select sujeito from plataforma.refresh_token where token_hash = ?",
                (rs, n) -> rs.getString(1), hash).stream().findFirst();
        if (dono.isEmpty()) {
            return new Decisao(Estado.INVALIDO, null);
        }
        String sujeito = dono.get();
        travar(sujeito);
        // Relê depois do lock: outra requisição pode ter rotacionado ou encerrado o token enquanto esta esperava.
        List<Linha> linhas = jdbc.query("select sujeito, expira_em, revogado_em from plataforma.refresh_token where token_hash = ?",
                (rs, n) -> new Linha(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3) != null), hash);
        if (linhas.isEmpty()) {
            return new Decisao(Estado.INVALIDO, null);
        }
        Linha atual = linhas.get(0);
        Instant agora = relogio.instant();
        if (atual.revogado()) {
            jdbc.update("update plataforma.refresh_token set revogado_em = ? where sujeito = ? and revogado_em is null",
                    Timestamp.from(agora), sujeito);
            return new Decisao(Estado.REUTILIZADO, null);
        }
        if (!atual.expiraEm().isAfter(agora)) {
            return new Decisao(Estado.EXPIRADO, null);
        }
        Optional<Long> versao = usuarios.versaoDeCredenciais(sujeito);
        if (versao.isEmpty()) {
            return new Decisao(Estado.INATIVO, null);
        }
        String novo = TokenOpaco.gerar();
        java.util.UUID novoId = jdbc.queryForObject("""
                insert into plataforma.refresh_token (sujeito, token_hash, expira_em, ip)
                values (?, ?, ?, ?) returning id""", java.util.UUID.class,
                sujeito, TokenOpaco.hash(novo), Timestamp.from(agora.plus(propriedades.refresh().ttl())), ip(ip));
        jdbc.update("update plataforma.refresh_token set revogado_em = ?, substituido_por = ? where token_hash = ?",
                Timestamp.from(agora), novoId, hash);
        return new Decisao(Estado.OK, new Rotacao(sujeito, versao.get(), novo));
    }

    private String inserir(String sujeito, String ip) {
        String bruto = TokenOpaco.gerar();
        jdbc.update("insert into plataforma.refresh_token (sujeito, token_hash, expira_em, ip) values (?, ?, ?, ?)",
                sujeito, TokenOpaco.hash(bruto), Timestamp.from(relogio.instant().plus(propriedades.refresh().ttl())), ip(ip));
        return bruto;
    }

    /** Trava, até o fim da transação, tudo que muda as sessões deste sujeito. */
    private void travar(String sujeito) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "plataforma.refresh:" + sujeito);
    }

    private Timestamp agora() {
        return Timestamp.from(relogio.instant());
    }

    private static String ip(String ip) {
        return ip == null ? null : ip.substring(0, Math.min(ip.length(), 64));
    }

    /** Apaga tokens vencidos há mais de um dia, no máximo uma vez por minuto por processo. */
    private void limparSeNecessario() {
        long agora = relogio.millis();
        long previsto = proximaLimpeza.get();
        if (agora >= previsto && proximaLimpeza.compareAndSet(previsto, agora + 60_000)) {
            jdbc.update("delete from plataforma.refresh_token where expira_em < ?",
                    Timestamp.from(Instant.ofEpochMilli(agora - 86_400_000L)));
        }
    }
}
