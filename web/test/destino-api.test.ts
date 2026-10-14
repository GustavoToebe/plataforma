import { describe, expect, it } from 'vitest';

import { caminhoDaApi } from '../src/destino-api';

/** Mesmos casos do `destino-api.spec.ts` dos fronts Angular e do `destino-api.test.ts` do consultoria-app. */
describe('destino da API', () => {
  const api = 'https://app.exemplo.test/api';
  const base = 'https://app.exemplo.test';

  it('aceita apenas a mesma origem com fronteira de caminho', () => {
    expect(caminhoDaApi(`${api}/pessoas?pagina=2`, api, base)).toBe('/pessoas');
    expect(caminhoDaApi(api, api, base)).toBe('/');
    expect(caminhoDaApi(`${api}/pessoas`, `${api}/`, base)).toBe('/pessoas');
  });

  it('recusa host parecido, protocolo, porta e caminho adjacente', () => {
    expect(caminhoDaApi('https://app.exemplo.test.evil.test/api/pessoas', api, base)).toBeNull();
    expect(caminhoDaApi('http://app.exemplo.test/api/pessoas', api, base)).toBeNull();
    expect(caminhoDaApi('https://app.exemplo.test:8443/api/pessoas', api, base)).toBeNull();
    expect(caminhoDaApi('https://app.exemplo.test/apifalso/pessoas', api, base)).toBeNull();
    expect(caminhoDaApi('https://app.exemplo.test/api/../fora', api, base)).toBeNull();
  });

  it('recusa userinfo mesmo com a origem correta', () => {
    expect(caminhoDaApi('https://usuario:senha@app.exemplo.test/api/pessoas', api, base)).toBeNull();
    expect(caminhoDaApi('https://usuario@app.exemplo.test/api/pessoas', api, base)).toBeNull();
  });

  it('suporta API na raiz e URLs relativas da mesma origem', () => {
    expect(caminhoDaApi('https://api.exemplo.test/pessoas', 'https://api.exemplo.test', base)).toBe('/pessoas');
    expect(caminhoDaApi('/api/pessoas', '/api', base)).toBe('/pessoas');
    expect(caminhoDaApi('/apifalso/pessoas', '/api', base)).toBeNull();
  });

  it('no nativo, com a API absoluta por IP da rede, recusa o YouTube e outro IP', () => {
    const nativo = 'http://192.168.0.10:8088/api';
    expect(caminhoDaApi(`${nativo}/aluno/painel`, nativo, nativo)).toBe('/aluno/painel');
    expect(caminhoDaApi('https://www.youtube.com/watch?v=1', nativo, nativo)).toBeNull();
    expect(caminhoDaApi('http://192.168.0.11:8088/api/aluno/painel', nativo, nativo)).toBeNull();
  });

  it('URL que não dá para interpretar não é da API', () => {
    expect(caminhoDaApi('http://', api, base)).toBeNull();
    expect(caminhoDaApi('/api/pessoas', '/api', 'não é uma url')).toBeNull();
  });
});
