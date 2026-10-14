/**
 * Compara origem e fronteira do caminho antes de anexar credenciais. Só a própria API recebe token, cookie e XSRF:
 * nada de `startsWith` na URL inteira, que aceitaria `https://api.exemplo.test.evil.test` ou `/apifalso`.
 *
 * `base` resolve URLs relativas: na web é a origem da página (`window.location.origin`); no nativo, a própria URL da
 * API (absoluta). Devolve o caminho dentro da API (`/alunos`) ou `null` se o destino não é a API.
 */
export function caminhoDaApi(destino: string, api: string, base: string): string | null {
  try {
    const raiz = new URL(api, base);
    const url = new URL(destino, base);
    const prefixo = raiz.pathname.replace(/\/+$/, '');
    if (url.origin !== raiz.origin || url.username || url.password) return null;
    if (prefixo && url.pathname !== prefixo && !url.pathname.startsWith(`${prefixo}/`)) return null;
    return url.pathname.slice(prefixo.length) || '/';
  } catch {
    return null;
  }
}
