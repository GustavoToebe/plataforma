/**
 * Máscaras e validações de documentos e contatos. São as mesmas regras do `web/Formatos.java` dos backends, que é quem
 * decide: aqui elas só avisam antes de enviar e deixam o campo no formato em que vai ser gravado. As funções `formatar*`
 * aceitam texto pela metade (máscara enquanto digita). Os casos de teste estão em `test/fixtures/formatos.json`.
 *
 * Fora do pacote, de propósito: RG e sexo (só o Servirea usa).
 */

export const UFS = [
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA',
  'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
] as const;

export type Mascara = 'cpf' | 'cnpj' | 'cep' | 'telefone';

type Texto = string | null | undefined;

/** Mesma expressão de `Formatos.EMAIL` (Java): exige domínio com ponto. */
export const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function somenteDigitos(valor: Texto): string {
  return (valor ?? '').replace(/\D/g, '');
}

/** Letras e dígitos em maiúsculas, para o CNPJ alfanumérico. */
function somenteAlfanumericos(valor: Texto): string {
  return (valor ?? '').toUpperCase().replace(/[^0-9A-Z]/g, '');
}

/** Encaixa os caracteres nos grupos, parando onde o texto acaba. */
function encaixar(caracteres: string, grupos: readonly (readonly [number, string])[]): string {
  let saida = '';
  let posicao = 0;
  for (const [tamanho, separador] of grupos) {
    if (posicao >= caracteres.length) break;
    if (posicao > 0) saida += separador;
    saida += caracteres.slice(posicao, posicao + tamanho);
    posicao += tamanho;
  }
  return saida;
}

export function formatarCpf(valor: Texto): string {
  return encaixar(somenteDigitos(valor).slice(0, 11), [[3, ''], [3, '.'], [3, '.'], [2, '-']]);
}

/** CNPJ numérico ou alfanumérico (Receita Federal, a partir de julho de 2026). */
export function formatarCnpj(valor: Texto): string {
  return encaixar(somenteAlfanumericos(valor).slice(0, 14), [[2, ''], [3, '.'], [3, '.'], [4, '/'], [2, '-']]);
}

export function formatarCep(valor: Texto): string {
  return encaixar(somenteDigitos(valor).slice(0, 8), [[5, ''], [3, '-']]);
}

/** Tira o +55 de 12 ou 13 dígitos, como o `Formatos.telefone` do Java. */
function semPais(digitos: string): string {
  return (digitos.length === 12 || digitos.length === 13) && digitos.startsWith('55') ? digitos.slice(2) : digitos;
}

export function formatarTelefone(valor: Texto): string {
  const d = semPais(somenteDigitos(valor)).slice(0, 11);
  if (!d) return '';
  if (d.length <= 2) return `(${d}`;
  const ddd = d.slice(0, 2);
  const corpo = d.slice(2);
  const meio = d.length === 11 ? 5 : 4;
  return corpo.length <= meio ? `(${ddd}) ${corpo}` : `(${ddd}) ${corpo.slice(0, meio)}-${corpo.slice(meio)}`;
}

export function formatar(mascara: Mascara, valor: Texto): string {
  switch (mascara) {
    case 'cpf':
      return formatarCpf(valor);
    case 'cnpj':
      return formatarCnpj(valor);
    case 'cep':
      return formatarCep(valor);
    case 'telefone':
      return formatarTelefone(valor);
  }
}

function todosIguais(texto: string): boolean {
  return [...texto].every((c) => c === texto.charAt(0));
}

/** CPF com dígitos verificadores; recusa os onze dígitos iguais. */
export function cpfValido(valor: Texto): boolean {
  const d = somenteDigitos(valor);
  if (d.length !== 11 || todosIguais(d)) return false;
  const verificador = (tamanho: number): number => {
    let soma = 0;
    for (let i = 0; i < tamanho; i++) soma += Number(d.charAt(i)) * (tamanho + 1 - i);
    const resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  };
  return verificador(9) === Number(d.charAt(9)) && verificador(10) === Number(d.charAt(10));
}

/**
 * CNPJ numérico ou alfanumérico: as 12 primeiras posições aceitam 0-9 e A-Z, os dois dígitos verificadores são
 * numéricos e cada caractere vale o código ASCII menos 48.
 */
export function cnpjValido(valor: Texto): boolean {
  const c = somenteAlfanumericos(valor);
  if (!/^[0-9A-Z]{12}[0-9]{2}$/.test(c) || todosIguais(c)) return false;
  const verificador = (tamanho: number): number => {
    let soma = 0;
    let peso = tamanho - 7;
    for (let i = 0; i < tamanho; i++) {
      soma += (c.charCodeAt(i) - 48) * peso;
      peso = peso === 2 ? 9 : peso - 1;
    }
    const resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  };
  return verificador(12) === Number(c.charAt(12)) && verificador(13) === Number(c.charAt(13));
}

export function cepValido(valor: Texto): boolean {
  return somenteDigitos(valor).length === 8;
}

/** Fixo com DDD (10 dígitos, terceiro entre 2 e 8) ou celular (11 dígitos, terceiro 9). Aceita o +55 na frente. */
export function telefoneValido(valor: Texto): boolean {
  const d = semPais(somenteDigitos(valor));
  if (d.length < 10 || d.startsWith('0') || d.charAt(1) === '0') return false;
  const terceiro = d.charAt(2);
  return d.length === 10 ? terceiro >= '2' && terceiro <= '8' : d.length === 11 && terceiro === '9';
}

export function emailValido(valor: Texto): boolean {
  return EMAIL.test((valor ?? '').trim());
}

export function ufValida(valor: Texto): boolean {
  return (UFS as readonly string[]).includes((valor ?? '').trim().toUpperCase());
}
