package br.com.plataforma.comum.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.plataforma.comum.seguranca.Totp;

/**
 * Segundo fator (TOTP) e códigos de recuperação no schema {@code plataforma}, por sujeito. A senha atual e a auditoria ficam com
 * o sistema, que chama estes métodos depois de conferir a senha. Cada código TOTP só vale uma vez ({@code ultimo_passo} é
 * atualizado de forma atômica) e cada código de recuperação também. Ativar, desativar e renovar os códigos de recuperação encerram todas as sessões do sujeito.
 */
public class Mfa {

    private static final int CODIGOS_DE_RECUPERACAO = 10;
    private static final long PREPARACAO_SEGUNDOS = 600;

    /** @param configurado se o servidor tem chave de MFA (sem ela, ativar responde 503) */
    public record Status(boolean ativo, boolean configurado, int codigosRestantes) {
    }

    /** O segredo aparece uma vez, para o QR code do front; nunca no log. */
    public record Preparacao(String segredo, Instant expiraEm) {
        @Override
        public String toString() {
            return "Preparacao[segredo=***, expiraEm=" + expiraEm + "]";
        }
    }

    /** Códigos de recuperação em claro, mostrados uma vez. */
    public record Recuperacao(List<String> codigos) {
        @Override
        public String toString() {
            return "Recuperacao[codigos=***]";
        }
    }

    private record Estado(String segredo, String pendente, Instant pendenteAte) {
    }

    private record Ativo(String segredo, long ultimoPasso) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final MfaCifra cifra;
    private final RefreshTokens refresh;
    private final Clock relogio;
    private final SecureRandom aleatorio = new SecureRandom();

    Mfa(JdbcTemplate jdbc, PlatformTransactionManager gerenteDeTransacao, MfaCifra cifra, RefreshTokens refresh, Clock relogio) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
        this.cifra = cifra;
        this.refresh = refresh;
        this.relogio = relogio;
    }

    public boolean ativo(String sujeito) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from plataforma.mfa where sujeito = ? and segredo_cifrado is not null)",
                Boolean.class, sujeito));
    }

    public Status status(String sujeito) {
        Integer restantes = jdbc.queryForObject(
                "select count(*) from plataforma.mfa_recuperacao where sujeito = ? and usado_em is null", Integer.class, sujeito);
        return new Status(ativo(sujeito), cifra.configurada(), restantes == null ? 0 : restantes);
    }

    /** Gera um segredo pendente (vale 10 minutos); só vira o segredo do sujeito quando {@link #ativar} confirmar um código. */
    public Preparacao preparar(String sujeito) {
        return transacao.execute(status -> {
            travar(sujeito);
            if (ativo(sujeito)) {
                throw new MfaException(HttpStatus.CONFLICT, "MFA já está ativo.", "MFA_JA_ATIVO");
            }
            String segredo = Totp.novoSegredo();
            Instant ate = relogio.instant().plusSeconds(PREPARACAO_SEGUNDOS);
            jdbc.update("""
                    insert into plataforma.mfa (sujeito, pendente_cifrado, pendente_ate) values (?, ?, ?)
                    on conflict (sujeito) do update set pendente_cifrado = excluded.pendente_cifrado, pendente_ate = excluded.pendente_ate""",
                    sujeito, cifra.cifrar(sujeito, segredo), Timestamp.from(ate));
            return new Preparacao(segredo, ate);
        });
    }

    /** Confirma o código do aplicativo autenticador, liga o MFA, gera os códigos de recuperação e encerra as sessões. */
    public Recuperacao ativar(String sujeito, String codigo) {
        return transacao.execute(status -> {
            travar(sujeito);
            var linhas = jdbc.query("select segredo_cifrado, pendente_cifrado, pendente_ate from plataforma.mfa where sujeito = ?",
                    (rs, n) -> new Estado(rs.getString(1), rs.getString(2),
                            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()), sujeito);
            Instant agora = relogio.instant();
            if (linhas.isEmpty() || linhas.get(0).segredo() != null || linhas.get(0).pendente() == null
                    || !linhas.get(0).pendenteAte().isAfter(agora)) {
                throw new MfaException(HttpStatus.CONFLICT, "Prepare novamente a configuração do MFA.", "MFA_PREPARACAO_EXPIRADA");
            }
            long passo = Totp.verificar(cifra.decifrar(sujeito, linhas.get(0).pendente()), codigo, agora, -1);
            if (passo < 0) {
                throw MfaException.invalido();
            }
            jdbc.update("""
                    update plataforma.mfa set segredo_cifrado = pendente_cifrado, pendente_cifrado = null, pendente_ate = null,
                    ultimo_passo = ?, ativado_em = ? where sujeito = ?""", passo, Timestamp.from(agora), sujeito);
            Recuperacao recuperacao = gerarRecuperacao(sujeito);
            refresh.encerrarTodas(sujeito);
            return recuperacao;
        });
    }

    /**
     * Troca os códigos de recuperação por 10 novos, exigindo um código válido (do aplicativo ou de recuperação, que é consumido) e
     * encerrando as sessões, como {@link #ativar}. Os antigos, usados ou não, deixam de existir. MFA inativo é
     * {@code MFA_INATIVO}. Como em {@link #desativar}, o limite de tentativas é da rota do sistema.
     */
    public Recuperacao renovarRecuperacao(String sujeito, String codigo) {
        return transacao.execute(status -> {
            travar(sujeito);
            if (!ativo(sujeito)) {
                throw new MfaException(HttpStatus.CONFLICT, "MFA não está ativo.", "MFA_INATIVO");
            }
            verificarLogin(sujeito, codigo);
            Recuperacao recuperacao = gerarRecuperacao(sujeito);
            refresh.encerrarTodas(sujeito);
            return recuperacao;
        });
    }

    private Recuperacao gerarRecuperacao(String sujeito) {
        jdbc.update("delete from plataforma.mfa_recuperacao where sujeito = ?", sujeito);
        List<String> codigos = new ArrayList<>();
        for (int i = 0; i < CODIGOS_DE_RECUPERACAO; i++) {
            byte[] bytes = new byte[16];
            aleatorio.nextBytes(bytes);
            String bruto = HexFormat.of().formatHex(bytes);
            codigos.add(bruto);
            jdbc.update("insert into plataforma.mfa_recuperacao (sujeito, codigo_hash) values (?, ?)", sujeito, TokenOpaco.hash(bruto));
        }
        return new Recuperacao(List.copyOf(codigos));
    }

    /**
     * Desliga o MFA exigindo um código válido (do aplicativo ou de recuperação) e encerra as sessões. O módulo não limita as
     * tentativas aqui: a rota do sistema que chama este método passa pelo {@code LimiteLogin} (como o login), senão o código de
     * 6 dígitos pode ser adivinhado por quem já tem a sessão.
     */
    public void desativar(String sujeito, String codigo) {
        transacao.executeWithoutResult(status -> {
            travar(sujeito);
            if (!ativo(sujeito)) {
                throw new MfaException(HttpStatus.CONFLICT, "MFA não está ativo.", "MFA_INATIVO");
            }
            verificarLogin(sujeito, codigo);
            jdbc.update("delete from plataforma.mfa where sujeito = ?", sujeito);
            jdbc.update("delete from plataforma.mfa_recuperacao where sujeito = ?", sujeito);
            refresh.encerrarTodas(sujeito);
        });
    }

    /**
     * Segundo fator do login, depois da senha. Sem MFA ativo não faz nada. Com MFA, exige um código TOTP novo (nunca usado) ou
     * um código de recuperação ainda não usado; os dois são consumidos de forma atômica, então reaproveitar o mesmo código
     * numa requisição concorrente falha.
     */
    public void verificarLogin(String sujeito, String codigo) {
        var linhas = jdbc.query("select segredo_cifrado, ultimo_passo from plataforma.mfa where sujeito = ? and segredo_cifrado is not null",
                (rs, n) -> new Ativo(rs.getString(1), rs.getLong(2)), sujeito);
        if (linhas.isEmpty()) {
            return;
        }
        if (codigo == null || codigo.isBlank()) {
            throw MfaException.necessario();
        }
        String informado = codigo.trim();
        Timestamp agora = Timestamp.from(relogio.instant());
        if (informado.matches("[0-9a-f]{32}")) {
            if (jdbc.update("update plataforma.mfa_recuperacao set usado_em = ? where sujeito = ? and codigo_hash = ? and usado_em is null",
                    agora, sujeito, TokenOpaco.hash(informado)) == 1) {
                return;
            }
        } else {
            long passo = Totp.verificar(cifra.decifrar(sujeito, linhas.get(0).segredo()), informado, relogio.instant(),
                    linhas.get(0).ultimoPasso());
            if (passo >= 0 && jdbc.update("update plataforma.mfa set ultimo_passo = ? where sujeito = ? and ultimo_passo < ?",
                    passo, sujeito, passo) == 1) {
                return;
            }
        }
        throw MfaException.invalido();
    }

    private void travar(String sujeito) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "plataforma.mfa:" + sujeito);
    }
}
