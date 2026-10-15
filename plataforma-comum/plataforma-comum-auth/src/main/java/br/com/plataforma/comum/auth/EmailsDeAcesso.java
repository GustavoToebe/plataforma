package br.com.plataforma.comum.auth;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Os dois e-mails do módulo: redefinição de senha e convite. O conteúdo é fixo: o nome do aplicativo (da configuração), uma frase e
 * <b>só o link</b>. Nunca leva nome nem dado de aluno, paroquiano ou qualquer pessoa. O remetente vem de
 * {@code plataforma.auth.email.remetente}. O link do convite é o que o aplicativo já gera; o módulo não o guarda.
 */
public class EmailsDeAcesso {

    private static final Pattern ENDERECO = Pattern.compile("[^\\s@<>,;\"']{1,64}@[^\\s@<>,;\"']{1,190}\\.[^\\s@<>,;\"']{2,63}");

    private final EnviadorDeEmail enviador;
    private final String nomeApp;

    public EmailsDeAcesso(EnviadorDeEmail enviador, EmailProperties propriedades) {
        this.enviador = enviador;
        this.nomeApp = propriedades.nomeApp();
    }

    /**
     * @param para     endereço da conta, vindo do cadastro do sistema (nunca o texto que o usuário digitou no formulário)
     * @param link     link com o token no fragmento
     * @param validade quanto tempo o link vale, para o texto
     * @throws EnvioDeEmailException se o provedor não aceitou
     */
    public void enviarRedefinicao(String para, String link, Duration validade) {
        conferir(para, link);
        String prazo = descrever(validade);
        String texto = "Recebemos um pedido para redefinir a sua senha no " + nomeApp + ".\n\n"
                + "Para criar uma nova senha, abra o link abaixo. Ele vale por " + prazo + " e só pode ser usado uma vez.\n\n"
                + link + "\n\n"
                + "Se você não fez este pedido, ignore esta mensagem: a sua senha continua a mesma.";
        String html = "<p>Recebemos um pedido para redefinir a sua senha no " + escapar(nomeApp) + ".</p>"
                + "<p>Para criar uma nova senha, abra o link abaixo. Ele vale por " + prazo + " e só pode ser usado uma vez.</p>"
                + "<p><a href=\"" + escapar(link) + "\">" + escapar(link) + "</a></p>"
                + "<p>Se você não fez este pedido, ignore esta mensagem: a sua senha continua a mesma.</p>";
        enviador.enviar(new EnviadorDeEmail.Mensagem(para, "Redefinição de senha - " + nomeApp, texto, html));
    }

    /**
     * @param para endereço de quem foi convidado, como o sistema o conhece
     * @param link o link de convite que o aplicativo gerou (https; http só para localhost)
     * @throws EnvioDeEmailException se o provedor não aceitou
     */
    public void enviarConvite(String para, String link) {
        conferir(para, link);
        String texto = "Você recebeu um convite para acessar o " + nomeApp + ".\n\n"
                + "Para começar, abra o link abaixo.\n\n"
                + link + "\n\n"
                + "Se você não esperava este convite, ignore esta mensagem.";
        String html = "<p>Você recebeu um convite para acessar o " + escapar(nomeApp) + ".</p>"
                + "<p>Para começar, abra o link abaixo.</p>"
                + "<p><a href=\"" + escapar(link) + "\">" + escapar(link) + "</a></p>"
                + "<p>Se você não esperava este convite, ignore esta mensagem.</p>";
        enviador.enviar(new EnviadorDeEmail.Mensagem(para, "Convite - " + nomeApp, texto, html));
    }

    private static void conferir(String para, String link) {
        if (para == null || para.length() > 254 || !ENDERECO.matcher(para).matches()) {
            throw new IllegalArgumentException("Endereço de e-mail inválido.");
        }
        if (!EmailProperties.UrlsDeLink.valida(link)) {
            throw new IllegalArgumentException("O link do e-mail precisa ser https (http só para localhost).");
        }
    }

    private static String descrever(Duration validade) {
        long minutos = Math.max(1, (validade.toSeconds() + 59) / 60);
        if (minutos < 120) {
            return minutos + (minutos == 1 ? " minuto" : " minutos");
        }
        long horas = (minutos + 59) / 60;
        return horas + " horas";
    }

    private static String escapar(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
