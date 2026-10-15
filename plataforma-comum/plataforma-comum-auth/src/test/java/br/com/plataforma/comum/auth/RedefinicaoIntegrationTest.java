package br.com.plataforma.comum.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.plataforma.comum.seguranca.login.LimiteLoginException;
import br.com.plataforma.comum.teste.PostgresDeTeste;
import br.com.plataforma.comum.web.UnauthorizedException;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = {AppDeTeste.class, RedefinicaoIntegrationTest.EmailDeTeste.class}, properties = {
        "plataforma.auth.habilitado=true",
        "plataforma.auth.jwt.secret=" + AuthIntegrationTest.SEGREDO,
        "plataforma.auth.mfa.chaves=k1:" + AuthIntegrationTest.SEGREDO,
        "plataforma.auth.mfa.chave-ativa=k1",
        "plataforma.auth.mfa.contexto=teste",
        "plataforma.auth.login.sal=sal-do-teste",
        "plataforma.auth.jwt.emissor=app-de-teste",
        "plataforma.auth.email.habilitado=true",
        "plataforma.auth.email.remetente=App de Teste <contato@exemplo.test>",
        "plataforma.auth.email.nome-app=App de Teste",
        "plataforma.auth.redefinicao.habilitado=true",
        "plataforma.auth.redefinicao.url-base=https://app.exemplo.test/redefinir-senha",
        "plataforma.auth.redefinicao.limite-ip=1000",
        "plataforma.auth.redefinicao.limite-email=3",
        "plataforma.auth.redefinicao.limite-conta=4",
        "spring.main.banner-mode=off"
})
class RedefinicaoIntegrationTest {

    static final PostgreSQLContainer POSTGRES = PostgresDeTeste.iniciar("redefinicao_test", "redefinicao_test", "redefinicao_test");
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        PostgresDeTeste.registrar(registry, POSTGRES);
    }

    /** O e-mail do "sistema": captura as mensagens, pode falhar de propósito e roda o despacho na hora (ou guarda para depois). */
    @Configuration(proxyBeanMethods = false)
    static class EmailDeTeste {
        static final List<EnviadorDeEmail.Mensagem> ENVIADAS = new CopyOnWriteArrayList<>();
        static final AtomicBoolean FALHAR = new AtomicBoolean();
        static final AtomicBoolean ADIAR = new AtomicBoolean();
        static final List<Runnable> ADIADAS = new CopyOnWriteArrayList<>();

        @Bean
        EnviadorDeEmail enviador() {
            return mensagem -> {
                if (FALHAR.get()) {
                    throw new EnvioDeEmailException("falhou para ana@x.com em https://app.exemplo.test/redefinir-senha#token=abc");
                }
                ENVIADAS.add(mensagem);
            };
        }

        @Bean
        DespachoDeEmail despacho() {
            return tarefa -> {
                if (ADIAR.get()) {
                    ADIADAS.add(tarefa);
                } else {
                    tarefa.run();
                }
            };
        }
    }

    @Autowired RedefinicaoDeSenha redefinicao;
    @Autowired Autenticador autenticador;
    @Autowired Mfa mfa;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder senhas;

    private final AtomicInteger trocas = new AtomicInteger();

    @BeforeEach
    void limpar() {
        jdbc.update("delete from plataforma.redefinicao_token");
        jdbc.update("delete from plataforma.refresh_token");
        jdbc.update("delete from plataforma.login_tentativa");
        jdbc.update("delete from plataforma.mfa");
        jdbc.update("delete from plataforma.mfa_recuperacao");
        AppDeTeste.USUARIOS.clear();
        EmailDeTeste.ENVIADAS.clear();
        EmailDeTeste.ADIADAS.clear();
        EmailDeTeste.FALHAR.set(false);
        EmailDeTeste.ADIAR.set(false);
        AppDeTeste.USUARIOS.put("ana@x.com", new AppDeTeste.Usuario("ana", senhas.encode("senha-antiga-da-ana"), true, 1));
        AppDeTeste.USUARIOS.put("bia@x.com", new AppDeTeste.Usuario("bia", senhas.encode("senha-da-bia"), true, 1));
    }

    private static Optional<RedefinicaoDeSenha.Conta> buscar(String email) {
        AppDeTeste.Usuario u = AppDeTeste.USUARIOS.get(email);
        return u == null || !u.ativo() ? Optional.empty() : Optional.of(new RedefinicaoDeSenha.Conta(u.sujeito(), email));
    }

    /** O que o sistema faz: grava o hash e sobe a versão das credenciais. */
    private RedefinicaoDeSenha.TrocaDeSenha troca() {
        return (sujeito, hash) -> {
            trocas.incrementAndGet();
            AppDeTeste.USUARIOS.replaceAll((login, u) ->
                    u.sujeito().equals(sujeito) ? new AppDeTeste.Usuario(sujeito, hash, u.ativo(), u.versao() + 1) : u);
        };
    }

    private String ultimoToken() {
        assertThat(EmailDeTeste.ENVIADAS).isNotEmpty();
        Matcher m = TOKEN.matcher(EmailDeTeste.ENVIADAS.get(EmailDeTeste.ENVIADAS.size() - 1).texto());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private void redefinir(String token, String senha, String mfaCodigo) {
        redefinicao.redefinir(token, senha, mfaCodigo, "10.0.0.1", troca());
    }

    @Test
    void pedidoGravaSoOHashEMandaSoOLinkComOTokenNoFragmento(CapturedOutput saida) {
        redefinicao.solicitar("  ANA@x.com ", "10.0.0.1", RedefinicaoIntegrationTest::buscar);

        assertThat(EmailDeTeste.ENVIADAS).hasSize(1);
        EnviadorDeEmail.Mensagem mensagem = EmailDeTeste.ENVIADAS.get(0);
        String token = ultimoToken();
        assertThat(mensagem.para()).isEqualTo("ana@x.com");
        assertThat(mensagem.texto()).contains("https://app.exemplo.test/redefinir-senha#token=" + token).contains("30 minutos");
        assertThat(mensagem.html()).contains("href=\"https://app.exemplo.test/redefinir-senha#token=" + token + "\"");
        // Nada além do nome do app: nenhum nome de pessoa, nem o sujeito.
        assertThat(mensagem.texto() + mensagem.html() + mensagem.assunto()).doesNotContain("ana").contains("App de Teste");

        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token where token_hash = ?", Integer.class, token)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token where token_hash = ? and finalidade = 'REDEFINICAO'",
                Integer.class, TokenOpaco.hash(token))).isEqualTo(1);
        assertThat(saida.getAll()).doesNotContain("ana@x.com").doesNotContain(token);
    }

    @Test
    void contaInexistenteOuInativaRespondeIgualENadaEhEnviado() {
        AppDeTeste.USUARIOS.put("off@x.com", new AppDeTeste.Usuario("off", senhas.encode("x"), false, 1));

        redefinicao.solicitar("ninguem@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        redefinicao.solicitar("off@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        redefinicao.solicitar("   ", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        redefinicao.solicitar(null, "10.0.0.1", RedefinicaoIntegrationTest::buscar);

        assertThat(EmailDeTeste.ENVIADAS).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token", Integer.class)).isZero();
    }

    @Test
    void oPedidoNaoFazNadaAteODespachoRodarEPortantoOTempoNaoRevelaAConta() {
        EmailDeTeste.ADIAR.set(true);

        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        redefinicao.solicitar("ninguem@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);

        // As duas voltaram sem tocar na conta, no banco nem no provedor: a diferença só aparece no despacho.
        assertThat(EmailDeTeste.ADIADAS).hasSize(2);
        assertThat(EmailDeTeste.ENVIADAS).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token", Integer.class)).isZero();
        EmailDeTeste.ADIADAS.forEach(Runnable::run);
        assertThat(EmailDeTeste.ENVIADAS).hasSize(1);
    }

    @Test
    void falhaDoProvedorNaoChegaAoChamadorEONaoVazaEnderecoNemLink(CapturedOutput saida) {
        EmailDeTeste.FALHAR.set(true);

        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);

        assertThat(saida.getAll()).contains("EnvioDeEmailException").doesNotContain("ana@x.com").doesNotContain("#token=")
                .doesNotContain("app.exemplo.test");
    }

    @Test
    void pedirDeNovoInvalidaOLinkAnterior() {
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String primeiro = ultimoToken();
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String segundo = ultimoToken();

        assertThat(segundo).isNotEqualTo(primeiro);
        assertThatThrownBy(() -> redefinir(primeiro, "nova-senha-1", null))
                .isInstanceOfSatisfying(RedefinicaoException.class, e -> assertThat(e.getCodigo()).isEqualTo("LINK_INVALIDO"));
        redefinir(segundo, "nova-senha-2", null);
        assertThat(autenticador.entrar("ana@x.com", "nova-senha-2", null, "10.0.0.2").sujeito()).isEqualTo("ana");
    }

    @Test
    void redefinirTrocaASenhaEncerraAsSessoesEOsAccessTokensAntigosDeixamDeValer(CapturedOutput saida) {
        Autenticador.Sessao antiga = autenticador.entrar("ana@x.com", "senha-antiga-da-ana", null, "10.0.0.2");
        Autenticador.Sessao outraBia = autenticador.entrar("bia@x.com", "senha-da-bia", null, "10.0.0.3");
        assertThat(autenticador.autenticar(antiga.accessToken()).sujeito()).isEqualTo("ana");
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();

        redefinir(token, "senha-nova-da-ana", null);

        assertThat(trocas).hasValue(1);
        assertThatThrownBy(() -> autenticador.autenticar(antiga.accessToken())).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> autenticador.renovar(antiga.refreshToken(), "10.0.0.2")).isInstanceOf(UnauthorizedException.class);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token where sujeito = 'ana'", Integer.class)).isZero();
        // Só a conta da redefinição: a outra segue logada.
        assertThat(autenticador.autenticar(outraBia.accessToken()).sujeito()).isEqualTo("bia");
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "senha-antiga-da-ana", null, "10.0.0.2")).isInstanceOf(UnauthorizedException.class);
        assertThat(autenticador.entrar("ana@x.com", "senha-nova-da-ana", null, "10.0.0.2").sujeito()).isEqualTo("ana");
        // Uso único: o mesmo link não vale de novo, e os links do sujeito sumiram.
        assertThatThrownBy(() -> redefinir(token, "outra-senha", null)).isInstanceOf(RedefinicaoException.class);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token", Integer.class)).isZero();
        assertThat(saida.getAll()).doesNotContain(token).doesNotContain("senha-nova-da-ana");
    }

    @Test
    void linkVencidoDesconhecidoOuDeContaInativaTemOMesmoErro() {
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();

        for (String invalido : new String[] {"nao-existe", "", "   ", null}) {
            assertThatThrownBy(() -> redefinir(invalido, "nova-senha-1", null))
                    .isInstanceOfSatisfying(RedefinicaoException.class, e -> assertThat(e.getCodigo()).isEqualTo("LINK_INVALIDO"));
        }
        AppDeTeste.USUARIOS.put("ana@x.com", new AppDeTeste.Usuario("ana", "x", false, 1));
        assertThatThrownBy(() -> redefinir(token, "nova-senha-1", null))
                .isInstanceOfSatisfying(RedefinicaoException.class, e -> assertThat(e.getCodigo()).isEqualTo("LINK_INVALIDO"));
        AppDeTeste.USUARIOS.put("ana@x.com", new AppDeTeste.Usuario("ana", "x", true, 1));

        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(31));
        assertThatThrownBy(() -> redefinir(token, "nova-senha-1", null))
                .isInstanceOfSatisfying(RedefinicaoException.class, e -> assertThat(e.getCodigo()).isEqualTo("LINK_INVALIDO"));
        assertThat(trocas).hasValue(0);
    }

    @Test
    void semNovaSenhaOuComTrocaQueFalhaOLinkContinuaValendo() {
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();

        assertThatThrownBy(() -> redefinir(token, " ", null))
                .isInstanceOfSatisfying(RedefinicaoException.class, e -> assertThat(e.getCodigo()).isEqualTo("SENHA_OBRIGATORIA"));
        assertThatThrownBy(() -> redefinicao.redefinir(token, "nova-senha-1", null, "10.0.0.1", (s, h) -> {
            throw new IllegalStateException("banco do sistema caiu");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token", Integer.class)).isEqualTo(1);

        redefinir(token, "nova-senha-1", null);
        assertThat(trocas).hasValue(1);
    }

    @Test
    void senhaAcimaDe72BytesDa400EOLinkContinuaValendo() {
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();
        // 37 letras "é" = 74 bytes em UTF-8 (37 caracteres): o hash recusaria, e a recusa tem que ser 400, não 500.
        String longa = "é".repeat(37);

        assertThatThrownBy(() -> redefinir(token, longa, null))
                .isInstanceOfSatisfying(RedefinicaoException.class, e -> assertThat(e.getCodigo()).isEqualTo("SENHA_LONGA"));
        assertThat(trocas).hasValue(0);
        redefinir(token, "nova-senha-1", null);
        assertThat(trocas).hasValue(1);
    }

    @Test
    void regraDeSenhaDoAppRecusaSemGastarOCodigoDeRecuperacaoNemOLink() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        Mfa.Recuperacao recuperacao = mfa.ativar("ana", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();
        String codigo = recuperacao.codigos().get(0);

        assertThatThrownBy(() -> redefinicao.redefinir(token, "curta", codigo, "10.0.0.1", (sujeito, senha) -> {
            assertThat(sujeito).isEqualTo("ana");
            throw new IllegalArgumentException("A senha precisa ter pelo menos 8 caracteres.");
        }, troca())).isInstanceOf(IllegalArgumentException.class);
        // A troca falhar depois do MFA também desfaz o consumo do código de recuperação.
        assertThatThrownBy(() -> redefinicao.redefinir(token, "nova-senha-1", codigo, "10.0.0.1", (sujeito, hash) -> {
            throw new IllegalStateException("banco do sistema caiu");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(trocas).hasValue(0);

        redefinir(token, "nova-senha-1", codigo);
        assertThat(trocas).hasValue(1);
    }

    @Test
    void comMfaAtivoRedefinirExigeOCodigoELinkErradoNaoTrocaNada() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        mfa.ativar("ana", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();
        AppDeTeste.RELOGIO.avancar(Duration.ofSeconds(31));

        assertThatThrownBy(() -> redefinir(token, "nova-senha-1", null))
                .isInstanceOfSatisfying(MfaException.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_NECESSARIO"));
        assertThatThrownBy(() -> redefinir(token, "nova-senha-1", "000000"))
                .isInstanceOfSatisfying(MfaException.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_CODIGO_INVALIDO"));
        assertThat(trocas).hasValue(0);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token", Integer.class)).isEqualTo(1);

        redefinir(token, "nova-senha-1", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        assertThat(trocas).hasValue(1);
        // O MFA continua ativo: a redefinição não o desliga.
        assertThat(mfa.ativo("ana")).isTrue();
    }

    @Test
    void comMfaUmCodigoDeRecuperacaoTambemServeMasSoUmaVez() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        Mfa.Recuperacao recuperacao = mfa.ativar("ana", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();

        redefinir(token, "nova-senha-1", recuperacao.codigos().get(0));

        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String segundo = ultimoToken();
        assertThatThrownBy(() -> redefinir(segundo, "nova-senha-2", recuperacao.codigos().get(0)))
                .isInstanceOfSatisfying(MfaException.class, e -> assertThat(e.getCodigo()).isEqualTo("MFA_CODIGO_INVALIDO"));
    }

    @Test
    void chutarOCodigoDoMfaTemLimitePorLinkEDepoisNemOCodigoCertoPassa() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        mfa.ativar("ana", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();
        AppDeTeste.RELOGIO.avancar(Duration.ofSeconds(31));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> redefinir(token, "nova-senha-1", "111111")).isInstanceOf(MfaException.class);
        }
        String certo = TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant());
        assertThatThrownBy(() -> redefinir(token, "nova-senha-1", certo))
                .isInstanceOfSatisfying(LimiteLoginException.class, e -> assertThat(e.segundos()).isPositive());
        assertThat(trocas).hasValue(0);
    }

    @Test
    void linksNovosNaoRenovamOOrcamentoDeTentativasDaConta() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        mfa.ativar("ana", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String primeiro = ultimoToken();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> redefinir(primeiro, "nova-senha-1", "111111")).isInstanceOf(MfaException.class);
        }
        // Um link novo (a caixa de e-mail é de quem chuta) segue no mesmo orçamento: sobra 1 tentativa, não 4.
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String segundo = ultimoToken();
        assertThatThrownBy(() -> redefinir(segundo, "nova-senha-1", "222222")).isInstanceOf(MfaException.class);
        AppDeTeste.RELOGIO.avancar(Duration.ofSeconds(31));
        String certo = TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant());
        assertThatThrownBy(() -> redefinir(segundo, "nova-senha-1", certo)).isInstanceOf(LimiteLoginException.class);
        assertThat(trocas).hasValue(0);
    }

    @Test
    void linksDesconhecidosGastamSoOIpENaoOOrcamentoDeContaNenhuma() {
        for (int i = 0; i < 6; i++) {
            int n = i;
            assertThatThrownBy(() -> redefinir("chute-" + n, "nova-senha-1", null)).isInstanceOf(RedefinicaoException.class);
        }
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        redefinir(ultimoToken(), "nova-senha-1", null);
        assertThat(trocas).hasValue(1);
    }

    @Test
    void limiteDePedidosPorEmailTemRetryAfterEOLoginNaoSofre() {
        for (int i = 0; i < 3; i++) {
            redefinicao.solicitar("ana@x.com", "10.0.0." + i, RedefinicaoIntegrationTest::buscar);
        }
        assertThatThrownBy(() -> redefinicao.solicitar("ANA@x.com", "10.0.0.9", RedefinicaoIntegrationTest::buscar))
                .isInstanceOfSatisfying(LimiteLoginException.class, e -> assertThat(e.segundos()).isBetween(1L, 3600L));
        // Contador próprio: o login da mesma conta e dos mesmos IPs segue livre.
        assertThat(autenticador.entrar("ana@x.com", "senha-antiga-da-ana", null, "10.0.0.1").sujeito()).isEqualTo("ana");
        // E o limite também vale para e-mail que não existe, senão ele denunciaria a conta.
        for (int i = 0; i < 3; i++) {
            redefinicao.solicitar("ninguem@x.com", "10.0.1." + i, RedefinicaoIntegrationTest::buscar);
        }
        assertThatThrownBy(() -> redefinicao.solicitar("ninguem@x.com", "10.0.1.9", RedefinicaoIntegrationTest::buscar))
                .isInstanceOf(LimiteLoginException.class);
    }

    @Test
    void conviteTemPrazoProprioInvalidaOAnteriorEHaDeSerUsadoComoRedefinir() {
        String primeiro = redefinicao.emitirConvite("bia", Duration.ofDays(7));
        String segundo = redefinicao.emitirConvite("bia", Duration.ofDays(7));
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token where sujeito = 'bia' and finalidade = 'CONVITE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token where token_hash = ?", Integer.class, primeiro)).isZero();

        assertThatThrownBy(() -> redefinir(primeiro, "senha-bia-1", null)).isInstanceOf(RedefinicaoException.class);
        AppDeTeste.RELOGIO.avancar(Duration.ofDays(6));
        redefinir(segundo, "senha-bia-2", null);
        assertThat(autenticador.entrar("bia@x.com", "senha-bia-2", null, "10.0.0.5").sujeito()).isEqualTo("bia");
    }

    @Test
    void conviteVencidoNaoVale() {
        String convite = redefinicao.emitirConvite("bia", Duration.ofDays(7));
        AppDeTeste.RELOGIO.avancar(Duration.ofDays(8));
        assertThatThrownBy(() -> redefinir(convite, "senha-bia-1", null)).isInstanceOf(RedefinicaoException.class);
        assertThatThrownBy(() -> redefinicao.emitirConvite("bia", Duration.ofDays(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redefinicao.emitirConvite("bia", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redefinicao.emitirConvite(" ", Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pedirRedefinicaoNaoDerrubaOConviteMasUsarUmApagaOutro() {
        String convite = redefinicao.emitirConvite("bia", Duration.ofDays(7));
        redefinicao.solicitar("bia@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String reset = ultimoToken();
        assertThat(jdbc.queryForObject("select count(*) from plataforma.redefinicao_token where sujeito = 'bia'", Integer.class)).isEqualTo(2);

        redefinir(convite, "senha-bia-1", null);

        assertThatThrownBy(() -> redefinir(reset, "senha-bia-2", null)).isInstanceOf(RedefinicaoException.class);
    }

    @Test
    void doisUsosSimultaneosDoMesmoLinkSoUmVence() throws Exception {
        redefinicao.solicitar("ana@x.com", "10.0.0.1", RedefinicaoIntegrationTest::buscar);
        String token = ultimoToken();
        int tentativas = 3;
        ExecutorService pool = Executors.newFixedThreadPool(tentativas);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        for (int i = 0; i < tentativas; i++) {
            int n = i;
            resultados.add(pool.submit(() -> {
                largada.await();
                try {
                    redefinicao.redefinir(token, "nova-senha-" + n, null, "10.0.0." + n, troca());
                    return true;
                } catch (RedefinicaoException e) {
                    return false;
                }
            }));
        }
        largada.countDown();
        int vencedores = 0;
        for (Future<Boolean> r : resultados) {
            if (r.get(30, TimeUnit.SECONDS)) {
                vencedores++;
            }
        }
        pool.shutdown();
        assertThat(vencedores).isEqualTo(1);
        assertThat(trocas).hasValue(1);
    }
}
