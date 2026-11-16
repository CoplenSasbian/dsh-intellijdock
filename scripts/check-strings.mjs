#!/usr/bin/env node
/**
 * Cross-checks the message bundles against the code.
 *
 * The VS Code version's best self-check was "every key has a user, every user
 * string has a translation"; the same check earns its keep here, because a
 * missing key only shows up as `!key!` in a tool window that nobody opens twice.
 *
 * Usage: node scripts/check-strings.mjs [--strict]
 *
 * Exit code 1 when
 *   * the code references a key that is missing from the English bundle,
 *   * the English bundle has a key the Chinese bundle lacks (or vice versa),
 *   * the same key is defined twice.
 * `--strict` also fails on keys that no code path references.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const kotlinDir = join(root, 'src', 'main', 'kotlin');
const messagesDir = join(root, 'src', 'main', 'resources', 'messages');
const strict = process.argv.includes('--strict');

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (entry.endsWith('.kt')) out.push(full);
  }
  return out;
}

function readBundle(file) {
  const text = readFileSync(file, 'utf8');
  const keys = new Map();
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const index = trimmed.indexOf('=');
    if (index <= 0) continue;
    const key = trimmed.slice(0, index).trim();
    if (keys.has(key)) throw new Error(`${file}: duplicate key ${key}`);
    keys.set(key, trimmed.slice(index + 1));
  }
  return keys;
}

const english = readBundle(join(messagesDir, 'DshDockBundle.properties'));
const chinese = readBundle(join(messagesDir, 'DshDockBundle_zh_CN.properties'));

/**
 * Literals that look like keys but are not: the plugin id shares the `dsh.`
 * prefix with the DSH-launcher messages.
 */
const NOT_MESSAGES = new Set(['dsh.ideadock', 'dsh-ideadock', 'dsd.ideadock']);

const used = new Set();
for (const file of walk(kotlinDir)) {
  const text = readFileSync(file, 'utf8');
  for (const match of text.matchAll(/message\(\s*"([a-z][A-Za-z0-9.]*)"/g)) used.add(match[1]);
  for (const match of text.matchAll(/\bt\(\s*"([a-z][A-Za-z0-9.]*)"/g)) used.add(match[1]);
  // Conditional variants such as message(if (on) "a" else "b").
  for (const match of text.matchAll(/"([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+)"/g)) {
    const key = match[1];
    if (/^(action|dock|diff|dsh|mcp|notify|port|probe|review|settings|status)\./.test(key)) used.add(key);
  }
}
for (const key of NOT_MESSAGES) used.delete(key);

const missingInEnglish = [...used].filter((key) => !english.has(key)).sort();
const missingInChinese = [...used].filter((key) => !chinese.has(key)).sort();
const onlyEnglish = [...english.keys()].filter((key) => !chinese.has(key)).sort();
const onlyChinese = [...chinese.keys()].filter((key) => !english.has(key)).sort();
const unreferenced = [...english.keys()].filter((key) => !used.has(key)).sort();

let failed = false;
function report(title, list, fatal = true) {
  if (list.length === 0) return;
  if (fatal) failed = true;
  console.log(`${fatal ? '✗' : '·'} ${title} (${list.length})`);
  for (const item of list) console.log(`    ${item}`);
}

report('keys used in code but missing from DshDockBundle.properties', missingInEnglish);
report('keys used in code but missing from DshDockBundle_zh_CN.properties', missingInChinese);
report('keys in English but not in Chinese', onlyEnglish);
report('keys in Chinese but not in English', onlyChinese);
report('keys not referenced by any code path', unreferenced, strict);

if (!failed) {
  console.log(`✓ ${english.size} keys, ${used.size} referenced, both bundles in sync`);
}
process.exit(failed ? 1 : 0);
