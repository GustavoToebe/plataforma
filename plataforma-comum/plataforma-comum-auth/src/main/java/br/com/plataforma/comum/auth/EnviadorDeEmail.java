package br.com.plataforma.comum.auth;

/**
 * Envio de e-mail do módulo. As implementações são {@code EnviadorResend} (HTTP) e {@code EnviadorRegistro} (só registra que houve
 * envio, para dev e teste). Um bean do sistema do mesmo tipo toma o lugar das duas, o que serve para um teste do sistema capturar
 * o link. Quem monta o texto é o {@link EmailsDeAcesso}: o módulo só envia o link e um texto fixo.
 */
public interface EnviadorDeEmail {

    /** Tamanho máximo do nome de quem envia (em caracteres). */
    int NOME_REMETENTE_MAXIMO = 60;

    /**
     * Nome de quem envia aceito: não vazio, até {@value #NOME_REMETENTE_MAXIMO} caracteres e sem nenhum que
     * {@link #proibidoNoNomeDoRemetente} recuse. É a regra única: quem valida antes de montar a {@link Mensagem} chama esta.
     */
    static boolean nomeValido(String nome) {
        return nome != null && !nome.isBlank() && nome.codePointCount(0, nome.length()) <= NOME_REMETENTE_MAXIMO
                && nome.codePoints().noneMatch(EnviadorDeEmail::proibidoNoNomeDoRemetente);
    }

    /**
     * Caractere que o nome do remetente recusa: controle, quebra ou separador de linha, {@code < > " \}, e a categoria de
     * formatação do Unicode (bidi, largura zero, {@code U+FEFF}), que esconde ou inverte o texto.
     */
    static boolean proibidoNoNomeDoRemetente(int codePoint) {
        int tipo = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || codePoint == '<' || codePoint == '>' || codePoint == '"' || codePoint == '\\'
                || tipo == Character.LINE_SEPARATOR || tipo == Character.PARAGRAPH_SEPARATOR || tipo == Character.FORMAT;
    }

    /**
     * Texto e HTML já prontos; {@code toString} não mostra nada, para a mensagem não chegar a um log.
     *
     * @param nomeRemetente opcional: quem pediu o envio (o {@code from} vira {@code "<nome> via <app>" <endereço>}); recusa CR, LF,
     *                      {@code <}, {@code >}, aspas, barra invertida, caractere de controle e de formatação, até
     *                      {@value #NOME_REMETENTE_MAXIMO} caracteres
     * @param responderPara opcional: vira o {@code reply_to}; mesmo formato de endereço dos e-mails de acesso
     * @param remetente     opcional: endereço usado no {@code from} no lugar do endereço padrão; mesmo formato de endereço
     * @throws IllegalArgumentException (sem repetir o valor) se um dos três opcionais for inválido
     */
    record Mensagem(String para, String assunto, String texto, String html,
                    String nomeRemetente, String responderPara, String remetente) {

        public Mensagem {
            if (nomeRemetente != null && !nomeValido(nomeRemetente)) {
                throw new IllegalArgumentException("O nome do remetente não pode ser vazio, passar de " + NOME_REMETENTE_MAXIMO
                        + " caracteres nem ter quebra de linha, < >, aspas, barra invertida ou caractere de controle.");
            }
            if (responderPara != null && !EmailsDeAcesso.enderecoValido(responderPara)) {
                throw new IllegalArgumentException("O endereço de resposta é inválido.");
            }
            if (remetente != null && !EmailsDeAcesso.enderecoValido(remetente)) {
                throw new IllegalArgumentException("O endereço do remetente é inválido.");
            }
        }

        /** Mensagem sem remetente próprio (e-mails de acesso): o {@code from} é o da configuração. */
        public Mensagem(String para, String assunto, String texto, String html) {
            this(para, assunto, texto, html, null, null, null);
        }

        @Override
        public String toString() {
            return "Mensagem[***]";
        }
    }

    /** @throws EnvioDeEmailException se o provedor não aceitou a mensagem */
    void enviar(Mensagem mensagem);
}
