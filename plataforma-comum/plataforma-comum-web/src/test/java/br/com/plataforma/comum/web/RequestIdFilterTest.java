package br.com.plataforma.comum.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void geraUmRequestIdQuandoOCabecalhoNaoVem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> durante = new AtomicReference<>();
        FilterChain chain = (req, res) -> durante.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(new MockHttpServletRequest("GET", "/qualquer"), response, chain);

        String devolvido = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(devolvido).matches("[0-9a-f-]{36}");
        assertThat(durante.get()).isEqualTo(devolvido);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("o MDC não vaza para a próxima requisição").isNull();
    }

    @Test
    void aproveitaORequestIdSeguroDoCliente() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/qualquer");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "id-vindo-do-cliente_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("id-vindo-do-cliente_123");
    }

    @Test
    void cabecalhoLongoOuComCaracterePerigosoViraUuid() throws Exception {
        for (String valor : new String[] {"x".repeat(65), "linha\nforjada", "nome com espaços", ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/teste");
            request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, valor);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).matches("[0-9a-f-]{36}");
            assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        }
    }

    @Test
    void limpaOMdcMesmoQuandoACadeiaFalha() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain falha = (req, res) -> {
            throw new IllegalStateException("falha da cadeia");
        };

        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, falha);
        } catch (Exception esperado) {
            assertThat(esperado).isInstanceOf(IllegalStateException.class);
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
