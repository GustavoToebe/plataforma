package br.com.plataforma.comum.auth;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
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
    private final String nomeApp;

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
        this.nomeApp = propriedades.nomeApp();
    }

    /**
     * Sem nome nem remetente próprio, o {@code from} da configuração, sem mudar um byte. Com nome: {@code "<nome> via <app>" <endereço>}
     * (o nome de exibição vai entre aspas, para vírgula, dois-pontos ou {@code @} não quebrarem o cabeçalho). Só com remetente
     * próprio: o nome de exibição da configuração (se houver, como o operador o escreveu) com o endereço novo.
     */
    private String de(Mensagem mensagem) {
        if (mensagem.nomeRemetente() == null && mensagem.remetente() == null) {
            return remetente;
        }
        int abre = remetente.lastIndexOf('<');
        int fecha = remetente.lastIndexOf('>');
        boolean comNome = abre >= 0 && fecha > abre;
        String endereco = mensagem.remetente() != null ? mensagem.remetente()
                : comNome ? remetente.substring(abre + 1, fecha).trim() : remetente.trim();
        if (mensagem.nomeRemetente() != null) {
            // O nome da Mensagem já não tem \ nem "; o nomeApp vem da configuração e pode ter.
            String nomeCompleto = (mensagem.nomeRemetente() + " via " + nomeApp).replace("\\", "\\\\").replace("\"", "\\\"");
            return "\"" + nomeCompleto + "\" <" + endereco + ">";
        }
        String exibicao = comNome ? remetente.substring(0, abre).trim() : "";
        return exibicao.isEmpty() ? endereco : exibicao + " <" + endereco + ">";
    }

    @Override
    public void enviar(Mensagem mensagem) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("from", de(mensagem));
        corpo.put("to", List.of(mensagem.para()));
        corpo.put("subject", mensagem.assunto());
        corpo.put("text", mensagem.texto());
        corpo.put("html", mensagem.html());
        if (mensagem.responderPara() != null) {
            corpo.put("reply_to", List.of(mensagem.responderPara()));
        }
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
