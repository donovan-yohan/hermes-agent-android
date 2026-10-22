#!/usr/bin/env node
'use strict';
// Syntax receipts only. Never evaluate upstream modules or execute plugin registration.
// Supply TypeScript through NODE_PATH (a disposable audit tooling install is sufficient).
const ts = require('typescript');
if (ts.version !== '5.9.3') throw new Error('Expected TypeScript 5.9.3 for reproducible syntax receipts');
const fs = require('node:fs');
if (process.argv.includes('--version')) { process.stdout.write(ts.version + '\n'); process.exit(0); }
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const result = [];
for (const {path, text} of input) {
  const sf = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : path.endsWith('.js') ? ts.ScriptKind.JS : ts.ScriptKind.TS);
  if (sf.parseDiagnostics.length) throw new Error('Syntax parse failed: ' + path + ': ' + sf.parseDiagnostics.map(d => d.messageText).join('; '));
  const span = n => ({start: sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1,
    end: sf.getLineAndCharacterOfPosition(n.end - 1).line + 1,
    offset: n.getStart(sf), end_offset: n.end});
  const source = n => n.getText(sf);
  const declarations = new Map();
  const imports = new Map();
  const initializers = new Map();
  function staticValue(n, depth = 0) {
    if (!n || depth > 8) return undefined;
    if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) return n.text;
    if (ts.isNumericLiteral(n)) return Number(n.text);
    if (n.kind === ts.SyntaxKind.TrueKeyword) return true;
    if (n.kind === ts.SyntaxKind.FalseKeyword) return false;
    if (ts.isIdentifier(n)) return staticValue(initializers.get(n.text), depth + 1);
    if (ts.isArrayLiteralExpression(n)) {
      const values = n.elements.map(x => staticValue(x, depth + 1));
      return values.every(x => x !== undefined) ? values : undefined;
    }
    return undefined;
  }
  function index(n) {
    if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.parent?.parent?.parent === sf && n.initializer)
      initializers.set(n.name.text, n.initializer);
    if ((ts.isFunctionDeclaration(n) || ts.isVariableDeclaration(n)) && n.name && ts.isIdentifier(n.name)) {
      const a = declarations.get(n.name.text) || [];
      a.push({path, ...span(n), expression: source(n)});
      declarations.set(n.name.text, a);
    }
    if (ts.isImportDeclaration(n) && n.importClause) {
      const clause = n.importClause;
      if (clause.name) imports.set(clause.name.text, source(n.moduleSpecifier));
      if (clause.namedBindings) {
        if (ts.isNamespaceImport(clause.namedBindings)) imports.set(clause.namedBindings.name.text, source(n.moduleSpecifier));
        else for (const e of clause.namedBindings.elements) imports.set(e.name.text, source(n.moduleSpecifier));
      }
    }
    ts.forEachChild(n, index);
  }
  index(sf);
  function refs(n) {
    const found = new Set();
    function visit(x) { if (ts.isIdentifier(x)) found.add(x.text); ts.forEachChild(x, visit); }
    visit(n);
    return [...found].filter(x => declarations.has(x) || imports.has(x)).map(name => ({name,
      ...(imports.has(name) ? {import: imports.get(name)} : {declarations: declarations.get(name).map(({expression, ...x}) => x)})}));
  }
  function context(n) {
    const ancestors = [];
    for (let p = n.parent; p; p = p.parent) {
      if (ts.isConditionalExpression(p)) ancestors.push({kind: 'conditional', condition: source(p.condition), ...span(p.condition)});
      if (ts.isIfStatement(p)) ancestors.push({kind: 'if', condition: source(p.expression), ...span(p.expression)});
      if (ts.isBinaryExpression(p) && [ts.SyntaxKind.AmpersandAmpersandToken, ts.SyntaxKind.BarBarToken].includes(p.operatorToken.kind))
        ancestors.push({kind: 'short-circuit', condition: source(p.left), ...span(p.left)});
      if (ts.isCallExpression(p) && ts.isPropertyAccessExpression(p.expression) && ['map', 'flatMap', 'filter'].includes(p.expression.name.text))
        ancestors.push({kind: 'collection-template', expression: source(p.expression.expression), ...span(p.expression)});
      if ((ts.isFunctionDeclaration(p) || ts.isVariableDeclaration(p)) && p.name) {
        ancestors.push({kind: 'lexical-owner', name: source(p.name), ...span(p)});
        break;
      }
    }
    return ancestors;
  }
  function walk(n) {
    if (ts.isJsxOpeningElement(n) || ts.isJsxSelfClosingElement(n)) {
      const attrs = n.attributes.properties.filter(ts.isJsxAttribute);
      const events = attrs.filter(a => /^on[A-Z]/.test(source(a.name)));
      if (events.length) result.push({kind: 'jsx-control-candidate', path, ...span(n), component: source(n.tagName),
        attributes: attrs.filter(a => /^(on[A-Z]|disabled$|readOnly$|value$|checked$|aria-label$|title$|label$|options$|items$)/.test(source(a.name)))
          .map(a => ({name: source(a.name), expression: a.initializer ? source(a.initializer) : 'true', ...span(a)})),
        handlers: events.map(a => ({event: source(a.name), expression: a.initializer ? source(a.initializer) : '', references: refs(a)})),
        context: context(n)});
    }
    if (ts.isCallExpression(n) && /(?:register\w*|contribute|buildFromTemplate|glob)$/.test(source(n.expression))) {
      result.push({kind: 'registration-candidate', path, ...span(n), callee: source(n.expression),
        arguments: n.arguments.map(a => ({expression: source(a), ...span(a), references: refs(a)})), context: context(n)});
    }
    if (ts.isVariableDeclaration(n) && n.name && ts.isIdentifier(n.name) &&
        /^(APP_ROUTES|SETTINGS_MANIFEST|SECTIONS|LOG_FILES|LOG_LEVELS|USAGE_PERIODS|NAV_ITEMS|ACTIONS|KEYBIND_ACTIONS|NATIVE_NOTIFICATION_KINDS)$/.test(n.name.text)) {
      result.push({kind: 'registry-declaration', path, ...span(n), name: n.name.text,
        expression: n.initializer ? source(n.initializer) : '', references: n.initializer ? refs(n.initializer) : []});
      const registry = n.name.text;
      function entries(x, keys) {
        if (ts.isPropertyAssignment(x)) {
          const next = [...keys, source(x.name)];
          result.push({kind: 'registry-entry', path, ...span(x), name: registry + '.' + next.join('.')});
          entries(x.initializer, next);
        } else if (ts.isObjectLiteralExpression(x)) {
          for (const p of x.properties) {
            if (ts.isPropertyAssignment(p)) entries(p, keys);
            else if (ts.isSpreadAssignment(p)) result.push({kind: 'registry-dynamic-spread', path, ...span(p), name: registry + '.' + keys.join('.')});
          }
        } else if (ts.isAsExpression(x) || ts.isSatisfiesExpression(x) || ts.isParenthesizedExpression(x)) entries(x.expression, keys);
      }
      if (n.initializer && registry === 'SETTINGS_MANIFEST') entries(n.initializer, []);
    }
    if (ts.isCallExpression(n) && /^(?:jsx|jsxs|jsxDEV|(?:React\.)?createElement)$/.test(source(n.expression)) && n.arguments.length > 1 && ts.isObjectLiteralExpression(n.arguments[1])) {
      const props = n.arguments[1].properties.filter(p => p.name);
      const events = props.filter(p => /^on[A-Z]/.test(source(p.name)));
      if (events.length) result.push({kind: 'factory-control-candidate', path, ...span(n), component: source(n.arguments[0]),
        handlers: events.map(p => ({event: source(p.name), expression: p.initializer ? source(p.initializer) : source(p.name), references: refs(p)})), context: context(n)});
    }
    if (ts.isObjectLiteralExpression(n)) {
      const props = n.properties.filter(p => p.name).map(p => ({name: source(p.name), expression: p.initializer ? source(p.initializer) : source(p), static_value: p.initializer ? staticValue(p.initializer) : undefined, ...span(p)}));
      const names = new Set(props.map(p => p.name.replace(/^['"]|['"]$/g, '')));
      if ((names.has('id') && ['run', 'runWithEvent', 'onSelect', 'area', 'path', 'to'].some(x => names.has(x))) || names.has('role') || names.has('click') || (names.has('id') && (names.has('keys') || names.has('labelKey'))) || (names.has('label') && [...names].some(x => /^on[A-Z]/.test(x))))
        result.push({kind: 'action-object-candidate', path, ...span(n), properties: props.filter(p => !['keywords', 'icon', 'className', 'children', 'submenu', 'data'].includes(p.name)), context: context(n)});
    }
    ts.forEachChild(n, walk);
  }
  walk(sf);
}
process.stdout.write(JSON.stringify(result));
