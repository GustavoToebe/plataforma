/**
 * Tokens dos 3 pilares do design (cor com significado, blocos modulares, SaaS limpo), do `docs/design-system.md` dos
 * fronts. A marca continua por app e fica fora daqui: a cor da marca nunca diz status.
 *
 * - Escuro: valores da Central (`src/styles.scss`), que o design system aponta como referência.
 * - Claro: fundo e texto dos utilitários Tailwind da tabela do Pilar 1 (`50` e `700`; `100` e `600` no neutro); a borda
 *   segue a regra do Pilar 3 (a cor do texto a 25–32%).
 * - Não há escala de espaçamento no design system: só as medidas que ele fixa (`blocos`, `tabela`, `transicaoMs`).
 */

export type Significado = 'positivo' | 'atencao' | 'problema' | 'encerrado';
export type TemaDeCor = 'claro' | 'escuro';

export interface CoresDoSignificado {
  texto: string;
  fundo: string;
  borda: string;
}

export const significado: Readonly<Record<Significado, Readonly<Record<TemaDeCor, CoresDoSignificado>>>> = {
  positivo: {
    claro: { texto: '#047857', fundo: '#ecfdf5', borda: 'rgba(4, 120, 87, 0.32)' },
    escuro: { texto: '#34d399', fundo: 'rgba(16, 185, 129, 0.15)', borda: 'rgba(16, 185, 129, 0.32)' },
  },
  atencao: {
    claro: { texto: '#b45309', fundo: '#fffbeb', borda: 'rgba(180, 83, 9, 0.32)' },
    escuro: { texto: '#fbbf24', fundo: 'rgba(245, 158, 11, 0.15)', borda: 'rgba(245, 158, 11, 0.32)' },
  },
  problema: {
    claro: { texto: '#b91c1c', fundo: '#fef2f2', borda: 'rgba(185, 28, 28, 0.32)' },
    escuro: { texto: '#fb7185', fundo: 'rgba(244, 63, 94, 0.15)', borda: 'rgba(244, 63, 94, 0.32)' },
  },
  encerrado: {
    claro: { texto: '#475569', fundo: '#f1f5f9', borda: 'rgba(71, 85, 105, 0.25)' },
    escuro: { texto: '#94a3b8', fundo: 'rgba(148, 163, 184, 0.12)', borda: 'rgba(148, 163, 184, 0.25)' },
  },
};

/** Forma do micro-badge (Central, `.bo-ok` e irmãs). Status sempre leva o texto: nunca só pela cor. */
export const microBadge = {
  tamanhoFontePx: 11,
  peso: 800,
  caixaAlta: true,
  espacamentoLetras: '0.04em',
  paddingPx: { vertical: 2.5, horizontal: 8 },
  espacoInternoPx: 4,
  raioPx: 6,
} as const;

/** Pilar 2: todo conteúdo em cartão (vidro fosco), com elementos internos um pouco menos arredondados. */
export const blocos = {
  raioPx: 16,
  raioInternoPx: 12,
  bordaPx: 1,
  desfoquePx: 12,
} as const;

/** Pilar 3: tabela com cabeçalho de 45 px e linha de no mínimo 55 px. */
export const tabela = {
  alturaDoCabecalhoPx: 45,
  alturaMinimaDaLinhaPx: 55,
  tamanhoFonteDoCabecalhoPx: 11,
  barraDaLinhaMarcadaPx: 3,
} as const;

/** Transições curtas, sem sombra pesada. */
export const transicaoMs = { minimo: 120, maximo: 200 } as const;
