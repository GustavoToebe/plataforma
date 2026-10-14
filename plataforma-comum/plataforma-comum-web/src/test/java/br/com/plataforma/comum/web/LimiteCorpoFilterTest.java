package br.com.plataforma.comum.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicBoolean;

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

        assertThatThrownBy(() -> filter.doFilter(chunked, new MockHttpServletResponse(), lerTudo(new AtomicBoolean())))
                .isInstanceOf(LimiteCorpoFilter.CorpoExcedidoIOException.class);
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
