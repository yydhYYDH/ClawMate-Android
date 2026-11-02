import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const option = name => args[args.indexOf(name) + 1];
if (!args.includes('--project') || !args.includes('--android-sdk')) {
  throw new Error('Pass --project <generated probe> --android-sdk <installed Android SDK>');
}
const project = path.resolve(option('--project'));
const android = path.join(project, '.arkui-x/android');
fs.writeFileSync(path.join(android, 'build.gradle'),
  "buildscript { repositories { google { content { excludeGroup 'org.jetbrains.kotlin' } }; mavenCentral() }; dependencies { classpath 'com.android.tools.build:gradle:8.5.2' } }\n" +
  'allprojects { repositories { google(); mavenCentral() } }\n');
fs.writeFileSync(path.join(android, 'app/build.gradle'),
  "plugins { id 'com.android.application' }\n" +
  "android { namespace 'com.clawmate.reuseprobe'; compileSdk 34\n" +
  " defaultConfig { applicationId 'com.clawmate.reuseprobe'; minSdk 26; targetSdk 34; versionCode 1; versionName '0.1-probe'; ndk { abiFilters 'arm64-v8a' } }\n" +
  ' compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }\n' +
  " sourceSets { main { jniLibs.srcDirs=['libs'] } }\n" +
  ' packagingOptions { jniLibs { useLegacyPackaging true } }\n}\n' +
  "dependencies { implementation fileTree(dir:'libs',include:['*.jar','*.aar']) }\n");
fs.writeFileSync(path.join(android, 'local.properties'),
  `sdk.dir=${path.resolve(option('--android-sdk')).split(path.sep).join('/')}\n`);
const java = path.join(android, 'app/src/main/java/com/clawmate/reuseprobe');
fs.copyFileSync(path.join(here, 'probe/ProbeBridge.java'), path.join(java, 'ProbeBridge.java'));
fs.copyFileSync(path.join(here, 'probe/ProbeFixture.java'), path.join(java, 'ProbeFixture.java'));
fs.writeFileSync(path.join(java, 'EntryEntryAbilityActivity.java'),
  'package com.clawmate.reuseprobe;\n' +
  'import android.os.Bundle;\nimport ohos.stage.ability.adapter.StageActivity;\n' +
  'public class EntryEntryAbilityActivity extends StageActivity {\n' +
  '  private ProbeBridge probeBridge;\n' +
  '  @Override protected void onCreate(Bundle state) {\n' +
  '    probeBridge = new ProbeBridge(this);\n' +
  '    setInstanceName("com.clawmate.reuseprobe:entry:EntryAbility:");\n' +
  '    super.onCreate(state);\n' +
  '    getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);\n' +
  '    getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);\n' +
  '  }\n' +
  '  @Override protected void onDestroy() {\n' +
  '    try { probeBridge.closeFixture(); } catch (Exception ignored) {}\n' +
  '    super.onDestroy();\n' +
  '  }\n}\n');
const manifestPath = path.join(android, 'app/src/main/AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8').replace(/\s+package="[^"]+"/, '')
  .replace('android:allowBackup="true"', 'android:allowBackup="false"');
if (!manifest.includes('android:networkSecurityConfig=')) {
  manifest = manifest.replace('<application', '<application android:networkSecurityConfig="@xml/probe_network_security"');
}
if (!manifest.includes('android:extractNativeLibs=')) {
  manifest = manifest.replace('<application', '<application android:extractNativeLibs="true"');
}
fs.writeFileSync(manifestPath, manifest);
fs.mkdirSync(path.join(android, 'app/src/main/res/xml'), { recursive: true });
fs.writeFileSync(path.join(android, 'app/src/main/res/xml/probe_network_security.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n' +
  '<network-security-config><base-config cleartextTrafficPermitted="false"/>' +
  '<domain-config cleartextTrafficPermitted="true"><domain>127.0.0.1</domain></domain-config></network-security-config>\n');
fs.writeFileSync(path.join(android, 'app/src/main/res/values/strings.xml'),
  '<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">ClawMate 复用验证</string></resources>\n');
console.log(JSON.stringify({ applicationId: 'com.clawmate.reuseprobe', configured: true }));
