/**
 * Leitura do erro da API (`ApiError` dos backends). Sem HTTP e sem Angular: funciona com o `ErroApi` do cliente (Expo,
 * `fetch`) e, por duck typing, com o `HttpErrorResponse` do Angular (`{ status, error: corpo }`).
 */

export interface CampoComErro {
  field: string;
  message: string;
}

/** Corpo JSON de erro de todas as APIs da plataforma. */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  codigo: string | null;
  path: string;
  requestId: string | null;
  fieldErrors: CampoComErro[] | null;
}

export const MENSAGEM_SEM_CONEXAO = 'Sem conexão com o servidor. Confira a internet e tente de novo.';
export const MENSAGEM_ERRO_DO_SERVIDOR = 'O servidor teve um problema. Tente de novo.';
export const MENSAGEM_NAO_CONCLUIDO = 'Não foi possível concluir.';

/** Erro do cliente HTTP: `status` 0 é "sem conexão" (rede, servidor fora do ar ou tempo esgotado). */
export class ErroApi extends Error {
  constructor(
    readonly status: number,
    readonly codigo: string | null,
    mensagem: string,
    readonly campos: CampoComErro[] = [],
  ) {
    super(mensagem);
    this.name = 'ErroApi';
  }

  /** Sem rede, servidor fora do ar ou tempo esgotado: o app continua com o que tem salvo. */
  get semConexao(): boolean {
    return this.status === 0;
  }
}

/** Reconhece o `ErroApi` mesmo que venha de outra cópia do módulo (CJS e ESM carregados juntos). */
function ehErroApi(erro: unknown): erro is ErroApi {
  if (erro instanceof ErroApi) return true;
  return (
    erro instanceof Error &&
    erro.name === 'ErroApi' &&
    typeof (erro as Partial<ErroApi>).status === 'number' &&
    Array.isArray((erro as Partial<ErroApi>).campos)
  );
}

interface Leitura {
  status: number;
  message: string | null;
  codigo: string | null;
  campos: CampoComErro[];
}

function objeto(valor: unknown): Record<string, unknown> {
  return typeof valor === 'object' && valor !== null && !Array.isArray(valor) ? (valor as Record<string, unknown>) : {};
}

function texto(valor: unknown): string | null {
  return typeof valor === 'string' && valor !== '' ? valor : null;
}

function listaDeCampos(valor: unknown): CampoComErro[] | null {
  if (!Array.isArray(valor)) return null;
  const campos: CampoComErro[] = [];
  for (const item of valor) {
    const campo = objeto(item);
    const nome = texto(campo.field);
    const mensagem = texto(campo.message);
    if (nome && mensagem) campos.push({ field: nome, message: mensagem });
  }
  return campos;
}

/** Texto padrão de quem não recebeu um corpo de erro legível. */
export function mensagemDoStatus(status: number): string {
  return status >= 500 ? MENSAGEM_ERRO_DO_SERVIDOR : MENSAGEM_NAO_CONCLUIDO;
}

/**
 * Normaliza o corpo de uma resposta de erro. Corpo ausente, que não é JSON ou que não é um objeto vira o texto padrão do
 * status; campo com tipo errado é ignorado.
 */
export function corpoDoErro(json: unknown, status: number): ApiError {
  const corpo = objeto(json);
  return {
    timestamp: texto(corpo.timestamp) ?? '',
    status: typeof corpo.status === 'number' ? corpo.status : status,
    error: texto(corpo.error) ?? '',
    message: texto(corpo.message) ?? mensagemDoStatus(status),
    codigo: texto(corpo.codigo),
    path: texto(corpo.path) ?? '',
    requestId: texto(corpo.requestId),
    fieldErrors: listaDeCampos(corpo.fieldErrors),
  };
}

/** `ErroApi` de uma resposta com falha: `status` da resposta HTTP e corpo já lido (ou `undefined`). */
export function erroApiDaResposta(status: number, json: unknown): ErroApi {
  const corpo = corpoDoErro(json, status);
  return new ErroApi(status, corpo.codigo, corpo.message, corpo.fieldErrors ?? []);
}

function ler(erro: unknown): Leitura | null {
  if (ehErroApi(erro)) {
    return { status: erro.status, message: texto(erro.message), codigo: erro.codigo, campos: erro.campos };
  }
  if (typeof erro === 'object' && erro !== null && 'status' in erro && 'error' in erro) {
    const { status, error } = erro as { status: unknown; error: unknown };
    if (typeof status !== 'number') return null;
    const corpo = objeto(error);
    return {
      status,
      message: texto(corpo.message),
      codigo: texto(corpo.codigo),
      campos: listaDeCampos(corpo.fieldErrors) ?? [],
    };
  }
  return null;
}

/**
 * Mensagem para mostrar ao usuário, nesta ordem: `message` da API, a do primeiro campo inválido, o texto de sem conexão
 * (status 0) e o `fallback`. Erro que não veio da API usa sempre o `fallback`, nunca texto técnico.
 */
export function mensagemApi(erro: unknown, fallback: string): string {
  const leitura = ler(erro);
  if (!leitura) return fallback;
  if (leitura.message) return leitura.message;
  const campo = leitura.campos[0]?.message;
  if (campo) return campo;
  return leitura.status === 0 ? MENSAGEM_SEM_CONEXAO : fallback;
}

/** Código estável do erro (`CONFLITO_EDICAO`, `PLANO_INVALIDO`...), se veio. */
export function codigoApi(erro: unknown): string | null {
  return ler(erro)?.codigo ?? null;
}

/** Erros por campo (`fieldErrors`), indexados pelo nome do campo. */
export function errosPorCampo(erro: unknown): Record<string, string> {
  const mapa: Record<string, string> = {};
  for (const campo of ler(erro)?.campos ?? []) {
    mapa[campo.field] = campo.message;
  }
  return mapa;
}

/** Falta de conexão: a requisição nem chegou à API. */
export function erroDeConexao(erro: unknown): boolean {
  return ler(erro)?.status === 0;
}
