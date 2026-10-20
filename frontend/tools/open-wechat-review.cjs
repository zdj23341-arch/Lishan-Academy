// Keep the WeChat service script and view template on the same acceptance build.
// Development watch still uses dist/dev/mp-weixin; this command only opens dist/build.
const fs = require('node:fs');
const path = require('node:path');
const { exec } = require('node:child_process');
const { stampCandidate } = require('./wechat-review-candidate.cjs');
const {
  ensureReviewSession,
  readRuntimeBuildId,
  waitForRuntimeBuildId,
  waitForRuntimeConfigurations,
} = require('./wechat-review-runtime.cjs');

const root = path.resolve(__dirname, '..');
const project = path.join(root, 'miniprogram/dist/build/mp-weixin');
const candidates = [
  process.env.WECHAT_DEVTOOLS_CLI,
  'D:/Program Files (x86)/Tencent/微信web开发者工具/cli.bat',
  'C:/Program Files (x86)/Tencent/微信web开发者工具/cli.bat',
].filter(Boolean);
const cli = candidates.find((value) => fs.existsSync(value));
function quote(value) {
  // cmd variable expansion occurs even inside quotes. Reject special shell characters.
  if (/["%!^&|<>\r\n]/.test(value)) throw new Error('工具或项目路径含不支持的命令行字符');
  return '"' + value.replaceAll('/', '\\') + '"';
}
function run(command, timeout) {
  return new Promise((resolve, reject) => {
    const child = exec(command, { cwd: root, timeout, windowsHide: true }, (error) =>
      error ? reject(error) : resolve(),
    );
    child.stdout.pipe(process.stdout);
    child.stderr.pipe(process.stderr);
  });
}
async function main() {
  if (process.platform !== 'win32') throw new Error('此验收脚本用于 Windows 微信开发者工具');
  if (!cli) throw new Error('未找到微信 CLI，请通过 WECHAT_DEVTOOLS_CLI 指定 cli.bat 路径');
  const command = quote(cli);
  const previousFile = path.join(project, 'review-build.json');
  const previousId = fs.existsSync(previousFile)
    ? JSON.parse(fs.readFileSync(previousFile, 'utf8')).buildId
    : null;
  // CLI close can return success while leaving the old port/window alive.
  // Only reuse a positively identified candidate; never launch over an unknown port.
  let before;
  try {
    before = await readRuntimeBuildId();
  } catch (error) {
    // A known candidate may still be starting after the IDE file watcher fired.
    if (!previousId) throw error;
    before = await waitForRuntimeBuildId(previousId);
  }
  if (before !== null && (!previousId || before !== previousId)) {
    throw new Error('9420端口运行标识与当前候选不一致；停止构建，避免覆盖或重复打开其他项目');
  }
  console.log('生成固定目录验收候选；构建期间请勿操作模拟器。');
  await run('npm run build:mp:local', 180000);
  const filename = path.join(project, 'project.private.config.json');
  const config = fs.existsSync(filename) ? JSON.parse(fs.readFileSync(filename, 'utf8')) : {};
  config.setting = { ...config.setting, compileHotReLoad: false };
  fs.writeFileSync(filename, JSON.stringify(config, null, 2) + '\n');
  // A watched rebuild briefly removes the old app marker. The pre-build identity
  // already authorized reuse; compare the NEW identity only after stamping.
  const session =
    before !== null
      ? 'reuse'
      : await ensureReviewSession({
          expectedId: null,
          read: readRuntimeBuildId,
          launch: () => run(`${command} auto --project ${quote(project)} --auto-port 9420`, 45000),
        });
  console.log(
    session === 'reuse' ? '已核对并复用当前验收窗口，不重复启动。' : '已打开唯一验收窗口。',
  );
  // The IDE can retain old service scripts even after reopening the same directory.
  // Write after opening so its file watcher invalidates every script in this candidate.
  await new Promise((resolve) => setTimeout(resolve, 5000));
  const buildId = 'review-' + Date.now().toString(36);
  const candidate = stampCandidate(project, buildId);
  await waitForRuntimeBuildId(buildId);
  const expected = Object.fromEntries(
    candidate.configurations
      .filter((record) => record.path !== 'app.json')
      .map((record) => [
        record.path,
        JSON.parse(fs.readFileSync(path.join(project, record.path), 'utf8')),
      ]),
  );
  const configurationsChecked = await waitForRuntimeConfigurations(expected);
  await waitForRuntimeBuildId(buildId);
  fs.writeFileSync(
    path.join(project, 'review-runtime-verification.json'),
    JSON.stringify(
      {
        buildId,
        configurationsChecked,
        verifiedAt: new Date().toISOString(),
        scope:
          'Page/component JSON registrations and app script marker; visual acceptance still required',
      },
      null,
      2,
    ) + '\n',
  );
  console.log(`本次运行标识：${buildId}。已回读微信运行标识，与 review-build.json 一致。`);
  console.log(`已回读并核对 ${configurationsChecked} 份页面/组件配置，拒绝旧组件注册混入。`);
  console.log(
    '验收候选已打开。等待模拟器首页加载完成后再操作；仍须实际浏览确认，CLI成功不代表视觉验收通过。',
  );
}
main().catch((error) => {
  console.error('验收构建未完成：', error.message);
  process.exitCode = 1;
});
