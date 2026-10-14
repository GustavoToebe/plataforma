package br.com.plataforma.comum.monitoramento;

import java.util.Locale;

/**
 * Escritor do texto Prometheus (versão 0.0.4) que os {@link MetricasDeDominio} usam. Rótulos são sempre agregados
 * (nível, canal, resultado...): nunca cliente, usuário, URI ou conteúdo.
 */
public final class SaidaPrometheus {

    private final StringBuilder texto = new StringBuilder();

    /** Declara o tipo da métrica ({@code gauge}, {@code counter}, {@code summary}); escreva uma vez por nome. */
    public SaidaPrometheus tipo(String nome, String tipo) {
        texto.append("# TYPE ").append(nome).append(' ').append(tipo).append('\n');
        return this;
    }

    /** Uma amostra sem rótulos. */
    public SaidaPrometheus amostra(String nome, double valor) {
        texto.append(nome).append(' ').append(formatar(valor)).append('\n');
        return this;
    }

    /** Uma amostra com um rótulo (ex.: {@code nivel="CRITICO"}). */
    public SaidaPrometheus amostra(String nome, String rotulo, String valorDoRotulo, double valor) {
        texto.append(nome).append('{').append(rotulo).append("=\"").append(escapar(valorDoRotulo)).append("\"} ")
                .append(formatar(valor)).append('\n');
        return this;
    }

    /** Uma amostra com dois rótulos. */
    public SaidaPrometheus amostra(String nome, String rotulo1, String valor1, String rotulo2, String valor2,
                                   double valor) {
        texto.append(nome).append('{').append(rotulo1).append("=\"").append(escapar(valor1)).append("\",")
                .append(rotulo2).append("=\"").append(escapar(valor2)).append("\"} ")
                .append(formatar(valor)).append('\n');
        return this;
    }

    /** Texto bruto já no formato Prometheus (usado pelo próprio módulo). */
    SaidaPrometheus bruto(String linha) {
        texto.append(linha);
        return this;
    }

    @Override
    public String toString() {
        return texto.toString();
    }

    private static String formatar(double valor) {
        if (valor == Math.rint(valor) && Math.abs(valor) < 1e15) {
            return Long.toString((long) valor);
        }
        return String.format(Locale.ROOT, "%s", valor);
    }

    private static String escapar(String valor) {
        return valor.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
