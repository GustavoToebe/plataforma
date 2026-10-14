/**
 * Lógica do cliente de autenticação, sem HTTP próprio: renovação compartilhada num 401 (uma por vez, as outras esperam),
 * XSRF (`X-XSRF-TOKEN` só para a própria API, só em escrita) e quando desistir e encerrar a sessão. Quem faz a requisição
 * é o app (`HttpClient` no Angular, `fetch` no Expo); o app também informa o nome do próprio cookie, as rotas que não
 * renovam e os códigos de erro que não são sessão expirada.
 */

export const CABECALHO_XSRF = 'X-XSRF-TOKEN';

/** Métodos que o CSRF por cookie não confere (os mesmos do `PoliticaCsrf` do módulo auth). */
const METODOS_SEGUROS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

export function metodoPrecisaXsrf(metodo: string): boolean {
  return !METODOS_SEGUROS.has(metodo.toUpperCase());
}

/** Valor de um cookie a partir de `document.cookie` (ou de qualquer texto `a=1; b=2`); `null` se não existe. */
export function lerCookie(cookies: string, nome: string): string | null {
  for (const parte of cookies.split(';')) {
    const [chave, ...valor] = parte.trim().split('=');
    if (chave === nome) {
      const bruto = valor.join('=');
      try {
        return decodeURIComponent(bruto);
      } catch {
        return bruto;
      }
    }
  }
  return null;
}

export interface Credenciais {
  /** Access token em memória, se houver. */
  token?: string | null;
  /** Valor do cookie XSRF, se houver (só na web). */
  xsrf?: string | null;
  metodo: string;
}

/**
 * Cabeçalhos de credencial de uma requisição **que já se sabe ser da própria API** (confira com `caminhoDaApi`):
 * `Authorization: Bearer` quando há token e `X-XSRF-TOKEN` quando há cookie e o método é de escrita.
 */
export function cabecalhosDeCredencial({ token, xsrf, metodo }: Credenciais): Record<string, string> {
  const cabecalhos: Record<string, string> = {};
  if (token) cabecalhos.Authorization = `Bearer ${token}`;
  if (xsrf && metodoPrecisaXsrf(metodo)) cabecalhos[CABECALHO_XSRF] = xsrf;
  return cabecalhos;
}

/**
 * Uma execução por vez: chamadas simultâneas esperam a mesma promessa e, quando ela termina (bem ou mal), a próxima
 * chamada começa de novo. É a renovação compartilhada: o refresh gira o token, então um segundo refresh em paralelo
 * chegaria com o token já revogado.
 */
export function umaPorVez<T>(tarefa: () => Promise<T>): () => Promise<T> {
  let emAndamento: Promise<T> | null = null;
  return () => {
    if (!emAndamento) {
      emAndamento = new Promise<T>((resolver) => resolver(tarefa())).finally(() => {
        emAndamento = null;
      });
    }
    return emAndamento;
  };
}

export interface ErroDeResposta {
  status: number;
  codigo?: string | null;
  /** Caminho dentro da API (`caminhoDaApi`), para a lista de rotas que não renovam. */
  caminho?: string;
}

export interface RegrasDeRenovacao {
  /**
   * Rotas que nunca renovam (login, refresh, logout...). `/auth/refresh` casa só com ela mesma; termina em `/`
   * (`/auth/`, `/public/`) casa com tudo abaixo.
   */
  rotasSemRenovar?: readonly string[];
  /**
   * Códigos de erro que não são sessão expirada (conflito de edição, código MFA errado...). Termina em `*`
   * (`MFA_*`) casa por prefixo.
   */
  codigosSemRenovar?: readonly string[];
}

function rotaCasa(caminho: string, rota: string): boolean {
  return rota.endsWith('/') ? caminho.startsWith(rota) : caminho === rota || caminho.startsWith(`${rota}/`);
}

function codigoCasa(codigo: string, padrao: string): boolean {
  return padrao.endsWith('*') ? codigo.startsWith(padrao.slice(0, -1)) : codigo === padrao;
}

/** Um 401 pede renovação, exceto em rota de sessão e em erro de regra que também veio como 401. */
export function deveRenovar(erro: ErroDeResposta, regras: RegrasDeRenovacao = {}): boolean {
  if (erro.status !== 401) return false;
  const { caminho, codigo } = erro;
  if (caminho !== undefined && regras.rotasSemRenovar?.some((rota) => rotaCasa(caminho, rota))) return false;
  return !(codigo && regras.codigosSemRenovar?.some((padrao) => codigoCasa(codigo, padrao)));
}

export interface OpcoesDeRenovacao<R> {
  /** Decide, olhando a resposta, se ela pede renovação (em geral com `deveRenovar`). */
  deveRenovar: (resposta: R) => boolean | Promise<boolean>;
  /** A renovação (use `umaPorVez`). `true` se a sessão foi renovada, `false` se o servidor a recusou. */
  renovar: () => Promise<boolean>;
  /** Chamada quando o servidor recusa a renovação: é hora de encerrar a sessão. */
  aoEncerrar: () => void;
}

/**
 * Envia, e se a resposta pedir renovação: renova e repete **uma vez**. Renovação recusada chama `aoEncerrar` e devolve a
 * resposta original. Erro ao renovar (sem rede, por exemplo) propaga e **não** encerra a sessão.
 */
export async function comRenovacao<R>(enviar: () => Promise<R>, opcoes: OpcoesDeRenovacao<R>): Promise<R> {
  const resposta = await enviar();
  if (!(await opcoes.deveRenovar(resposta))) return resposta;
  if (await opcoes.renovar()) return enviar();
  opcoes.aoEncerrar();
  return resposta;
}

export type DesfechoDoRefresh = 'renovada' | 'encerrar' | 'falhou';

/**
 * O que a resposta do refresh diz: 2xx renovou; 401 e 403 são recusa (encerrar a sessão); qualquer outra coisa (5xx,
 * 429...) é falha do servidor ou da rede, e a sessão continua para tentar de novo depois.
 */
export function desfechoDoRefresh(status: number): DesfechoDoRefresh {
  if (status >= 200 && status < 300) return 'renovada';
  return status === 401 || status === 403 ? 'encerrar' : 'falhou';
}
