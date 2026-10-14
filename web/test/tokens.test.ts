import { describe, expect, it } from 'vitest';

import { blocos, microBadge, significado, tabela, transicaoMs } from '../src/tokens';

const significados = ['positivo', 'atencao', 'problema', 'encerrado'] as const;
const temas = ['claro', 'escuro'] as const;

describe('tokens dos 3 pilares', () => {
  it('Pilar 1: quatro significados, cada um com texto, fundo e borda nos dois temas', () => {
    expect(Object.keys(significado).sort()).toEqual([...significados].sort());
    for (const nome of significados) {
      for (const tema of temas) {
        const cores = significado[nome][tema];
        expect(cores.texto, `${nome}/${tema}`).toMatch(/^#[0-9a-f]{6}$/);
        expect(cores.fundo, `${nome}/${tema}`).toMatch(/^(#[0-9a-f]{6}|rgba\(.+\))$/);
        expect(cores.borda, `${nome}/${tema}`).toMatch(/^rgba\(.+\)$/);
      }
    }
  });

  it('Pilar 1: mesmo status, mesma cor; cada significado tem a sua', () => {
    for (const tema of temas) {
      const textos = significados.map((nome) => significado[nome][tema].texto);
      expect(new Set(textos).size).toBe(4);
    }
  });

  it('Pilar 1: valores do escuro da Central', () => {
    expect(significado.positivo.escuro).toEqual({
      texto: '#34d399',
      fundo: 'rgba(16, 185, 129, 0.15)',
      borda: 'rgba(16, 185, 129, 0.32)',
    });
    expect(significado.atencao.escuro.texto).toBe('#fbbf24');
    expect(significado.problema.escuro.texto).toBe('#fb7185');
    expect(significado.encerrado.escuro).toEqual({
      texto: '#94a3b8',
      fundo: 'rgba(148, 163, 184, 0.12)',
      borda: 'rgba(148, 163, 184, 0.25)',
    });
  });

  it('Pilar 3: micro-badge de 11 px, caixa-alta, 0.04em', () => {
    expect(microBadge.tamanhoFontePx).toBe(11);
    expect(microBadge.caixaAlta).toBe(true);
    expect(microBadge.espacamentoLetras).toBe('0.04em');
  });

  it('Pilar 2: cartão de 16 px com borda de 1 px e desfoque de 12 px', () => {
    expect(blocos).toMatchObject({ raioPx: 16, bordaPx: 1, desfoquePx: 12 });
    expect(blocos.raioInternoPx).toBeLessThan(blocos.raioPx);
  });

  it('Pilar 3: tabela com cabeçalho de 45 px e linha de no mínimo 55 px; transições de 120 a 200 ms', () => {
    expect(tabela.alturaDoCabecalhoPx).toBe(45);
    expect(tabela.alturaMinimaDaLinhaPx).toBe(55);
    expect(tabela.tamanhoFonteDoCabecalhoPx).toBe(11);
    expect(transicaoMs).toEqual({ minimo: 120, maximo: 200 });
  });
});
