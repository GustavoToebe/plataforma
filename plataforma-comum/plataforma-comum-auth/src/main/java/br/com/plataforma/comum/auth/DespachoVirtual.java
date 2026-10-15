package br.com.plataforma.comum.auth;

/**
 * Despacho padrão: uma thread virtual por tarefa, sem requisição, sem contexto de segurança nem de tenant. A busca da conta que o
 * sistema passa a {@link RedefinicaoDeSenha#solicitar} roda aqui; se ela lançar, o erro só vira o nome da classe no log.
 */
final class DespachoVirtual implements DespachoDeEmail {

    @Override
    public void despachar(Runnable tarefa) {
        Thread.ofVirtual().name("plataforma-email").start(tarefa);
    }
}
