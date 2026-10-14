package br.com.plataforma.comum.monitoramento;

/**
 * Métricas do domínio de um sistema (ex.: instâncias por nível na Central, fila de envios no Servirea), escritas
 * depois das métricas comuns. Declare um bean deste tipo. Só agregados, sem dado pessoal nos rótulos. Se lançar uma
 * {@link RuntimeException}, o grupo é omitido e o resto da coleta continua (métrica de processo não pode cair junto
 * com um banco lento).
 */
@FunctionalInterface
public interface MetricasDeDominio {

    void escrever(SaidaPrometheus saida);
}
