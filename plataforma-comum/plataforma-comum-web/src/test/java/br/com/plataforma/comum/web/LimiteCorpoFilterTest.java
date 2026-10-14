package br.com.plataforma.comum.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimiteCorpoFilterTest {

    private static final long LIMITE = 100;

    private final LimiteCorpoFilter filter = new LimiteCorpoFilter(JsonMapper.builder().build(), LIMITE);

    @Test
    void contentLengthAcimaDoLimiteRecusaAntesDeLerComErroPadrao() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/dados");
        request.setContent(new byte[(int) LIMITE + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean seguiu = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> seguiu.set(true));

        assertThat(seguiu).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"codigo\":\"CORPO_EXCEDIDO\"")
                .contains("\"path\":\"/dados\"")
                .contains("\"status\":413");
    }

    @Test
    void corpoNoLimiteSegue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/dados");
        request.setContent(new byte[(int) LIMITE]);
        AtomicBoolean seguiu = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), lerTudo(seguiu));

        assertThat(seguiu).isTrue();
    }

    @Test
    void semContentLengthALeituraQuePassaDoLimiteFalhaComIoDedicada() {
        MockHttpServletRequest chunked = semTamanho("/dados", LIMITE + 50);
        AtomicReference<Throwable> erro = new AtomicReference<>();

        assertThatThrownBy(() -> filter.doFilter(chunked, comprometida(), (req, res) -> {
            try {
                lerTudo(new AtomicBoolean()).doFilter(req, res);
            } catch (IOException e) {
                erro.set(e);
                throw e;
            }
        })).isInstanceOf(LimiteCorpoFilter.CorpoExcedidoIOException.class);
        assertThat(erro.get()).isInstanceOf(LimiteCorpoFilter.CorpoExcedidoIOException.class);
    }

    /**
     * Quem lê o corpo fora do Spring MVC (filtro de integração na cadeia de segurança) não passa pelo handler: o próprio
     * filtro responde 413 no formato padrão, em vez do 500 do contêiner.
     */
    @Test
    void estouroLidoForaDoMvcViraRespostaPadrao413() throws Exception {
        MockHttpServletRequest chunked = semTamanho("/integracao/v1/x", LIMITE + 50);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunked, response, (req, res) -> {
            try {
                lerTudo(new AtomicBoolean()).doFilter(req, res);
            } catch (IOException e) {
                throw new ServletException("filtro de terceiro embrulhou", e);
            }
        });

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"codigo\":\"CORPO_EXCEDIDO\"");
    }

    @Test
    void getReaderTambemRespeitaOLimite() {
        MockHttpServletRequest chunked = semTamanho("/dados", LIMITE + 50);
        AtomicReference<Throwable> erro = new AtomicReference<>();

        assertThatThrownBy(() -> filter.doFilter(chunked, comprometida(), (req, res) -> {
            try (BufferedReader leitor = req.getReader()) {
                char[] buffer = new char[16];
                while (leitor.read(buffer, 0, buffer.length) >= 0) {
                    // só consome
                }
            } catch (IOException e) {
                erro.set(e);
                throw e;
            }
        })).isInstanceOf(LimiteCorpoFilter.CorpoExcedidoIOException.class);
        assertThat(erro.get()).isInstanceOf(LimiteCorpoFilter.CorpoExcedidoIOException.class);
    }

    /** Upload de formulário sempre manda Content-Length: segue os limites do multipart, não o do filtro. */
    @Test
    void multipartComContentLengthAcimaDoLimiteSegue() throws Exception {
        MockHttpServletRequest multipart = new MockHttpServletRequest("POST", "/upload");
        multipart.setContentType("multipart/form-data; boundary=x");
        multipart.setContent(new byte[(int) LIMITE * 10]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean seguiu = new AtomicBoolean();

        filter.doFilter(multipart, response, lerTudo(seguiu));

        assertThat(seguiu).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** Resposta já enviada: não dá para trocar por 413, então o erro sobe como antes. */
    private static MockHttpServletResponse comprometida() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        return response;
    }

    @Test
    void multipartNaoPassaPeloLimiteDoFiltro() throws Exception {
        MockHttpServletRequest multipart = semTamanho("/upload", LIMITE * 10);
        multipart.setContentType("multipart/form-data; boundary=x");
        AtomicBoolean seguiu = new AtomicBoolean();

        filter.doFilter(multipart, new MockHttpServletResponse(), lerTudo(seguiu));

        assertThat(seguiu).isTrue();
    }

    /** Requisição que não declara o tamanho do corpo (chunked). */
    private static MockHttpServletRequest semTamanho(String caminho, long bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", caminho) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[(int) bytes]);
        return request;
    }

    private static FilterChain lerTudo(AtomicBoolean seguiu) {
        return (req, res) -> {
            try (var in = req.getInputStream()) {
                byte[] buffer = new byte[16];
                while (in.read(buffer, 0, buffer.length) >= 0) {
                    // só consome
                }
            }
            seguiu.set(true);
        };
    }
}
