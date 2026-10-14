package br.com.plataforma.comum.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;

import br.com.plataforma.comum.seguranca.login.ContadorJanelas;

/**
 * Contador de tentativas de login no banco ({@code plataforma.login_tentativa}): vale entre réplicas e sobrevive a reinício. Uma
 * instrução só conta e lê, então tentativas concorrentes não passam do limite. A chave já chega como hash (nenhum e-mail nem IP
 * é gravado em claro).
 */
class ContadorBanco implements ContadorJanelas {

    private static final String SQL = """
            insert into plataforma.login_tentativa (chave, janela_ate, tentativas) values (?, ?, 1)
            on conflict (chave) do update set
              tentativas = case when plataforma.login_tentativa.janela_ate <= ? then 1 else plataforma.login_tentativa.tentativas + 1 end,
              janela_ate = case when plataforma.login_tentativa.janela_ate <= ? then ? else plataforma.login_tentativa.janela_ate end
            returning tentativas, janela_ate
            """;

    private final JdbcTemplate jdbc;
    private final AtomicLong proximaLimpeza = new AtomicLong();

    ContadorBanco(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Estado tentar(String chave, long agoraMs, long janelaMs) {
        limparSeNecessario(agoraMs);
        Timestamp agora = Timestamp.from(Instant.ofEpochMilli(agoraMs));
        Timestamp fim = Timestamp.from(Instant.ofEpochMilli(agoraMs + janelaMs));
        return jdbc.queryForObject(SQL, (rs, n) -> new Estado(rs.getInt(1), rs.getTimestamp(2).getTime()),
                chave, fim, agora, agora, fim);
    }

    /** Remove janelas vencidas há mais de uma hora, no máximo uma vez por minuto por processo. */
    private void limparSeNecessario(long agoraMs) {
        long previsto = proximaLimpeza.get();
        if (agoraMs >= previsto && proximaLimpeza.compareAndSet(previsto, agoraMs + 60_000)) {
            jdbc.update("delete from plataforma.login_tentativa where janela_ate < ?",
                    Timestamp.from(Instant.ofEpochMilli(agoraMs - 3_600_000)));
        }
    }
}
