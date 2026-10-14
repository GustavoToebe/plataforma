package br.com.plataforma.comum.central;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.plataforma.comum.web.ApiException;
import br.com.plataforma.comum.web.BadRequestException;
import br.com.plataforma.comum.web.ConflictException;
import br.com.plataforma.comum.web.ResourceNotFoundException;

/**
 * Direitos da instância neste app (contrato v1, seções 4 e 5.3): o snapshot completo que a Central manda, aplicado só quando a
 * versão é maior que a local. Versão igual ou menor não é erro: não muda nada além de {@code confirmadoEm} (a Central confirmou
 * o estado) e responde {@code aplicado=false}. O acesso efetivo é {@code acessoLiberado E (agora − confirmadoEm) ≤ tolerância}
 * (72 h por padrão). Sem registro local, o acesso é negado (falha fechada).
 */
public class DireitosLocais {

    private static final Set<String> SITUACOES = Set.of("TRIAL", "ATIVA", "INADIMPLENTE", "BLOQUEADA", "CANCELADA");
    private static final String TABELA = MigracaoIntegracao.SCHEMA + ".direitos_locais";

    /**
     * Snapshot de {@code DireitosInstancia} reduzido ao que o app guarda.
     *
     * @param instanciaId     id desta instância no app (tenantId, consultoriaId...), em texto
     * @param limites         JSON do mapa {@code codigo → número}
     * @param funcionalidades JSON da lista de códigos
     */
    public record Direitos(String instanciaId, String contratacaoId, long versao, String situacao, boolean acessoLiberado,
                           String motivoBloqueio, LocalDate vigenteAte, String planoCodigo, String planoNome,
                           String limites, String funcionalidades) {
    }

    /** Estado guardado, com o instante da última confirmação da Central. */
    public record Estado(Direitos direitos, Instant confirmadoEm, Instant atualizadoEm) {
    }

    /** Resposta do webhook: se esta versão foi aplicada e qual é a versão local agora. */
    public record Aplicacao(boolean aplicado, long versaoAtual) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;
    private final Duration tolerancia;

    DireitosLocais(JdbcTemplate jdbc, PlatformTransactionManager transacoes, Clock relogio, Duration tolerancia) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(transacoes);
        this.relogio = relogio;
        this.tolerancia = tolerancia;
    }

    /** Grava os direitos de uma instância nova (provisionamento). Já existente é conflito, nunca sobrescrita. */
    public void inicializar(Direitos direitos) {
        validar(direitos);
        Instant agora = relogio.instant();
        transacao.executeWithoutResult(status -> {
            travar(direitos.instanciaId());
            if (!buscarLinha(direitos.instanciaId()).isEmpty()) {
                throw new ConflictException("A instância já tem direitos registrados.", "IDEMPOTENCY_KEY_CONFLITO");
            }
            jdbc.update("insert into " + TABELA + " (instancia_id, contratacao_id, versao, situacao, acesso_liberado, motivo_bloqueio, "
                            + "vigente_ate, plano_codigo, plano_nome, limites, funcionalidades, confirmado_em, atualizado_em) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    direitos.instanciaId(), direitos.contratacaoId(), direitos.versao(), direitos.situacao(), direitos.acessoLiberado(),
                    direitos.motivoBloqueio(), data(direitos.vigenteAte()), direitos.planoCodigo(), direitos.planoNome(),
                    direitos.limites(), direitos.funcionalidades(), Timestamp.from(agora), Timestamp.from(agora));
        });
    }

    /**
     * Webhook ({@code PUT .../direitos}). Versão maior é aplicada; versão igual só renova {@code confirmadoEm} (a Central confirmou
     * o estado); versão menor não muda nada (um webhook atrasado não pode estender a janela de acesso). Instância desconhecida:
     * 404 {@code INSTANCIA_NAO_ENCONTRADA}; {@code contratacaoId} diferente do gravado: 409 {@code IDEMPOTENCY_KEY_CONFLITO}.
     */
    public Aplicacao aplicar(Direitos direitos) {
        return aplicar(direitos, false);
    }

    private Aplicacao aplicar(Direitos direitos, boolean confirmarSempre) {
        validar(direitos);
        Instant agora = relogio.instant();
        return transacao.execute(status -> {
            travar(direitos.instanciaId());
            List<Estado> atuais = buscarLinha(direitos.instanciaId());
            if (atuais.isEmpty()) {
                throw new ResourceNotFoundException("Instância não encontrada.", "INSTANCIA_NAO_ENCONTRADA");
            }
            Direitos local = atuais.get(0).direitos();
            if (!local.contratacaoId().equals(direitos.contratacaoId())) {
                throw new ConflictException("A contratação informada difere da registrada para a instância.", "IDEMPOTENCY_KEY_CONFLITO");
            }
            if (direitos.versao() <= local.versao()) {
                if (direitos.versao() == local.versao() || confirmarSempre) {
                    jdbc.update("update " + TABELA + " set confirmado_em = ? where instancia_id = ?", Timestamp.from(agora), direitos.instanciaId());
                }
                return new Aplicacao(false, local.versao());
            }
            jdbc.update("update " + TABELA + " set versao = ?, situacao = ?, acesso_liberado = ?, motivo_bloqueio = ?, vigente_ate = ?, "
                            + "plano_codigo = ?, plano_nome = ?, limites = ?, funcionalidades = ?, confirmado_em = ?, atualizado_em = ? "
                            + "where instancia_id = ?",
                    direitos.versao(), direitos.situacao(), direitos.acessoLiberado(), direitos.motivoBloqueio(), data(direitos.vigenteAte()),
                    direitos.planoCodigo(), direitos.planoNome(), direitos.limites(), direitos.funcionalidades(),
                    Timestamp.from(agora), Timestamp.from(agora), direitos.instanciaId());
            return new Aplicacao(true, direitos.versao());
        });
    }

    /**
     * Resultado da sincronização.
     *
     * @param aplicadas   quantas versões novas foram aplicadas
     * @param desconhecidas instâncias listadas pela Central que o app não tem (conciliação: o chamador alerta, nada é aplicado)
     * @param falhas      instâncias com conflito ou dado inválido (o chamador alerta; as demais seguem sendo confirmadas)
     */
    public record Sincronizacao(int aplicadas, List<String> desconhecidas, List<String> falhas) {
    }

    /**
     * Sincronização (contrato 6.1): aplica as de versão maior e confirma todas as presentes, mesmo com versão igual ou menor. Um
     * item ruim não interrompe os seguintes: se interrompesse, a repetição a cada 30 minutos cairia sempre no mesmo item e, às 72 h,
     * todos os que vêm depois dele perderiam o acesso. Instâncias que o app tem e a lista não trouxe ficam como estão (o contrato
     * manda só alertar, nunca bloquear sozinho).
     */
    public Sincronizacao sincronizar(List<Direitos> lista) {
        int aplicadas = 0;
        List<String> desconhecidas = new ArrayList<>();
        List<String> falhas = new ArrayList<>();
        for (Direitos direitos : lista) {
            try {
                if (aplicar(direitos, true).aplicado()) {
                    aplicadas++;
                }
            } catch (ResourceNotFoundException e) {
                desconhecidas.add(String.valueOf(direitos.instanciaId()));
            } catch (ApiException e) {
                falhas.add(String.valueOf(direitos.instanciaId()));
            }
        }
        return new Sincronizacao(aplicadas, List.copyOf(desconhecidas), List.copyOf(falhas));
    }

    public Optional<Estado> buscar(String instanciaId) {
        return buscarLinha(instanciaId).stream().findFirst();
    }

    /** {@code acessoLiberado E (agora − confirmadoEm) ≤ tolerância}; sem registro, {@code false}. */
    public boolean acessoEfetivo(String instanciaId) {
        return buscar(instanciaId)
                .map(estado -> estado.direitos().acessoLiberado()
                        && Duration.between(estado.confirmadoEm(), relogio.instant()).compareTo(tolerancia) <= 0)
                .orElse(false);
    }

    private List<Estado> buscarLinha(String instanciaId) {
        return jdbc.query("select instancia_id, contratacao_id, versao, situacao, acesso_liberado, motivo_bloqueio, vigente_ate, "
                        + "plano_codigo, plano_nome, limites, funcionalidades, confirmado_em, atualizado_em from " + TABELA
                        + " where instancia_id = ?",
                (rs, i) -> {
                    Date vigente = rs.getDate(7);
                    return new Estado(new Direitos(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getBoolean(5),
                            rs.getString(6), vigente == null ? null : vigente.toLocalDate(), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getString(11)),
                            rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant());
                }, instanciaId);
    }

    private void travar(String instanciaId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "plataforma.direitos_locais:" + instanciaId);
    }

    private static Date data(LocalDate valor) {
        return valor == null ? null : Date.valueOf(valor);
    }

    private static void validar(Direitos d) {
        if (d.instanciaId() == null || d.instanciaId().isBlank() || d.instanciaId().length() > 120
                || d.contratacaoId() == null || d.contratacaoId().isBlank() || d.contratacaoId().length() > 120
                || d.versao() < 0 || !SITUACOES.contains(d.situacao())
                || d.limites() == null || d.funcionalidades() == null) {
            throw new BadRequestException("Direitos da instância inválidos.", "DADOS_INVALIDOS");
        }
    }
}
