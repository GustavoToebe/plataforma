package br.com.servirea.comum.login;
public class LimiteLoginException extends RuntimeException {
    private final long segundos;
    public LimiteLoginException(long segundos){super("Muitas tentativas de acesso. Aguarde e tente novamente.");this.segundos=segundos;}
    public long segundos(){return segundos;}
    public String getCodigo(){return "LOGIN_LIMITADO";}
}
