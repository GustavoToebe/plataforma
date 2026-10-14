package br.com.plataforma.comum.web;

import br.com.plataforma.comum.seguranca.login.LimiteLoginException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final List<String> avisos = new ArrayList<>();
    private final AtomicReference<RuntimeException> falhaDoOuvinte = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        OuvinteDeErros ouvinte = (status, codigo, mensagem, request) -> avisos.add(status + ":" + codigo);
        OuvinteDeErros quebrado = (status, codigo, mensagem, request) -> {
            throw falhaDoOuvinte.updateAndGet(f -> f != null ? f : new IllegalStateException("ouvinte quebrado"));
        };
        mvc = MockMvcBuilders.standaloneSetup(new Erros())
                .setControllerAdvice(new GlobalExceptionHandler(List.of(quebrado, ouvinte), "br.com.plataforma."))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void apiExceptionViraApiErrorComCodigoEAvisaOOuvinte() throws Exception {
        mvc.perform(get("/erros/conflito").header("X-Request-Id", "abc-123"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.codigo").value("CPF_REPETIDO"))
                .andExpect(jsonPath("$.message").value("CPF já cadastrado."))
                .andExpect(jsonPath("$.path").value("/erros/conflito"))
                .andExpect(jsonPath("$.requestId").value("abc-123"));
        assertThat(avisos).containsExactly("409:CPF_REPETIDO");
    }

    @Test
    void excecoesSemCodigoDevolvemCodigoNulo() throws Exception {
        mvc.perform(get("/erros/proibido"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").doesNotExist());
        mvc.perform(get("/erros/invalido"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void limiteDeLoginVira429ComOCodigoDaExcecao() throws Exception {
        mvc.perform(get("/erros/limite"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("LOGIN_LIMITADO"));
        assertThat(avisos).containsExactly("429:LOGIN_LIMITADO");
    }

    @Test
    void edicaoConcorrenteVira409ComCodigoProprio() throws Exception {
        mvc.perform(get("/erros/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONFLITO_EDICAO"));
    }

    @Test
    void validacaoDeCorpoListaOsCampos() throws Exception {
        mvc.perform(post("/erros/corpo").contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nome"));
    }

    @Test
    void corpoIlegivelVira400() throws Exception {
        mvc.perform(post("/erros/corpo").contentType(MediaType.APPLICATION_JSON).content("{nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
    }

    @Test
    void corpoQueEstourouOLimiteVira413() throws Exception {
        mvc.perform(get("/erros/corpo-excedido"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value("CORPO_EXCEDIDO"));
    }

    @Test
    void errosDoMvcTambemSaemNoFormatoPadrao() throws Exception {
        mvc.perform(post("/erros/conflito"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void rotaInexistenteVira404ComCodigoNaoEncontrado() throws Exception {
        mvc.perform(get("/nao/existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.codigo").value("NAO_ENCONTRADO"))
                .andExpect(jsonPath("$.path").value("/nao/existe"));
    }

    @Test
    void erroInesperadoNaoVazaDadosNemNoCorpoNemNoResumoDoLog() throws Exception {
        mvc.perform(get("/erros/inesperado"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        "Ocorreu um erro inesperado. Tente novamente ou contate o suporte informando o requestId."));
        assertThat(avisos).containsExactly("500:null");

        GlobalExceptionHandler handler = new GlobalExceptionHandler(List.of(), "br.com.plataforma.");
        Throwable erro = new IllegalStateException("Key (email)=(maria@exemplo.com) já existe",
                new IllegalArgumentException("Failing row contains (123.456.789-00)"));
        String resumo = handler.semDados(erro);
        assertThat(resumo)
                .contains("java.lang.IllegalStateException <- java.lang.IllegalArgumentException")
                .contains("GlobalExceptionHandlerTest")
                .doesNotContain("maria@exemplo.com")
                .doesNotContain("123.456.789-00");
    }

    @Test
    void resumoDoLogLimitaCausasEPontosDoCodigoProprio() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(List.of(), "br.com.plataforma.");
        Throwable erro = new RuntimeException("a");
        for (int i = 0; i < 10; i++) {
            erro = new RuntimeException("causa " + i, erro);
        }

        String resumo = handler.semDados(erro);

        assertThat(resumo.split(" <- ")).hasSize(6);
        assertThat(resumo.split("\\| em ").length - 1).isLessThanOrEqualTo(5);
    }

    @Test
    void acessoNegadoEAutenticacaoSaoRelancadosParaOFiltroDeSeguranca() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(List.of(), "br.com.plataforma.");
        AccessDeniedException negado = new AccessDeniedException("sem permissão");

        assertThatThrownBy(() -> handler.handleAccessDenied(negado)).isSameAs(negado);
    }

    @Test
    void falhaDoOuvinteNaoMudaAResposta() throws Exception {
        mvc.perform(get("/erros/conflito")).andExpect(status().isConflict());

        assertThat(falhaDoOuvinte.get()).isNotNull();
    }

    @RestController
    @Validated
    static class Erros {

        @GetMapping("/erros/conflito")
        void conflito() {
            throw new ConflictException("CPF já cadastrado.", "CPF_REPETIDO");
        }

        @GetMapping("/erros/proibido")
        void proibido() {
            throw new ForbiddenException("Sem permissão.");
        }

        @GetMapping("/erros/invalido")
        void invalido() {
            throw new BadRequestException("Dado inválido.");
        }

        @GetMapping("/erros/limite")
        void limite() {
            throw new LimiteLoginException(30);
        }

        @GetMapping("/erros/lock")
        void lock() {
            throw new OptimisticLockingFailureException("versão antiga");
        }

        @PostMapping("/erros/corpo")
        void corpo(@RequestBody @jakarta.validation.Valid Pedido pedido) {
            assertThat(pedido).isNotNull();
        }

        @GetMapping("/erros/corpo-excedido")
        void corpoExcedido() throws org.springframework.http.converter.HttpMessageNotReadableException {
            throw new org.springframework.http.converter.HttpMessageNotReadableException(
                    "corpo", new LimiteCorpoFilter.CorpoExcedidoIOException(),
                    new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));
        }

        @GetMapping("/erros/inesperado")
        void inesperado() {
            throw new IllegalStateException("Key (email)=(maria@exemplo.com) já existe");
        }
    }

    record Pedido(@NotBlank String nome) {
    }
}
