package br.com.plataforma.comum.auth;

/**
 * Onde roda o trabalho do pedido de "esqueci a senha" (achar a conta, gravar o link e enviar). Por padrão, numa thread virtual: a
 * resposta ao pedido sai antes do envio e leva o mesmo tempo exista a conta ou não, então o tempo não revela quais e-mails estão
 * cadastrados. Um bean do sistema do mesmo tipo toma o lugar (um teste usa um despacho que roda na hora).
 */
public interface DespachoDeEmail {

    void despachar(Runnable tarefa);
}
