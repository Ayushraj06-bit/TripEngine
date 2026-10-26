/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

// Tests run from Frontend/.
const WEB = process.cwd();
const SRC = join(WEB, 'src');
const THEME = join(SRC, 'styles', 'theme.css');

/** The tokens, as the design direction defines them. */
const SPEC: Record<string, string> = {
  ink: '#14171a',
  'ink-soft': '#3c4247',
  mute: '#8a9299',
  paper: '#ffffff',
  wash: '#f2f4f5',
  signal: '#ffb020',
  go: '#0e8a5f',
  stop: '#c8372d',
};

/** Everything that styles the app, apart from the theme itself, the tests and generated code. */
function styledFiles(): string[] {
  const files = readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .map((file) => join(SRC, file))
    .filter((file) => /\.(tsx?|css)$/.test(file))
    .filter((file) => file !== THEME)
    .filter((file) => !/\.test\.tsx?$/.test(file))
    .filter((file) => !file.endsWith('schema.d.ts'))
    .filter((file) => !file.includes(join('src', 'test')));
  return [...files, join(WEB, 'index.html')];
}

const PALETTE =
  'slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose';
const COLOUR_UTILITY =
  'bg|text|border|outline|ring|fill|stroke|from|to|via|divide|placeholder|accent|caret|decoration|shadow';

/** What would be a token written down somewhere other than the theme. */
const FORBIDDEN: { name: string; pattern: RegExp }[] = [
  { name: 'hex colour', pattern: /#[0-9a-f]{3,8}\b/gi },
  { name: 'colour function', pattern: /\b(?:rgba?|hsla?|oklch|oklab|lab|lch)\(/gi },
  {
    name: 'arbitrary colour',
    pattern: new RegExp(`\\b(?:${COLOUR_UTILITY})-\\[(?:#|rgb|hsl|oklch|color|var)`, 'g'),
  },
  {
    name: "Tailwind's own palette",
    pattern: new RegExp(`\\b(?:${COLOUR_UTILITY})-(?:${PALETTE})-\\d{2,3}\\b`, 'g'),
  },
  {
    name: 'black or white',
    pattern: new RegExp(`\\b(?:${COLOUR_UTILITY})-(?:black|white)\\b`, 'g'),
  },
  { name: "Tailwind's own type size", pattern: /\btext-(?:xs|sm|base|lg|xl|[2-9]xl)\b/g },
  { name: 'arbitrary type size', pattern: /\btext-\[\d/g },
  {
    name: 'weight outside 400 and 600',
    pattern: /\bfont-(?:thin|extralight|light|medium|bold|extrabold|black|\[\d)/g,
  },
  {
    name: "Tailwind's own radius",
    pattern: /\brounded(?:-[trblse]{1,2})?-(?:xs|sm|md|lg|xl|[2-4]xl)\b/g,
  },
  { name: 'arbitrary radius', pattern: /\brounded(?:-[trblse]{1,2})?-\[/g },
  { name: 'arbitrary shadow', pattern: /\bshadow-(?:\[|sm|md|lg|xl|2xl)/g },
  {
    name: 'raw CSS size, weight, radius or shadow',
    pattern: /(?:font-size|font-weight|border(?:-[a-z]+)*-radius):\s*\d|box-shadow:/g,
  },
];

describe('the design tokens', () => {
  const theme = readFileSync(THEME, 'utf8');

  it('are defined in the theme with the values the design direction gives', () => {
    for (const [name, value] of Object.entries(SPEC)) {
      expect(theme, `--${name}`).toMatch(new RegExp(`--${name}:\\s*${value};`, 'i'));
    }
  });

  it('are the only colours, sizes, weights, radii and shadow anywhere else', () => {
    const offences: string[] = [];
    for (const file of styledFiles()) {
      const text = readFileSync(file, 'utf8');
      for (const { name, pattern } of FORBIDDEN) {
        for (const match of text.matchAll(pattern)) {
          offences.push(`${relative(WEB, file)}: ${name} "${match[0]}"`);
        }
      }
    }
    expect(offences).toEqual([]);
  });

  it('would catch a hardcoded value', () => {
    // The check above is only worth something if it fires.
    const sample =
      'className="bg-[#ff0000] text-sm text-gray-500 rounded-lg font-bold" style="color: rgb(0,0,0)"';
    const caught = FORBIDDEN.filter(({ pattern }) =>
      new RegExp(pattern.source, pattern.flags).test(sample),
    );
    expect(caught.map((c) => c.name)).toEqual(
      expect.arrayContaining([
        'hex colour',
        'colour function',
        'arbitrary colour',
        "Tailwind's own palette",
        "Tailwind's own type size",
        "Tailwind's own radius",
        'weight outside 400 and 600',
      ]),
    );
  });
});
