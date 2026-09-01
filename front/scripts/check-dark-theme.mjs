import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('src');
const files = [];
const extensions = new Set(['.vue', '.less', '.css', '.ts', '.tsx', '.js', '.jsx']);
const styleExtensions = new Set(['.vue', '.less', '.css']);
const typographyFocusRoots = [
  'modules/device-manager-ui/views/device/DashBoard',
  'modules/device-manager-ui/views/device/Product',
  'modules/device-manager-ui/views/device/Instance',
  'modules/rule-engine-manager-ui/views/DashBoard',
  'modules/rule-engine-manager-ui/views/Alarm',
  'modules/authentication-manager-ui/views/system',
];

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (entry.isFile() && extensions.has(path.extname(entry.name).toLowerCase())) files.push(file);
  }
}

walk(root);

function lineAt(text, offset) {
  return text.slice(0, offset).split('\n').length;
}

function rgbFromToken(token) {
  const value = token.trim().toLowerCase();
  if (value === 'white') return { r: 255, g: 255, b: 255, a: 1 };
  if (value === 'black') return { r: 0, g: 0, b: 0, a: 1 };
  const hex = value.match(/^#([\da-f]{3,8})$/i);
  if (hex) {
    const raw = hex[1];
    const expanded = raw.length === 3 || raw.length === 4
      ? raw.split('').map(item => item + item).join('')
      : raw;
    return {
      r: Number.parseInt(expanded.slice(0, 2), 16),
      g: Number.parseInt(expanded.slice(2, 4), 16),
      b: Number.parseInt(expanded.slice(4, 6), 16),
      a: expanded.length >= 8 ? Number.parseInt(expanded.slice(6, 8), 16) / 255 : 1,
    };
  }
  const numeric = value.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?\s*\)$/i);
  if (numeric) return { r: +numeric[1], g: +numeric[2], b: +numeric[3], a: numeric[4] === undefined ? 1 : +numeric[4] };
  const less = value.match(/^rgba?\(\s*(#[\da-f]{3,8})\s*[,\s]+([\d.]+)\s*\)$/i);
  if (less) {
    const color = rgbFromToken(less[1]);
    return color ? { ...color, a: +less[2] } : null;
  }
  return null;
}

function tokens(value) {
  return value.match(/#[\da-f]{3,8}\b|rgba?\([^)]*\)|\b(?:white|black)\b/gi) || [];
}

function isLightSurface(color) {
  if (!color) return false;
  const nearWhite = color.r >= 220 && color.g >= 220 && color.b >= 220;
  return nearWhite && color.a >= 0.5;
}

function isDarkText(color) {
  if (!color) return false;
  const nearBlack = color.r <= 55 && color.g <= 55 && color.b <= 55;
  return nearBlack && color.a >= 0.25;
}

const findings = [];
const typographyFindings = [];
const typographyWarnings = [];

function relativePath(file) {
  return path.relative(process.cwd(), file).replaceAll(path.sep, '/');
}

function isTypographyFocusFile(file) {
  const relative = path.relative(root, file).replaceAll(path.sep, '/');
  return typographyFocusRoots.some((prefix) => relative.startsWith(prefix));
}

function inspectTypography(text, file) {
  const source = text
    .replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))
    .replace(/(^|\s)\/\/.*$/gm, match => match.replace(/[^\n]/g, ' '));

  for (const match of source.matchAll(/font-family\s*:\s*([^;{}\n]+)/gi)) {
    const value = match[1].trim();
    // The regular-only Alibaba file is still available as a fallback. It must
    // never be the first family for runtime text, but @font-face registration
    // itself is intentionally allowed.
    const before = source.slice(Math.max(0, match.index - 100), match.index);
    if (/^AliRegular\b/i.test(value) && !before.includes('@font-face')) {
      const line = lineAt(text, match.index);
      typographyFindings.push(`${relativePath(file)}:${line} AliRegular must be a fallback font`);
    }
  }

  for (const match of source.matchAll(/fontFamily\s*:\s*['"]([^'"]+)['"]/g)) {
    if (/^\s*AliRegular\b/i.test(match[1])) {
      const line = lineAt(text, match.index);
      typographyFindings.push(`${relativePath(file)}:${line} AliRegular must be a fallback font`);
    }
  }

  if (!isTypographyFocusFile(file)) return;
  for (const match of source.matchAll(/font-weight\s*:\s*(bold|700|800)\b/gi)) {
    const line = lineAt(text, match.index);
    const context = source.slice(Math.max(0, match.index - 180), match.index).toLowerCase();
    if (!/(value|number|metric|kpi|title|heading|active|status|count)/i.test(context)) {
      typographyWarnings.push(`${relativePath(file)}:${line} review ordinary text weight ${match[1]}`);
    }
  }
}

function inspectBlock(text, file, offsetBase) {
  const withoutComments = text
    .replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))
    .replace(/(^|\s)\/\/.*$/gm, match => match.replace(/[^\n]/g, ' '));
  const declaration = /(?:^|[;{\n])\s*(background(?:-color)?|color|fill|stroke)\s*:\s*([^;{}\n]+)/gim;
  for (const match of withoutComments.matchAll(declaration)) {
    const property = match[1].toLowerCase();
    const value = match[2].trim();
    if (value.includes('var(--app-') || value.includes('var(--ant-')) continue;
    const rawLineOffset = offsetBase + match.index + match[0].indexOf(match[1]);
    const sourceLine = lineAt(fullSourceByFile.get(file), rawLineOffset);
    const sourceLineText = fullSourceByFile.get(file).split('\n')[sourceLine - 1] || '';
    if (sourceLineText.includes('dark-theme-allow')) continue;
    for (const token of tokens(value)) {
      const color = rgbFromToken(token);
      if (property.startsWith('background') && isLightSurface(color)) {
        findings.push(`${path.relative(process.cwd(), file)}:${sourceLine} light ${property} ${token}`);
      }
      if ((property === 'color' || property === 'fill' || property === 'stroke') && isDarkText(color)) {
        findings.push(`${path.relative(process.cwd(), file)}:${sourceLine} dark ${property} ${token}`);
      }
    }
  }
}

function inspectDynamicColors(text, file) {
  const declaration = /\b(background|backgroundColor|borderColor|color)\s*:\s*(['"])([^'"]+)\2/g;
  for (const match of text.matchAll(declaration)) {
    const property = match[1].toLowerCase();
    const value = match[3].trim();
    if (value.includes('var(--app-') || value === 'auto' || value === 'inherit') continue;
    const sourceLine = lineAt(text, match.index);
    const sourceLineText = text.split('\n')[sourceLine - 1] || '';
    if (sourceLineText.includes('dark-theme-allow')) continue;
    const color = rgbFromToken(value);
    if ((property === 'background' || property === 'backgroundcolor') && isLightSurface(color)) {
      findings.push(`${path.relative(process.cwd(), file)}:${sourceLine} light dynamic ${match[1]} ${value}`);
    }
    if (property === 'color' && isDarkText(color)) {
      findings.push(`${path.relative(process.cwd(), file)}:${sourceLine} dark dynamic ${match[1]} ${value}`);
    }
  }
}

const fullSourceByFile = new Map();
for (const file of files) {
  const source = fs.readFileSync(file, 'utf8');
  fullSourceByFile.set(file, source);
  if (path.extname(file).toLowerCase() === '.vue') {
    for (const match of source.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/gi)) {
      inspectBlock(match[1], file, match.index + match[0].indexOf(match[1]));
    }
    for (const match of source.matchAll(/(?<!:)style=(['"])(.*?)\1/gis)) {
      inspectBlock(match[2], file, match.index + match[0].indexOf(match[2]));
    }
  } else if (styleExtensions.has(path.extname(file).toLowerCase())) {
    inspectBlock(source, file, 0);
  }
  inspectDynamicColors(source, file);
  inspectTypography(source, file);
}

if (findings.length || typographyFindings.length) {
  console.error(`Dark theme audit failed: ${findings.length + typographyFindings.length} undeclared theme literal/typography issue(s)`);
  for (const finding of findings.slice(0, 200)) console.error(`- ${finding}`);
  for (const finding of typographyFindings.slice(0, 200)) console.error(`- ${finding}`);
  process.exitCode = 1;
} else {
  console.log(`Dark theme audit passed: ${files.length} style files checked.`);
}

const focusFileCount = files.filter(isTypographyFocusFile).length;
console.log(`Typography audit: ${focusFileCount}重点页面文件 checked; system-first font stack enabled.`);
if (typographyWarnings.length) {
  console.warn(`Typography audit warning: ${typographyWarnings.length} focus-page bold declaration(s) need review.`);
  for (const warning of typographyWarnings.slice(0, 80)) console.warn(`- ${warning}`);
}
