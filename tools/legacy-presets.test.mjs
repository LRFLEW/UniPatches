import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const manifest = readFileSync(new URL('../patches/src/main/kotlin/unipatches/compatibility/LegacyAppCompatibilityManifest.kt', import.meta.url), 'utf8');
test('preset is first declared option and defaults to Custom', () => {
  assert.match(manifest.match(/val \w+ by \w+Option/)[0], /val preset by stringOption/);
  assert.match(manifest, /key = "legacyCompatibilityPreset"/);
});
test('Unity native mutation is independently fingerprint gated', () => {
  assert.match(manifest, /detectedEngines\.unity/);
  assert.match(manifest, /legacyEngineDetectionPatch/);
});
test('Custom conservative defaults avoid bulk changes', () => {
  for (const name of ['legacyReviverRaw', 'allScreensRaw'])
    assert.match(manifest, new RegExp('val ' + name + ' by booleanOption\\([\\s\\S]*?default = false'));
});
test('Custom Spoof Target SDK remains one visible option and logs one resolved value', () => {
  assert.equal((manifest.match(/key = \"legacyCompatibilitySpoofTargetSdk\"/g) || []).length, 1);
  assert.match(manifest, /val spoofTargetSdk = LegacyCompatibilityPresets\.boolean\(presetId, \"spoofTargetSdk\", spoofTargetSdkRaw\)/);
  assert.doesNotMatch(manifest, /rawSpoofTargetSdk=/);
  assert.doesNotMatch(manifest, /rawSpoof=|effectiveSpoof=/);
  assert.match(manifest, /if \(presetId != LegacyCompatibilityPresets\.CUSTOM && spoofTargetSdk != spoofTargetSdkRaw\)/);
});
test('explicit target SDK defaults to the Android 7+ install floor', () => {
  assert.match(manifest, /val targetSdkRaw by intOption\([\s\S]*?default = 24/);
  assert.match(manifest, /val targetSdkProfileRaw by stringOption\([\s\S]*?default = \"custom\"/);
});
test('repair missing exported flags is gated by final target API', () => {
  assert.match(manifest, /shouldRepairMissingExportFlags\(repairExportFlags == true, update\.finalTarget\)/);
});
test('all option delegates feed the effective preset boundary', () => {
  const ordinary = [...manifest.matchAll(/val (\w+)Raw by \w+Option/g)];
  assert.equal(ordinary.length, 34);
  for (const [, name] of ordinary) {
    if (name === 'playStorePackageVisibility') continue;
    assert.match(manifest, new RegExp('val ' + name + ' = .*' + name + 'Raw'));
  }
  assert.match(manifest, /val playStorePackageVisibility = LegacyCompatibilityPresets\.playStorePackageVisibility\([\s\S]*?playStorePackageVisibilityRaw,\s*detectedEngines\.openIab/);
});
