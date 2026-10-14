package com.fasterxml.jackson.databind;

/**
 * Só para o teste da regra {@code JACKSON_TRES}: uma classe no pacote do Jackson 2, sem trazer a dependência inteira para o
 * classpath de teste. A regra olha o pacote de quem é usado, e é isso que este arquivo simula.
 */
public final class MapeadorDeMentira {

    private MapeadorDeMentira() { }

    public static String ler() {
        return "jackson-2";
    }
}
