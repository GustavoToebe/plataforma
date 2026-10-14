package br.com.plataforma.comum.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.com.plataforma.comum.teste.PostgresDeTeste;
import br.com.plataforma.comum.web.BadRequestException;
import br.com.plataforma.comum.web.ConflictException;
import br.com.plataforma.comum.web.ResourceNotFoundException;

/** Idempotência do provisionamento e regras dos direitos locais, contra o Postgres. */
@SpringBootTest(classes = AppDeTeste.class, properties = {
        "plataforma.central.habilitado=true",
        "plataforma.central.chaves-entrada=" + IntegracaoIntegrationTest.ENTRADA,
        "spring.main.banner-mode=off"
})
class DireitosEIdempotenciaIntegrationTest {

    static final PostgreSQLContainer POSTGRES = PostgresDeTeste.iniciar("direitos_test", "direitos_test", "direitos_test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        PostgresDeTeste.registrar(registry, POSTGRES);
    }

    @Autowired Idempotencia idempotencia;
    @Autowired DireitosLocais direitos;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        AppDeTeste.RELOGIO.reiniciar();
        jdbc.update("delete from plataforma_integracao.integracao_operacao");
        jdbc.update("delete from plataforma_integracao.direitos_locais");
    }

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    // ---- idempotência ----

    @Test
    void mesmaChaveEMesmoCorpoRepeteARespostaSemRepetirOEfeito() {
        AtomicInteger execucoes = new AtomicInteger();
        Idempotencia.Resultado primeira = idempotencia.executar("chave-1", "PROVISIONAR", bytes("{\"a\":1}"),
                () -> { execucoes.incrementAndGet(); return new Idempotencia.Resultado(201, "{\"id\":\"x\"}"); });
        Idempotencia.Resultado segunda = idempotencia.executar("chave-1", "PROVISIONAR", bytes("{\"a\":1}"),
                () -> { execucoes.incrementAndGet(); return new Idempotencia.Resultado(500, "outra"); });
        assertThat(execucoes).hasValue(1);
        assertThat(primeira.repetido()).isFalse();
        assertThat(segunda.repetido()).isTrue();
        assertThat(segunda.statusHttp()).isEqualTo(201);
        assertThat(segunda.resposta()).isEqualTo("{\"id\":\"x\"}");
    }

    @Test
    void mesmaChaveComOutroCorpoOuOutroTipoEhConflito() {
        idempotencia.executar("chave-2", "PROVISIONAR", bytes("{\"a\":1}"), () -> new Idempotencia.Resultado(201, "{}"));
        assertThatThrownBy(() -> idempotencia.executar("chave-2", "PROVISIONAR", bytes("{\"a\":2}"), () -> new Idempotencia.Resultado(201, "{}")))
                .isInstanceOf(ConflictException.class).extracting("codigo").isEqualTo("IDEMPOTENCY_KEY_CONFLITO");
        assertThatThrownBy(() -> idempotencia.executar("chave-2", "OUTRO", bytes("{\"a\":1}"), () -> new Idempotencia.Resultado(201, "{}")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void chaveInvalidaEhRecusada() {
        for (String chave : new String[] {null, "", "   ", "com espaco", "x".repeat(121), "a;b"}) {
            assertThatThrownBy(() -> idempotencia.executar(chave, "T", bytes("{}"), () -> new Idempotencia.Resultado(200, "{}")))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void operacaoQueFalhaNaoGravaRegistroEPodeSerRepetida() {
        assertThatThrownBy(() -> idempotencia.executar("chave-3", "T", bytes("{}"), () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from plataforma_integracao.integracao_operacao", Integer.class)).isZero();
        Idempotencia.Resultado depois = idempotencia.executar("chave-3", "T", bytes("{}"), () -> new Idempotencia.Resultado(200, "{\"ok\":true}"));
        assertThat(depois.repetido()).isFalse();
    }

    @Test
    void duasRequisicoesSimultaneasComAMesmaChaveExecutamUmaVezSo() throws Exception {
        int threads = 8;
        AtomicInteger execucoes = new AtomicInteger();
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Idempotencia.Resultado>> futuros = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return idempotencia.executar("chave-concorrente", "PROVISIONAR", bytes("{\"slug\":\"sao-jose\"}"), () -> {
                        execucoes.incrementAndGet();
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new Idempotencia.Resultado(201, "{\"id\":\"unico\"}");
                    });
                }));
            }
            largada.countDown();
            int repetidos = 0;
            for (Future<Idempotencia.Resultado> futuro : futuros) {
                Idempotencia.Resultado r = futuro.get(30, TimeUnit.SECONDS);
                assertThat(r.resposta()).isEqualTo("{\"id\":\"unico\"}");
                if (r.repetido()) {
                    repetidos++;
                }
            }
            assertThat(execucoes).hasValue(1);
            assertThat(repetidos).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- direitos locais ----

    private DireitosLocais.Direitos snapshot(String instancia, long versao, String situacao, boolean liberado) {
        return new DireitosLocais.Direitos(instancia, "contratacao-1", versao, situacao, liberado, liberado ? null : "Pagamento em atraso",
                LocalDate.parse("2026-10-15"), "PROFISSIONAL", "Profissional", "{\"usuarios\":5}", "[\"ESCALAS\"]");
    }

    @Test
    void aplicaSoVersaoMaiorEIgnoraIgualOuMenorSemErro() {
        direitos.inicializar(snapshot("t1", 5, "ATIVA", true));
        AppDeTeste.RELOGIO.avancar(Duration.ofHours(1));

        assertThat(direitos.aplicar(snapshot("t1", 5, "BLOQUEADA", false))).isEqualTo(new DireitosLocais.Aplicacao(false, 5));
        assertThat(direitos.aplicar(snapshot("t1", 4, "BLOQUEADA", false))).isEqualTo(new DireitosLocais.Aplicacao(false, 5));
        DireitosLocais.Estado intacto = direitos.buscar("t1").orElseThrow();
        assertThat(intacto.direitos().situacao()).isEqualTo("ATIVA");
        assertThat(intacto.direitos().acessoLiberado()).isTrue();
        assertThat(intacto.confirmadoEm()).isEqualTo(AppDeTeste.RELOGIO.instant());

        assertThat(direitos.aplicar(snapshot("t1", 6, "BLOQUEADA", false))).isEqualTo(new DireitosLocais.Aplicacao(true, 6));
        DireitosLocais.Estado novo = direitos.buscar("t1").orElseThrow();
        assertThat(novo.direitos().situacao()).isEqualTo("BLOQUEADA");
        assertThat(novo.direitos().motivoBloqueio()).isEqualTo("Pagamento em atraso");
        assertThat(novo.direitos().vigenteAte()).isEqualTo(LocalDate.parse("2026-10-15"));
        assertThat(novo.direitos().limites()).isEqualTo("{\"usuarios\":5}");
    }

    @Test
    void instanciaDesconhecidaEh404EContratacaoDiferenteEhConflito() {
        assertThatThrownBy(() -> direitos.aplicar(snapshot("nao-existe", 1, "ATIVA", true)))
                .isInstanceOf(ResourceNotFoundException.class).extracting("codigo").isEqualTo("INSTANCIA_NAO_ENCONTRADA");
        direitos.inicializar(snapshot("t2", 1, "ATIVA", true));
        DireitosLocais.Direitos outraContratacao = new DireitosLocais.Direitos("t2", "contratacao-OUTRA", 9, "ATIVA", true, null, null,
                "P", "P", "{}", "[]");
        assertThatThrownBy(() -> direitos.aplicar(outraContratacao)).isInstanceOf(ConflictException.class)
                .extracting("codigo").isEqualTo("IDEMPOTENCY_KEY_CONFLITO");
        assertThatThrownBy(() -> direitos.inicializar(snapshot("t2", 1, "ATIVA", true))).isInstanceOf(ConflictException.class);
    }

    @Test
    void dadosInvalidosSaoRecusados() {
        assertThatThrownBy(() -> direitos.inicializar(snapshot("t3", 1, "QUALQUER", true))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> direitos.inicializar(snapshot("t3", -1, "ATIVA", true))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> direitos.inicializar(snapshot("", 1, "ATIVA", true))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void acessoEfetivoExigeLiberacaoEConfirmacaoDentroDe72Horas() {
        assertThat(direitos.acessoEfetivo("sem-registro")).isFalse();

        direitos.inicializar(snapshot("t4", 1, "ATIVA", true));
        assertThat(direitos.acessoEfetivo("t4")).isTrue();
        AppDeTeste.RELOGIO.avancar(Duration.ofDays(3));
        assertThat(direitos.acessoEfetivo("t4")).as("exatamente 72h ainda vale").isTrue();
        AppDeTeste.RELOGIO.avancar(Duration.ofSeconds(1));
        assertThat(direitos.acessoEfetivo("t4")).as("passou das 72h sem confirmação").isFalse();

        // Versão igual confirma e reabre a janela.
        direitos.aplicar(snapshot("t4", 1, "ATIVA", true));
        assertThat(direitos.acessoEfetivo("t4")).isTrue();

        direitos.inicializar(snapshot("t5", 1, "BLOQUEADA", false));
        assertThat(direitos.acessoEfetivo("t5")).as("a Central bloqueou").isFalse();
    }

    @Test
    void sincronizacaoAplicaMaioresConfirmaTodasEIgnoraDesconhecidas() {
        direitos.inicializar(snapshot("s1", 1, "ATIVA", true));
        direitos.inicializar(snapshot("s2", 3, "ATIVA", true));
        AppDeTeste.RELOGIO.avancar(Duration.ofHours(10));

        int aplicadas = direitos.sincronizar(List.of(
                snapshot("s1", 2, "ATIVA", true), snapshot("s2", 3, "ATIVA", true), snapshot("desconhecida", 1, "ATIVA", true)));

        assertThat(aplicadas).isEqualTo(1);
        assertThat(direitos.buscar("s1").orElseThrow().direitos().versao()).isEqualTo(2);
        assertThat(direitos.buscar("s2").orElseThrow().confirmadoEm()).isEqualTo(AppDeTeste.RELOGIO.instant());
        assertThat(direitos.buscar("desconhecida")).isEmpty();
    }

    @Test
    void webhooksSimultaneosDaMesmaInstanciaTerminamNaMaiorVersao() throws Exception {
        direitos.inicializar(snapshot("c1", 0, "ATIVA", true));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<?>> futuros = new ArrayList<>();
            for (long versao = 1; versao <= 12; versao++) {
                long v = versao;
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return direitos.aplicar(snapshot("c1", v, "ATIVA", true));
                }));
            }
            largada.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(direitos.buscar("c1").orElseThrow().direitos().versao()).isEqualTo(12);
    }
}
