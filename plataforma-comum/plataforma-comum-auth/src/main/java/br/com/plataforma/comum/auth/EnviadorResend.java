package br.com.plataforma.comum.auth;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Envio pelo Resend (HTTP, sem SDK): {@code POST} com {@code Authorization: Bearer} e corpo {@code from/to/subject/text/html}.
 * Prazos de conexão e de resposta e nenhum redirecionamento seguido. A chave vem de {@code plataforma.auth.email.resend.api-key}
 * (variável de ambiente, sem valor padrão). Nenhuma falha leva destinatário, chave ou o corpo da resposta (o provedor pode
 * devolver o endereço) para a mensagem ou para o log.
 */
final class EnviadorResend implements EnviadorDeEmail {

    private final RestClient cliente;
    private final String url;
    private final String chave;
    private final String remetente;

    EnviadorResend(EmailProperties propriedades) {
        EmailProperties.Resend resend = propriedades.resend();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(resend.timeoutConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
        fabrica.setReadTimeout(resend.timeoutResposta());
        this.cliente = RestClient.builder().requestFactory(fabrica).build();
        this.url = resend.url();
        this.chave = resend.apiKey();
        this.remetente = propriedades.remetente();
    }

    @Override
    public void enviar(Mensagem mensagem) {
        Map<String, Object> corpo = Map.of(
                "from", remetente,
                "to", List.of(mensagem.para()),
                "subject", mensagem.assunto(),
                "text", mensagem.texto(),
                "html", mensagem.html());
        try {
            ResponseEntity<Void> resposta = cliente.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + chave)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo)
                    .retrieve()
                    .toBodilessEntity();
            // O retrieve() só trata 4xx e 5xx como erro; um 3xx (redirecionamento não seguido) também não é um envio aceito.
            if (!resposta.getStatusCode().is2xxSuccessful()) {
                throw new EnvioDeEmailException("O provedor de e-mail não aceitou o envio (HTTP " + resposta.getStatusCode().value() + ").");
            }
        } catch (RestClientResponseException e) {
            throw new EnvioDeEmailException("O provedor de e-mail recusou o envio (HTTP " + e.getStatusCode().value() + ").");
        } catch (RestClientException e) {
            throw new EnvioDeEmailException("Não foi possível falar com o provedor de e-mail (" + e.getClass().getSimpleName() + ").");
        }
    }
}
