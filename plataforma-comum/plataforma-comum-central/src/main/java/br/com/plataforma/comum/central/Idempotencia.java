package br.com.plataforma.comum.central;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.plataforma.comum.seguranca.HmacAssinatura;
import br.com.plataforma.comum.web.BadRequestException;
import br.com.plataforma.comum.web.ConflictException;

/**
 * Idempotência do provisionamento (contrato v1, seções 5.1 e 8): a mesma {@code Idempotency-Key} com o mesmo corpo devolve a
 * resposta gravada sem repetir o efeito; com outro corpo ou outro tipo é {@code 409 IDEMPOTENCY_KEY_CONFLITO}. Duas requisições
 * simultâneas com a mesma chave se serializam por um lock consultivo da transação, então a operação roda uma vez só. A operação
 * roda na mesma transação do registro: ou os dois persistem, ou nenhum. Efeitos externos (e-mail do convite, por exemplo) devem
 * acontecer só depois do commit e só quando {@link Resultado#repetido()} for falso.
 */
public class Idempotencia {

    /** Cabeçalho da resposta repetida (contrato 5.1). */
    public static final String CABECALHO_REPETIDO = "Idempotency-Replayed";

    /**
     * @param statusHttp status da resposta: o da operação quando ela rodou agora; {@code 200} na repetição (contrato 5.1)
     * @param resposta   corpo JSON da resposta (na repetição, o gravado, idêntico)
     * @param repetido   {@code true} quando veio do registro (a operação não rodou agora)
     */
    public record Resultado(int statusHttp, String resposta, boolean repetido) {
        public Resultado(int statusHttp, String resposta) {
            this(statusHttp, resposta, false);
        }

        /** A resposta HTTP pronta: status, JSON e, na repetição, {@code Idempotency-Replayed: true}. */
        public ResponseEntity<String> comoResposta() {
            ResponseEntity.BodyBuilder resposta = ResponseEntity.status(statusHttp).contentType(MediaType.APPLICATION_JSON);
            if (repetido) {
                resposta.header(CABECALHO_REPETIDO, "true");
            }
            return resposta.body(this.resposta);
        }
    }

    private static final String TABELA = MigracaoIntegracao.SCHEMA + ".integracao_operacao";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;

    Idempotencia(JdbcTemplate jdbc, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(transacoes);
    }

    /**
     * Provisionamento ({@code POST /integracao/v1/instancias}, contrato 5.1): a chave tem de ser o id da contratação, igual em
     * toda repetição. Outra chave é recusada (400) antes de qualquer efeito.
     */
    public Resultado provisionar(String chave, String contratacaoId, byte[] corpo, Supplier<Resultado> operacao) {
        if (chave == null || !chave.equals(contratacaoId)) {
            throw new BadRequestException("Idempotency-Key precisa ser o contratacaoId.", "DADOS_INVALIDOS");
        }
        return executar(chave, "PROVISIONAR", corpo, operacao);
    }

    public Resultado executar(String chave, String tipo, byte[] corpo, Supplier<Resultado> operacao) {
        if (chave == null || chave.isBlank() || chave.length() > 120 || !chave.matches("[A-Za-z0-9._:-]+")) {
            throw new BadRequestException("Idempotency-Key ausente ou inválida.", "DADOS_INVALIDOS");
        }
        String hash = HmacAssinatura.sha256Hex(corpo == null ? new byte[0] : corpo);
        return transacao.execute(status -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "plataforma.integracao_operacao:" + chave);
            List<Registro> existentes = jdbc.query(
                    "select tipo, hash_corpo, status_http, resposta from " + TABELA + " where idempotency_key = ?",
                    (rs, i) -> new Registro(rs.getString(1), rs.getString(2).trim(), rs.getInt(3), rs.getString(4)), chave);
            if (!existentes.isEmpty()) {
                Registro registro = existentes.get(0);
                if (!registro.tipo().equals(tipo) || !registro.hash().equals(hash)) {
                    throw new ConflictException("A Idempotency-Key já foi usada com outro conteúdo.", "IDEMPOTENCY_KEY_CONFLITO");
                }
                return new Resultado(200, registro.resposta(), true);
            }
            Resultado resultado = operacao.get();
            jdbc.update("insert into " + TABELA + " (idempotency_key, tipo, hash_corpo, status_http, resposta) values (?, ?, ?, ?, ?)",
                    chave, tipo, hash, resultado.statusHttp(), resultado.resposta());
            return new Resultado(resultado.statusHttp(), resultado.resposta(), false);
        });
    }

    private record Registro(String tipo, String hash, int status, String resposta) {
    }
}
