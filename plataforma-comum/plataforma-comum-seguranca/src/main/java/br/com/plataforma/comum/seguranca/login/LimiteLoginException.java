package br.com.plataforma.comum.seguranca.login;
public class LimiteLoginException extends RuntimeException {
    private final long segundos;
    private final boolean primeiroBloqueio;
    public LimiteLoginException(long segundos){this(segundos,false);}
    /** @param primeiroBloqueio a tentativa foi exatamente a de número limite+1 no IP ou na conta (abre o bloqueio) */
    public LimiteLoginException(long segundos,boolean primeiroBloqueio){super("Muitas tentativas de acesso. Aguarde e tente novamente.");this.segundos=segundos;this.primeiroBloqueio=primeiroBloqueio;}
    public long segundos(){return segundos;}
    /**
     * Verdadeiro uma única vez por chave e janela: a tentativa que cruza o limite (a de número limite+1, no IP ou na conta). As
     * seguintes da mesma janela só recebem o 429. Serve para o sistema auditar o bloqueio sem gravar uma linha por tentativa.
     */
    public boolean primeiroBloqueio(){return primeiroBloqueio;}
    public String getCodigo(){return "LOGIN_LIMITADO";}
}
