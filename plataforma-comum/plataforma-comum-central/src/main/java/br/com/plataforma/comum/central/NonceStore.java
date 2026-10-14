package br.com.plataforma.comum.central;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nonces vistos (contrato, seção 2.4, passo 4): a chave primária impede o replay. Grava em transação própria, para persistir
 * mesmo que o handler falhe ou que o chamador esteja dentro de outra transação. A limpeza (nonces com mais de 10 minutos, o
 * dobro da janela) roda de passagem, no máximo uma vez por minuto por instância.
 */
class NonceStore {

    static final Duration RETENCAO = Duration.ofMinutes(10);
    private static final long INTERVALO_LIMPEZA_MS = 60_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final AtomicLong ultimaLimpeza = new AtomicLong();

    NonceStore(JdbcTemplate jdbc, PlatformTransactionManager transacoes, Clock relogio) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.relogio = relogio;
    }

    /** @return {@code true} se o nonce era novo; {@code false} se já tinha sido usado com essa chave */
    boolean registrar(String chaveId, String nonce) {
        Instant agora = relogio.instant();
        Integer inseridas = transacao.execute(status -> jdbc.update(
                "insert into " + MigracaoIntegracao.SCHEMA + ".integracao_nonce (chave_id, nonce, recebido_em) values (?, ?, ?) "
                        + "on conflict do nothing", chaveId, nonce, Timestamp.from(agora)));
        limparSeNecessario(agora);
        return inseridas != null && inseridas == 1;
    }

    /** Apaga o vencido; chamado pelo job de limpeza do sistema se ele preferir agendar. */
    int limpar() {
        Instant limite = relogio.instant().minus(RETENCAO);
        Integer apagadas = transacao.execute(status -> jdbc.update(
                "delete from " + MigracaoIntegracao.SCHEMA + ".integracao_nonce where recebido_em < ?", Timestamp.from(limite)));
        return apagadas == null ? 0 : apagadas;
    }

    private void limparSeNecessario(Instant agora) {
        long anterior = ultimaLimpeza.get();
        long atual = agora.toEpochMilli();
        if (atual - anterior >= INTERVALO_LIMPEZA_MS && ultimaLimpeza.compareAndSet(anterior, atual)) {
            limpar();
        }
    }
}
