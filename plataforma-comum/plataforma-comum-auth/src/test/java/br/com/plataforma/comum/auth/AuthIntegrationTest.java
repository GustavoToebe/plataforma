package br.com.plataforma.comum.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.plataforma.comum.seguranca.login.LimiteLoginException;
import br.com.plataforma.comum.teste.PostgresDeTeste;
import br.com.plataforma.comum.web.UnauthorizedException;

@SpringBootTest(classes = AppDeTeste.class, properties = {
        "plataforma.auth.habilitado=true",
        "plataforma.auth.jwt.secret=" + AuthIntegrationTest.SEGREDO,
        "plataforma.auth.mfa.chaves=k1:" + AuthIntegrationTest.SEGREDO,
        "plataforma.auth.mfa.chave-ativa=k1",
        "plataforma.auth.mfa.contexto=teste",
        "plataforma.auth.login.limite-conta=20",
        "plataforma.auth.login.limite-ip=1000",
        "plataforma.auth.login.sal=sal-do-teste",
        "plataforma.auth.jwt.emissor=app-de-teste",
        "plataforma.auth.jwt.propositos.tenant_selection=PT5M",
        "spring.main.banner-mode=off"
})
class AuthIntegrationTest {

    static final String SEGREDO = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final PostgreSQLContainer POSTGRES = PostgresDeTeste.iniciar("auth_test", "auth_test", "auth_test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        PostgresDeTeste.registrar(registry, POSTGRES);
    }

    @Autowired Autenticador autenticador;
    @Autowired RefreshTokens refresh;
    @Autowired Mfa mfa;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder senhas;
    @Autowired Environment ambiente;
    @Autowired PlatformTransactionManager transacoes;

    @BeforeEach
    void limpar() {
        jdbc.update("delete from plataforma.refresh_token");
        jdbc.update("delete from plataforma.login_tentativa");
        jdbc.update("delete from plataforma.mfa");
        jdbc.update("delete from plataforma.mfa_recuperacao");
        AppDeTeste.USUARIOS.clear();
        AppDeTeste.SUPORTE.clear();
        AppDeTeste.COMPARACOES.set(0);
        criarUsuario("ana@x.com", "ana", "segredo-da-ana", true, 1);
    }

    private void criarUsuario(String login, String sujeito, String senha, boolean ativo, long versao) {
        AppDeTeste.USUARIOS.put(login, new AppDeTeste.Usuario(sujeito, senhas.encode(senha), ativo, versao));
    }

    private void mudarUsuario(String login, boolean ativo, long versao) {
        AppDeTeste.Usuario atual = AppDeTeste.USUARIOS.get(login);
        AppDeTeste.USUARIOS.put(login, new AppDeTeste.Usuario(atual.sujeito(), atual.hash(), ativo, versao));
    }

    @Test
    void flywayDoSistemaEDoModuloConvivemComHistoricosSeparados() {
        assertThat(jdbc.queryForObject("select count(*) from public.usuario_app", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where version = '1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from plataforma.historico_migracao where version = '1' and success", Integer.class)).isEqualTo(1);
        // O histórico do módulo não vaza para o schema do sistema, nem o do sistema para o schema plataforma.
        assertThat(jdbc.queryForObject("select count(*) from public.flyway_schema_history", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'refresh_token'",
                Integer.class)).isZero();
    }

    @Test
    void loginGuardaSoOHashDoRefreshEEntregaAccessValido() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.1");

        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token where token_hash = ?", Integer.class,
                sessao.refreshToken())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token where token_hash = ?", Integer.class,
                TokenOpaco.hash(sessao.refreshToken()))).isEqualTo(1);
        assertThat(autenticador.autenticar(sessao.accessToken()).sujeito()).isEqualTo("ana");
        assertThat(sessao.expiraEmSegundos()).isEqualTo(900);
    }

    @Test
    void erroDeLoginTemAMesmaMensagemECompararSenhaMesmoSemUsuario() {
        criarUsuario("inativo@x.com", "inativo", "senha-boa", false, 1);

        List<String> mensagens = new ArrayList<>();
        for (String[] tentativa : new String[][]{{"ana@x.com", "errada"}, {"naoexiste@x.com", "qualquer"}, {"inativo@x.com", "senha-boa"}}) {
            int antes = AppDeTeste.COMPARACOES.get();
            assertThatThrownBy(() -> autenticador.entrar(tentativa[0], tentativa[1], null, "10.0.0.2"))
                    .isInstanceOf(UnauthorizedException.class)
                    .satisfies(e -> mensagens.add(e.getMessage()));
            assertThat(AppDeTeste.COMPARACOES.get()).as("senha comparada para %s", tentativa[0]).isEqualTo(antes + 1);
        }
        assertThat(mensagens).containsOnly("E-mail ou senha inválidos.");
    }

    @Test
    void limiteDeLoginPorContaBloqueiaDepoisDoLimite() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "errada", null, "10.0.0.3"))
                    .isInstanceOf(UnauthorizedException.class);
        }
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.3"))
                .isInstanceOf(LimiteLoginException.class);
        // Nada de e-mail nem IP em claro na tabela do contador.
        assertThat(jdbc.queryForList("select chave from plataforma.login_tentativa", String.class))
                .allSatisfy(chave -> assertThat(chave).doesNotContain("ana@x.com").doesNotContain("10.0.0.3"));
    }

    /**
     * O sistema costuma chamar o login dentro do próprio {@code @Transactional}: a senha errada (401) desfaz a transação de
     * fora, mas a tentativa contada tem de ficar, senão o limite nunca dispara.
     */
    @Test
    void limiteDeLoginContaMesmoQuandoATransacaoDoSistemaEhDesfeita() {
        TransactionTemplate doSistema = new TransactionTemplate(transacoes);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> doSistema.executeWithoutResult(s -> autenticador.entrar("ana@x.com", "errada", null, "10.0.1.3")))
                    .isInstanceOf(UnauthorizedException.class);
        }
        assertThatThrownBy(() -> doSistema.executeWithoutResult(s -> autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.1.3")))
                .isInstanceOf(LimiteLoginException.class);
    }

    /** O reuso derruba todas as sessões mesmo que o sistema chame a renovação dentro de uma transação que o 401 desfaz. */
    @Test
    void reusoDerrubaAsSessoesMesmoQuandoATransacaoDoSistemaEhDesfeita() {
        Autenticador.Sessao primeira = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.1.5");
        Autenticador.Sessao outra = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.1.6");
        autenticador.renovar(primeira.refreshToken(), "10.0.1.5");
        TransactionTemplate doSistema = new TransactionTemplate(transacoes);

        assertThatThrownBy(() -> doSistema.executeWithoutResult(s -> autenticador.renovar(primeira.refreshToken(), "10.0.1.9")))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from plataforma.refresh_token where sujeito = 'ana' and revogado_em is null", Integer.class)).isZero();
        assertThatThrownBy(() -> autenticador.renovar(outra.refreshToken(), "10.0.1.6")).isInstanceOf(UnauthorizedException.class);
    }

    /**
     * Login que corre junto com a troca de senha ou a ativação do MFA: a sessão nasce com a versão antiga das credenciais e
     * não pode virar um access novo depois que a versão subiu.
     */
    @Test
    void refreshEmitidoComVersaoAntigaNaoRenova() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.1.7");
        mudarUsuario("ana@x.com", true, 2);

        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.1.7")).isInstanceOf(UnauthorizedException.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from plataforma.refresh_token where sujeito = 'ana' and revogado_em is null", Integer.class)).isZero();

        Autenticador.Sessao nova = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.1.7");
        assertThat(autenticador.autenticar(autenticador.renovar(nova.refreshToken(), "10.0.1.7").accessToken()).versao()).isEqualTo(2);
    }

    @Test
    void verificarCredenciaisConfereSenhaELimiteMasNaoAbreSessao() {
        Autenticador.Identidade credencial = autenticador.verificarCredenciais("ana@x.com", "segredo-da-ana", null, "10.0.8.1");
        assertThat(credencial.sujeito()).isEqualTo("ana");
        assertThat(credencial.versao()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token", Integer.class)).isZero();
        assertThatThrownBy(() -> autenticador.verificarCredenciais("ana@x.com", "errada", null, "10.0.8.1"))
                .isInstanceOf(UnauthorizedException.class);
        mudarUsuario("ana@x.com", false, 1);
        assertThatThrownBy(() -> autenticador.verificarCredenciais("ana@x.com", "segredo-da-ana", null, "10.0.8.1"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void novaSessaoLevaOsClaimsDoSistemaNoAccess() {
        Autenticador.Sessao sessao = autenticador.novaSessao("ana", 1, "10.0.8.2", java.util.Map.of("tenant", "t1", "roles", List.of("ADMIN")));
        JwtService.Token token = autenticador.autenticar(sessao.accessToken());
        assertThat(token.extras()).containsEntry("tenant", "t1").containsEntry("roles", List.of("ADMIN"));
        assertThat(autenticador.autenticar(autenticador.novaSessao("ana", 1, "10.0.8.2").accessToken()).extras()).isEmpty();
    }

    @Test
    void renovarCalculaOsClaimsDentroDaRotacao() {
        Autenticador.Sessao sessao = autenticador.novaSessao("ana", 1, "10.0.8.3", java.util.Map.of("tenant", "t1"));
        Autenticador.Sessao nova = autenticador.renovar(sessao.refreshToken(), "10.0.8.3",
                sujeito -> java.util.Map.of("tenant", "t2", "quem", sujeito));
        assertThat(autenticador.autenticar(nova.accessToken()).extras()).containsEntry("tenant", "t2").containsEntry("quem", "ana");
        // Sem função, a renovação não carrega claims (o comportamento da 1.6.0).
        assertThat(autenticador.autenticar(autenticador.renovar(nova.refreshToken(), "10.0.8.3").accessToken()).extras()).isEmpty();
    }

    /** O sistema recusa a renovação (um 403 por falta de vínculo): o refresh antigo continua valendo e isso não é reuso. */
    @Test
    void recusaDoSistemaNaRenovacaoDesfazARotacaoEOTokenAntigoContinuaValido() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.8.4");
        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.8.4", sujeito -> {
            throw new br.com.plataforma.comum.web.ForbiddenException("Sem vínculo com a paróquia.");
        })).isInstanceOf(br.com.plataforma.comum.web.ForbiddenException.class);

        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token where revogado_em is null", Integer.class)).isEqualTo(1);
        Autenticador.Sessao nova = autenticador.renovar(sessao.refreshToken(), "10.0.8.4");
        assertThat(autenticador.autenticar(nova.accessToken()).sujeito()).isEqualTo("ana");
        // Agora sim, o antigo foi gasto: reusá-lo é reuso.
        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.8.4")).isInstanceOf(UnauthorizedException.class);
    }

    /** Claim que o JWT não aceita (UUID): a renovação falha antes do commit e o cliente continua com o refresh antigo. */
    @Test
    void claimInvalidoDoSistemaNaRenovacaoNaoGastaORefreshAntigo() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.8.5");
        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.8.5",
                sujeito -> java.util.Map.of("tenant", java.util.UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.8.5", sujeito -> java.util.Map.of("cv", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token where revogado_em is null", Integer.class)).isEqualTo(1);
        assertThat(autenticador.autenticar(autenticador.renovar(sessao.refreshToken(), "10.0.8.5").accessToken()).sujeito()).isEqualTo("ana");
    }

    @Test
    void propositoDoAplicativoTemValidadePropriaECarregaClaims() {
        String token = jwt.emitir("tenant_selection", "ana", 1, java.util.Map.of("candidatos", List.of("t1", "t2")));
        JwtService.Token validado = autenticador.autenticarComo(token, "tenant_selection");
        assertThat(validado.sujeito()).isEqualTo("ana");
        assertThat(validado.proposito()).isEqualTo("tenant_selection");
        assertThat(validado.extras()).containsEntry("candidatos", List.of("t1", "t2"));

        // Nunca vale como acesso nem como suporte, e o contrário também não.
        assertThatThrownBy(() -> autenticador.autenticar(token)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> autenticador.autenticarSuporte(token)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> autenticador.autenticarComo(jwt.emitirAcesso("ana", 1), "tenant_selection"))
                .isInstanceOf(UnauthorizedException.class);

        // Validade de 5 minutos, não os 15 do acesso.
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(4));
        assertThat(autenticador.autenticarComo(token, "tenant_selection").sujeito()).isEqualTo("ana");
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(2));
        assertThatThrownBy(() -> autenticador.autenticarComo(token, "tenant_selection")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void propositoDoAplicativoCaiQuandoASenhaMudaOuOPropositoNaoExiste() {
        String token = jwt.emitir("tenant_selection", "ana", 1, java.util.Map.of());
        mudarUsuario("ana@x.com", true, 2);
        assertThatThrownBy(() -> autenticador.autenticarComo(token, "tenant_selection")).isInstanceOf(UnauthorizedException.class);

        assertThatThrownBy(() -> jwt.emitir("inexistente", "ana", 1, java.util.Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> autenticador.autenticarComo(jwt.emitirAcesso("ana", 2), "access")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> autenticador.autenticarComo(token, "suporte")).isInstanceOf(IllegalArgumentException.class);
    }

    /** O sujeito de uma sessão de suporte pode não ser um usuário: a versão vem de {@code versaoDeSuporte}. */
    @Test
    void suporteComSujeitoQueNaoEUsuarioConfereAVersaoDeSuporteELevaClaims() {
        AppDeTeste.SUPORTE.put("codigo-1", 7L);
        String token = jwt.emitirSuporte("codigo-1", 7, "operador-1", "chamado 42", Duration.ofMinutes(10),
                java.util.Map.of("tenant", "t1", "email", "op@x.com"));
        JwtService.Token validado = autenticador.autenticarSuporte(token);
        assertThat(validado.suporte()).isTrue();
        assertThat(validado.extras()).containsEntry("tenant", "t1").containsEntry("email", "op@x.com");

        AppDeTeste.SUPORTE.put("codigo-1", 8L);
        assertThatThrownBy(() -> autenticador.autenticarSuporte(token)).isInstanceOf(UnauthorizedException.class);
        AppDeTeste.SUPORTE.remove("codigo-1");
        assertThatThrownBy(() -> autenticador.autenticarSuporte(token)).isInstanceOf(UnauthorizedException.class);
    }

    /** Sem sobrescrever {@code versaoDeSuporte}, o suporte continua conferindo a versão do usuário (como na 1.6.0). */
    @Test
    void suporteSemVersaoDeSuporteUsaAVersaoDasCredenciais() {
        String token = jwt.emitirSuporte("ana", 1, "operador-1", "chamado 42", Duration.ofMinutes(10));
        assertThat(autenticador.autenticarSuporte(token).sujeito()).isEqualTo("ana");
        mudarUsuario("ana@x.com", true, 2);
        assertThatThrownBy(() -> autenticador.autenticarSuporte(token)).isInstanceOf(UnauthorizedException.class);
    }

    /** Só a tentativa que cruza o limite abre o bloqueio; as seguintes da janela apenas recebem o 429. */
    @Test
    void soAPrimeiraTentativaAlemDoLimiteMarcaOPrimeiroBloqueio() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "errada", null, "10.0.8.9")).isInstanceOf(UnauthorizedException.class);
        }
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "errada", null, "10.0.8.9"))
                .isInstanceOfSatisfying(LimiteLoginException.class, e -> assertThat(e.primeiroBloqueio()).isTrue());
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "errada", null, "10.0.8.9"))
                .isInstanceOfSatisfying(LimiteLoginException.class, e -> assertThat(e.primeiroBloqueio()).isFalse());
    }

    @Test
    void tokenComVersaoAntigaOuUsuarioInativoEhRecusado() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.4");
        assertThat(autenticador.autenticar(sessao.accessToken()).versao()).isEqualTo(1);

        mudarUsuario("ana@x.com", true, 2);
        assertThatThrownBy(() -> autenticador.autenticar(sessao.accessToken())).isInstanceOf(UnauthorizedException.class);

        mudarUsuario("ana@x.com", true, 1);
        assertThat(autenticador.autenticar(sessao.accessToken()).sujeito()).isEqualTo("ana");
        mudarUsuario("ana@x.com", false, 1);
        assertThatThrownBy(() -> autenticador.autenticar(sessao.accessToken())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void tokenDeUmPropositoNaoServeParaOutro() {
        String suporte = jwt.emitirSuporte("ana", 1, "operador-1", "chamado 42", Duration.ofMinutes(10));
        String acesso = jwt.emitirAcesso("ana", 1);

        assertThatThrownBy(() -> autenticador.autenticar(suporte)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> autenticador.autenticarSuporte(acesso)).isInstanceOf(UnauthorizedException.class);
        JwtService.Token validado = autenticador.autenticarSuporte(suporte);
        assertThat(validado.suporte()).isTrue();
        assertThat(validado.operador()).isEqualTo("operador-1");
        assertThat(validado.motivo()).isEqualTo("chamado 42");
    }

    @Test
    void accessExpiraEm15Minutos() {
        String acesso = jwt.emitirAcesso("ana", 1);
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(14));
        assertThat(autenticador.autenticar(acesso).sujeito()).isEqualTo("ana");
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(2));
        assertThatThrownBy(() -> autenticador.autenticar(acesso)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refreshRotacionaEReusoDerrubaTodasAsSessoesVisivelEmOutraConexao() throws Exception {
        Autenticador.Sessao primeira = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.5");
        Autenticador.Sessao outraSessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.6");

        Autenticador.Sessao segunda = autenticador.renovar(primeira.refreshToken(), "10.0.0.5");
        assertThat(segunda.refreshToken()).isNotEqualTo(primeira.refreshToken());
        assertThat(autenticador.autenticar(segunda.accessToken()).sujeito()).isEqualTo("ana");

        // Reapresentar o token antigo é reuso: 401 e todas as sessões da ana são revogadas.
        assertThatThrownBy(() -> autenticador.renovar(primeira.refreshToken(), "10.0.0.9")).isInstanceOf(UnauthorizedException.class);

        // A revogação já foi confirmada (commit) quando o 401 saiu: outra conexão enxerga.
        try (Connection outra = DriverManager.getConnection(ambiente.getProperty("spring.datasource.url"),
                ambiente.getProperty("spring.datasource.username"), ambiente.getProperty("spring.datasource.password"));
             ResultSet rs = outra.createStatement().executeQuery(
                     "select count(*) from plataforma.refresh_token where sujeito = 'ana' and revogado_em is null")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        assertThatThrownBy(() -> autenticador.renovar(segunda.refreshToken(), "10.0.0.5")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> autenticador.renovar(outraSessao.refreshToken(), "10.0.0.6")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void duasRenovacoesSimultaneasDoMesmoTokenNaoTravamENaoDaoDoisTokens() throws Exception {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.7");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            Callable<Boolean> renovar = () -> {
                largada.await();
                try {
                    autenticador.renovar(sessao.refreshToken(), "10.0.0.7");
                    return true;
                } catch (UnauthorizedException e) {
                    return false;
                }
            };
            Future<Boolean> a = pool.submit(renovar);
            Future<Boolean> b = pool.submit(renovar);
            largada.countDown();
            boolean okA = a.get(20, TimeUnit.SECONDS);
            boolean okB = b.get(20, TimeUnit.SECONDS);
            assertThat(okA ^ okB).as("exatamente uma renovação vence").isTrue();
        } finally {
            pool.shutdownNow();
        }
        // O perdedor tratou como reuso: nenhuma sessão ativa sobra.
        assertThat(jdbc.queryForObject(
                "select count(*) from plataforma.refresh_token where sujeito = 'ana' and revogado_em is null", Integer.class)).isZero();
    }

    @Test
    void renovarComUsuarioInativoNaoEmiteTokenNovo() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.8");
        mudarUsuario("ana@x.com", false, 1);

        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.0.8")).isInstanceOf(UnauthorizedException.class);
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token", Integer.class)).isEqualTo(1);
    }

    @Test
    void refreshExpiradoEDesconhecidoSaoRecusados() {
        Autenticador.Sessao sessao = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.8");
        assertThatThrownBy(() -> autenticador.renovar("token-que-nao-existe", "10.0.0.8"))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Sessão inválida. Entre de novo.");
        assertThatThrownBy(() -> autenticador.renovar(null, "10.0.0.8")).isInstanceOf(UnauthorizedException.class);

        AppDeTeste.RELOGIO.avancar(Duration.ofDays(8));
        assertThatThrownBy(() -> autenticador.renovar(sessao.refreshToken(), "10.0.0.8"))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Sessão expirada. Entre de novo.");
    }

    @Test
    void sairRevogaOTokenEEncerrarSessoesApagaTudo() {
        Autenticador.Sessao a = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.8");
        Autenticador.Sessao b = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.8");

        autenticador.sair(a.refreshToken());
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token where revogado_em is not null", Integer.class))
                .isEqualTo(1);

        autenticador.encerrarSessoes("ana");
        assertThat(jdbc.queryForObject("select count(*) from plataforma.refresh_token", Integer.class)).isZero();
        // Apagado, não revogado: apresentar o token agora é "inválido", não "reuso" (não derruba sessões novas de ninguém).
        assertThatThrownBy(() -> autenticador.renovar(b.refreshToken(), "10.0.0.8")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void mfaPeloCicloCompleto() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        assertThat(preparacao.toString()).doesNotContain(preparacao.segredo());
        // O segredo fica cifrado no banco e preso ao sujeito (AAD): não aparece em claro.
        assertThat(jdbc.queryForObject("select pendente_cifrado from plataforma.mfa where sujeito = 'ana'", String.class))
                .startsWith("v1:k1:").doesNotContain(preparacao.segredo());

        assertThatThrownBy(() -> mfa.ativar("ana", "000000")).isInstanceOf(MfaException.class);
        Autenticador.Sessao anterior = autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.10");

        String codigo = TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant());
        Mfa.Recuperacao recuperacao = mfa.ativar("ana", codigo);
        assertThat(recuperacao.codigos()).hasSize(10).allSatisfy(c -> assertThat(c).matches("[0-9a-f]{32}"));
        assertThat(recuperacao.toString()).doesNotContain(recuperacao.codigos().get(0));
        assertThat(mfa.status("ana").codigosRestantes()).isEqualTo(10);
        // Ativar encerra as sessões.
        assertThatThrownBy(() -> autenticador.renovar(anterior.refreshToken(), "10.0.0.10")).isInstanceOf(UnauthorizedException.class);

        // Login: sem código, MFA_NECESSARIO; o mesmo código da ativação já foi consumido; um novo passo funciona uma vez.
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.10"))
                .isInstanceOf(MfaException.class).extracting("codigo").isEqualTo("MFA_NECESSARIO");
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "segredo-da-ana", codigo, "10.0.0.10"))
                .isInstanceOf(MfaException.class).extracting("codigo").isEqualTo("MFA_CODIGO_INVALIDO");
        AppDeTeste.RELOGIO.avancar(Duration.ofSeconds(60));
        String proximo = TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant());
        assertThat(autenticador.entrar("ana@x.com", "segredo-da-ana", proximo, "10.0.0.10").sujeito()).isEqualTo("ana");
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "segredo-da-ana", proximo, "10.0.0.10"))
                .isInstanceOf(MfaException.class).extracting("codigo").isEqualTo("MFA_CODIGO_INVALIDO");

        // Código de recuperação vale uma vez.
        String rec = recuperacao.codigos().get(0);
        assertThat(autenticador.entrar("ana@x.com", "segredo-da-ana", rec, "10.0.0.10").sujeito()).isEqualTo("ana");
        assertThatThrownBy(() -> autenticador.entrar("ana@x.com", "segredo-da-ana", rec, "10.0.0.10")).isInstanceOf(MfaException.class);
        assertThat(mfa.status("ana").codigosRestantes()).isEqualTo(9);

        // Desativar exige código e encerra as sessões.
        AppDeTeste.RELOGIO.avancar(Duration.ofSeconds(60));
        mfa.desativar("ana", TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant()));
        assertThat(mfa.ativo("ana")).isFalse();
        assertThat(autenticador.entrar("ana@x.com", "segredo-da-ana", null, "10.0.0.11").sujeito()).isEqualTo("ana");
    }

    @Test
    void segredoDeMfaDeUmSujeitoNaoDecifraParaOutro() {
        mfa.preparar("ana");
        String cifrado = jdbc.queryForObject("select pendente_cifrado from plataforma.mfa where sujeito = 'ana'", String.class);
        jdbc.update("insert into plataforma.mfa (sujeito, segredo_cifrado, ultimo_passo) values ('beto', ?, -1)", cifrado);

        assertThatThrownBy(() -> mfa.verificarLogin("beto", "123456")).isInstanceOf(MfaException.class)
                .extracting("codigo").isEqualTo("MFA_INDISPONIVEL");
    }

    @Test
    void prepararComMfaAtivoEPreparacaoExpiradaSaoRecusados() {
        Mfa.Preparacao preparacao = mfa.preparar("ana");
        AppDeTeste.RELOGIO.avancar(Duration.ofMinutes(11));
        String codigo = TotpDeTeste.codigo(preparacao.segredo(), AppDeTeste.RELOGIO.instant());
        assertThatThrownBy(() -> mfa.ativar("ana", codigo)).isInstanceOf(MfaException.class)
                .extracting("codigo").isEqualTo("MFA_PREPARACAO_EXPIRADA");

        Mfa.Preparacao nova = mfa.preparar("ana");
        mfa.ativar("ana", TotpDeTeste.codigo(nova.segredo(), AppDeTeste.RELOGIO.instant()));
        assertThatThrownBy(() -> mfa.preparar("ana")).isInstanceOf(MfaException.class)
                .extracting("codigo").isEqualTo("MFA_JA_ATIVO");
    }

    @Test
    void conexaoDeTesteApontaParaOPostgresDaImagemDoParent() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseMajorVersion())
                    .isEqualTo(Integer.parseInt(PostgresDeTeste.imagem().replaceAll("^postgres:(\\d+).*", "$1")));
        }
    }
}
