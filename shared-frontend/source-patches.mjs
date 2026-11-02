// Source adaptations must stop on drift instead of silently compiling an
// unadapted platform call. Keep this module usable by the Node 20 build runtime.
export function replaceChecked(source, pattern, replacement, { all = false, expected } = {}) {
  if (typeof source !== 'string' || typeof replacement !== 'string') {
    throw new TypeError('Source patches require string input and replacement');
  }
  if (typeof pattern !== 'string' && !(pattern instanceof RegExp)) {
    throw new TypeError('Source patch pattern must be a string or RegExp');
  }
  if (pattern === '' || (pattern instanceof RegExp && pattern.sticky)) {
    throw new Error('Empty/sticky source patch patterns are unsupported');
  }
  if (all && pattern instanceof RegExp && !pattern.global) {
    throw new Error('replaceAll source patches require a global RegExp');
  }
  const matches = typeof pattern === 'string' ? source.split(pattern).length - 1 :
    [...source.matchAll(new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g'))].length;
  const multiple = all || (pattern instanceof RegExp && pattern.global);
  const required = expected ?? (multiple ? undefined : 1);
  if (matches === 0 || (required !== undefined && matches !== required)) {
    throw new Error(`Source patch drift: ${String(pattern).slice(0, 180)}; expected ${required ?? 'at least 1'}, found ${matches}`);
  }
  return all ? source.replaceAll(pattern, replacement) : source.replace(pattern, replacement);
}

export function applyPatches(source, edits) {
  for (const [pattern, replacement, options] of edits) source = replaceChecked(source, pattern, replacement, options);
  return source;
}
