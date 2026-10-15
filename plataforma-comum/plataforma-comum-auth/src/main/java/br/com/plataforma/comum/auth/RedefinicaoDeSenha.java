package br.com.plataforma.comum.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.plataforma.comum.seguranca.login.ContadorJanelas;
import br.com.plataforma.comum.seguranca.login.LimiteLogin;

/**
 * "Esqueci a senha" e convite por link, para todos os apps. O módulo não conhece a tabela de usuários: o sistema passa, a cada
 * chamada, como achar a conta pelo e-mail ({@link #solicitar}) e como gravar o hash da senha ({@link TrocaDeSenha}); as rotas e as
 * telas também são do sistema.
 *
 * <ul>
 *   <li><b>Token</b> opaco de 256 bits, só o SHA-256 no banco ({@code plataforma.redefinicao_token}), uso único, prazo curto. Pedir
 *       um novo invalida os anteriores da mesma finalidade. O link leva o token no fragmento ({@code #token=...}).</li>
 *   <li><b>Sem enumeração</b>: {@link #solicitar} responde igual exista ou não a conta, sem esperar o envio, e uma falha de envio
 *       não chega ao chamador (só a classe da exceção vai para o log).</li>
 *   <li><b>Limites</b> por IP e por e-mail (pedido) e por IP e por conta (confirmação), no banco, com contadores próprios que não
 *       se misturam com os do login. O {@code LimiteLoginException} traz o {@code Retry-After}; o controller o põe no cabeçalho.</li>
 *   <li><b>MFA nunca é contornado</b>: com MFA ativo, {@link #redefinir} exige o código (do aplicativo ou de recuperação). O limite
 *       por conta (todos os links dela somados) trava a adivinhação do código de 6 dígitos.</li>
 *   <li>Redefinir sobe a versão das credenciais (a troca do sistema) e encerra todas as sessões; os access tokens antigos deixam de
 *       valer porque o {@code cv} deles não confere mais.</li>
 * </ul>
 */
public class RedefinicaoDeSenha {

    private static final Logger LOG = LoggerFactory.getLogger(RedefinicaoDeSenha.class);

    private static final String REDEFINICAO = "REDEFINICAO";
    private static final String CONVITE = "CONVITE";
    private static final Duration CONVITE_MAXIMO = Duration.ofDays(30);

    /** @param email endereço de envio, como o cadastro do sistema o guarda (não o texto digitado no formulário) */
    public record Conta(String sujeito, String email) {
        @Override
        public String toString() {
            return "Conta[***]";
        }
    }

    /** Grava o hash da nova senha e sobe a versão das credenciais do sujeito, na transação do módulo (se der erro, nada vale). */
    @FunctionalInterface
    public interface TrocaDeSenha {
        void trocar(String sujeito, String novoHash);
    }

    /**
     * Regra de senha do app (tamanho mínimo, "diferente do e-mail"...), chamada na transação com o sujeito e a senha em texto,
     * depois de conferir o link e antes do MFA e do hash. Lança a exceção do próprio app para recusar; o link continua valendo.
     */
    @FunctionalInterface
    public interface RegraDeSenha {
        void validar(String sujeito, String novaSenha);
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final RedefinicaoProperties propriedades;
    private final LimiteLogin limitePedido;
    private final LimiteLogin limiteConfirmacao;
    private final Mfa mfa;
    private final RefreshTokens refresh;
    private final UsuariosDaAutenticacao usuarios;
    private final PasswordEncoder senhas;
    private final EmailsDeAcesso emails;
    private final DespachoDeEmail despacho;
    private final Clock relogio;
    private final AtomicLong proximaLimpeza = new AtomicLong();

    RedefinicaoDeSenha(JdbcTemplate jdbc, PlatformTransactionManager gerenteDeTransacao, RedefinicaoProperties propriedades,
                       ContadorJanelas contador, String sal, Mfa mfa, RefreshTokens refresh, UsuariosDaAutenticacao usuarios,
                       PasswordEncoder senhas, EmailsDeAcesso emails, DespachoDeEmail despacho, Clock relogio) {
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(gerenteDeTransacao);
        this.propriedades = propriedades;
        // Chaves próprias (sal derivado): pedir a redefinição não gasta o limite de login de ninguém, nem o contrário.
        this.limitePedido = new LimiteLogin(propriedades.limiteIp(), propriedades.limiteEmail(), propriedades.janelaSegundos(),
                contador, relogio, sal + ":redefinicao-pedido");
        this.limiteConfirmacao = new LimiteLogin(propriedades.limiteIp(), propriedades.limiteConta(), propriedades.janelaSegundos(),
                contador, relogio, sal + ":redefinicao-confirmacao");
        this.mfa = mfa;
        this.refresh = refresh;
        this.usuarios = usuarios;
        this.senhas = senhas;
        this.emails = emails;
        this.despacho = despacho;
        this.relogio = relogio;
    }

    /**
     * Pedido de "esqueci a senha". Conta o pedido (IP e e-mail) <b>antes</b> de procurar a conta e volta sem dizer se ela existe: a
     * busca, a gravação do link e o envio rodam depois, no {@link DespachoDeEmail}. O sistema responde 202 sempre.
     *
     * @param email         o que a pessoa digitou (o módulo apara e põe em minúsculas)
     * @param ip            IP do cliente
     * @param buscarPorEmail acha a conta <b>ativa</b> pelo e-mail normalizado; vazio se não existe ou está inativa
     * @throws br.com.plataforma.comum.seguranca.login.LimiteLoginException se o IP ou o e-mail passou do limite
     */
    public void solicitar(String email, String ip, Function<String, Optional<Conta>> buscarPorEmail) {
        String alvo = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        limitePedido.registrar(ip, alvo);
        if (alvo.isEmpty() || alvo.length() > 254) {
            return;
        }
        try {
            despacho.despachar(() -> processar(alvo, buscarPorEmail));
        } catch (RuntimeException e) {
            LOG.warn("Pedido de redefinição de senha não despachado ({}).", e.getClass().getSimpleName());
        }
    }

    private void processar(String alvo, Function<String, Optional<Conta>> buscarPorEmail) {
        try {
            Optional<Conta> conta = buscarPorEmail.apply(alvo);
            if (conta.isEmpty()) {
                return;
            }
            String token = emitir(conta.get().sujeito(), REDEFINICAO, propriedades.prazo());
            emails.enviarRedefinicao(conta.get().email(), propriedades.urlBase() + "#token=" + token, propriedades.prazo());
        } catch (RuntimeException e) {
            // Só a classe: a mensagem pode ter endereço ou link. O chamador não fica sabendo (senão só contas existentes dariam erro).
            LOG.warn("Pedido de redefinição de senha não concluído ({}).", e.getClass().getSimpleName());
        }
    }

    /**
     * Token de convite para o sujeito, para o sistema montar o link e enviar com {@link EmailsDeAcesso#enviarConvite}. Vale o
     * mesmo {@link #redefinir} (quem recebe define a senha). Um convite novo invalida o anterior; um pedido de "esqueci a senha"
     * não. Participa da transação em curso, então o sistema deve enviar o e-mail depois do commit.
     *
     * @param prazo validade do convite (de 1 minuto a 30 dias)
     * @return o token bruto, que só existe aqui: o banco guarda o hash
     */
    public String emitirConvite(String sujeito, Duration prazo) {
        if (prazo == null || prazo.compareTo(Duration.ofMinutes(1)) < 0 || prazo.compareTo(CONVITE_MAXIMO) > 0) {
            throw new IllegalArgumentException("O prazo do convite precisa ficar entre 1 minuto e 30 dias.");
        }
        return emitir(sujeito, CONVITE, prazo);
    }

    private String emitir(String sujeito, String finalidade, Duration prazo) {
        if (sujeito == null || sujeito.isBlank() || sujeito.length() > 120) {
            throw new IllegalArgumentException("Sujeito inválido.");
        }
        String bruto = TokenOpaco.gerar();
        transacao.executeWithoutResult(status -> {
            travar(sujeito);
            jdbc.update("delete from plataforma.redefinicao_token where sujeito = ? and finalidade = ?", sujeito, finalidade);
            jdbc.update("insert into plataforma.redefinicao_token (sujeito, finalidade, token_hash, expira_em) values (?, ?, ?, ?)",
                    sujeito, finalidade, TokenOpaco.hash(bruto), Timestamp.from(relogio.instant().plus(prazo)));
        });
        limparSeNecessario();
        return bruto;
    }

    /**
     * Define a nova senha com o token do link (redefinição ou convite). Numa transação só: confere o link (uso único, prazo,
     * conta ativa), o código do MFA quando ele está ativo, grava a senha pela {@code troca} (que sobe a versão das credenciais) e
     * apaga os links do sujeito. Se o MFA falha, nada muda e o link continua valendo para outra tentativa (dentro do limite por
     * conta). Depois do commit, encerra todas as sessões. Chame <b>fora</b> de transação, como {@code entrar}: dentro de uma, o
     * "depois do commit" é o da transação de fora e as sessões só caem ali (os access tokens antigos já caem pelo {@code cv}).
     *
     * @param tokenBruto o que veio depois de {@code #token=}
     * @param novaSenha  a regra de tamanho e força é do sistema (o módulo só exige que exista)
     * @param codigoMfa  obrigatório quando a conta tem MFA ({@code MFA_NECESSARIO}); código errado é {@code MFA_CODIGO_INVALIDO}
     * @throws RedefinicaoException {@code LINK_INVALIDO} para link desconhecido, vencido, já usado ou de conta inativa
     * @throws br.com.plataforma.comum.seguranca.login.LimiteLoginException se o IP ou o link passou do limite
     */
    public void redefinir(String tokenBruto, String novaSenha, String codigoMfa, String ip, TrocaDeSenha troca) {
        redefinir(tokenBruto, novaSenha, codigoMfa, ip, (sujeito, senha) -> { }, troca);
    }

    /**
     * Como {@link #redefinir(String, String, String, String, TrocaDeSenha)}, com a {@code regra} de senha do app. O módulo só
     * garante o que o hash exige: no máximo 72 bytes ({@code SENHA_LONGA}, 400).
     */
    public void redefinir(String tokenBruto, String novaSenha, String codigoMfa, String ip, RegraDeSenha regra,
                          TrocaDeSenha troca) {
        if (tokenBruto == null || tokenBruto.isBlank() || tokenBruto.length() > 200) {
            throw RedefinicaoException.linkInvalido();
        }
        if (novaSenha == null || novaSenha.isBlank()) {
            throw RedefinicaoException.senhaObrigatoria();
        }
        if (novaSenha.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw RedefinicaoException.senhaLonga();
        }
        String hash = TokenOpaco.hash(tokenBruto.trim());
        List<String> encontrados = jdbc.queryForList("select sujeito from plataforma.redefinicao_token where token_hash = ?",
                String.class, hash);
        // O orçamento de tentativas é da conta, não do link: quem controla a caixa de e-mail pede links novos, e se cada um
        // trouxesse tentativas novas o código de 6 dígitos do MFA seria adivinhável aos poucos. Link desconhecido só gasta o IP.
        limiteConfirmacao.registrar(ip, encontrados.isEmpty() ? "t:" + hash : "s:" + encontrados.get(0));
        String sujeito = transacao.execute(status -> {
            if (encontrados.isEmpty()) {
                throw RedefinicaoException.linkInvalido();
            }
            String dono = encontrados.get(0);
            // Mesma trava de emitir(), tomada antes de qualquer trava de linha: dois pedidos do mesmo sujeito nunca se cruzam.
            travar(dono);
            List<Timestamp> validade = jdbc.queryForList(
                    "select expira_em from plataforma.redefinicao_token where token_hash = ? for update", Timestamp.class, hash);
            Instant agora = relogio.instant();
            if (validade.isEmpty() || !validade.get(0).toInstant().isAfter(agora) || usuarios.versaoDeCredenciais(dono).isEmpty()) {
                throw RedefinicaoException.linkInvalido();
            }
            // A regra de senha vem antes do MFA: recusar a senha nunca gasta código de recuperação nem tentativa do MFA.
            regra.validar(dono, novaSenha);
            mfa.verificarLogin(dono, codigoMfa);
            troca.trocar(dono, senhas.encode(novaSenha));
            jdbc.update("delete from plataforma.redefinicao_token where sujeito = ?", dono);
            return dono;
        });
        // Depois do commit, para o módulo enxergar a versão nova. Mesmo sem isto, o cv dos tokens antigos já não confere.
        refresh.encerrarTodas(sujeito);
    }

    private void travar(String sujeito) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "plataforma.redefinicao:" + sujeito);
    }

    /** Remove links vencidos, no máximo uma vez por minuto por processo. */
    private void limparSeNecessario() {
        long agora = relogio.millis();
        long previsto = proximaLimpeza.get();
        if (agora >= previsto && proximaLimpeza.compareAndSet(previsto, agora + 60_000)) {
            jdbc.update("delete from plataforma.redefinicao_token where expira_em < ?", Timestamp.from(Instant.ofEpochMilli(agora)));
        }
    }
}
