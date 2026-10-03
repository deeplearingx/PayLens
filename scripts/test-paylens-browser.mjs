import { spawn } from 'node:child_process';
import { constants, publicEncrypt } from 'node:crypto';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import net from 'node:net';

const baseUrl = (process.env.PAYLENS_BROWSER_BASE_URL ?? 'http://127.0.0.1:9999').replace(/\/$/, '');
const account = process.env.PAYLENS_BROWSER_ACCOUNT ?? 'bootx';
const password = process.env.PAYLENS_BROWSER_PASSWORD;
const screenshotPath = process.env.PAYLENS_BROWSER_SCREENSHOT ?? path.resolve('daxpay-start/target/paylens-browser-smoke.png');
const chromiumPath = process.env.PAYLENS_CHROMIUM_PATH ?? path.join(
  process.env.LOCALAPPDATA ?? '', 'ms-playwright', 'chromium-1140', 'chrome-win', 'chrome.exe');

if (!password) throw new Error('PAYLENS_BROWSER_PASSWORD is required; password values are never written to test output.');

async function jsonRequest(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json();
  if (!response.ok || (typeof body.code === 'number' && body.code !== 0)) {
    throw new Error(`Request failed: ${new URL(url).pathname}, HTTP ${response.status}, API ${body.code ?? 'unknown'}`);
  }
  return body;
}

async function getDebugPort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

async function waitFor(read, predicate, label, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await read();
    if (predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error(`Timed out waiting for ${label}; last state=${JSON.stringify(last)}`);
}

async function main() {
  const publicKeyResponse = await jsonRequest(`${baseUrl}/token/public-key`);
  const nonceResponse = await jsonRequest(`${baseUrl}/nonce/generate`, { method: 'POST' });
  const encryptedPassword = publicEncrypt({ key: publicKeyResponse.data, padding: constants.RSA_PKCS1_PADDING },
    Buffer.from(password, 'utf8')).toString('base64');
  const loginForm = new URLSearchParams({ account, password: encryptedPassword, loginType: 'password', client: 'admin' });
  const loginResponse = await jsonRequest(`${baseUrl}/token/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'x-nonce': String(nonceResponse.data.nonce),
      'x-timestamp': String(nonceResponse.data.timestamp),
    },
    body: loginForm,
  });
  const token = loginResponse.data;
  if (typeof token !== 'string' || token.length < 16) throw new Error('Admin login returned no usable session token.');

  const profile = await mkdtemp(path.join(tmpdir(), 'paylens-chromium-'));
  const port = await getDebugPort();
  const browser = spawn(chromiumPath, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    '--remote-allow-origins=*', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank',
  ], { windowsHide: true, stdio: 'ignore' });
  let socket;
  let requestId = 0;
  const pending = new Map();

  try {
    const deadline = Date.now() + 20000;
    let version;
    while (Date.now() < deadline) {
      try {
        const response = await fetch(`http://127.0.0.1:${port}/json/version`);
        if (response.ok) { version = await response.json(); break; }
      } catch { }
      if (browser.exitCode !== null) throw new Error(`Chromium exited with code ${browser.exitCode} before its debugging endpoint became ready.`);
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    if (!version) throw new Error('Chromium debugging endpoint did not start.');
    const targetResponse = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' });
    if (!targetResponse.ok) throw new Error(`Chromium could not create a browser tab: HTTP ${targetResponse.status}`);
    const target = await targetResponse.json();
    socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', reject, { once: true });
    });
    socket.addEventListener('message', (event) => {
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      if (!message.id || !pending.has(message.id)) return;
      const waiter = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) waiter.reject(new Error(message.error.message));
      else waiter.resolve(message.result);
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++requestId;
      pending.set(id, { resolve, reject });
      socket.send(JSON.stringify({ id, method, params }));
    });
    const evaluate = async (expression) => {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (result.exceptionDetails) throw new Error(`Browser script failed: ${result.exceptionDetails.text}`);
      return result.result?.value;
    };

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Network.enable');
    const cookie = await send('Network.setCookie', {
      name: 'Accesstoken', value: token, url: baseUrl, httpOnly: true, sameSite: 'Lax', secure: false,
    });
    if (cookie.success !== true) throw new Error('Could not establish the admin browser session.');
    await send('Page.navigate', { url: `${baseUrl}/paylens` });
    await waitFor(() => evaluate(`({title:document.title,ready:document.readyState,brand:document.querySelector('.brand')?.innerText})`),
      (state) => state?.ready === 'complete' && state?.brand?.includes('PayLens'), 'PayLens page load');

    await evaluate(`document.querySelector('#scenario').value='LATE_SUCCESS'; document.querySelector('#runScenario').click(); true`);
    const created = await waitFor(() => evaluate(`({message:document.querySelector('#scenarioMessage')?.innerText,detail:document.querySelector('#detail')?.innerText,status:document.querySelector('#tradeStatus')?.innerText})`),
      (state) => state?.detail?.includes('close_paid') && state?.detail?.includes('异常订单'), 'late-success evidence timeline');
    const tradeMatch = created.message.match(/交易号\s+([A-Za-z0-9_-]+)/);
    if (!tradeMatch) throw new Error('Browser scenario response did not render a transaction number.');
    const tradeNo = tradeMatch[1];

    await evaluate(`document.querySelector('.analysis-actions button[onclick="analyze()"]')?.click(); true`);
    const completed = await waitFor(() => evaluate(`({state:document.querySelector('#analysisState')?.innerText,result:document.querySelector('#analysisResult')?.innerText})`),
      (state) => state?.state?.startsWith('SUCCEEDED') && state?.result?.length > 80, 'successful browser diagnosis', 90000);
    if (!completed.result.includes('已确认事实') || !completed.result.includes('排查步骤')) {
      throw new Error('Browser diagnosis did not render the structured result sections.');
    }
    if (!completed.result.includes('导出 Markdown 报告')) throw new Error('Browser diagnosis did not render the report export link.');

    await evaluate('location.reload()');
    await waitFor(() => evaluate(`document.querySelectorAll('#demoTradeList .row').length`), (count) => count > 0,
      'trade list after browser reload');
    const selected = await evaluate(`(() => { const row=[...document.querySelectorAll('#demoTradeList .row')].find(x=>x.innerText.includes(${JSON.stringify(tradeNo)})); if(!row)return false; row.click(); return true; })()`);
    if (!selected) throw new Error('The created trade was missing from the refreshed browser trade list.');
    await waitFor(() => evaluate(`({items:document.querySelector('#analysisHistory')?.options.length,state:document.querySelector('#analysisState')?.innerText})`),
      (state) => state?.items > 1, 'historical diagnosis entry');
    const history = await evaluate(`(() => { const select=document.querySelector('#analysisHistory'); select.selectedIndex=1; select.dispatchEvent(new Event('change')); return true; })()`);
    if (!history) throw new Error('The browser could not select a historical diagnosis.');
    await waitFor(() => evaluate(`({state:document.querySelector('#analysisState')?.innerText,result:document.querySelector('#analysisResult')?.innerText})`),
      (state) => state?.state?.startsWith('SUCCEEDED') && state?.result?.includes('导出 Markdown 报告'), 'historical diagnosis reload');

    const seededIncidents = await evaluate(`(async()=>{const trades=[];for(let i=0;i<2;i++){const response=await fetch('/paylens/api/scenarios',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({scenario:'LATE_SUCCESS'})});const body=await response.json();if(!response.ok||body.code!==0||!body.data?.tradeNo)throw new Error('Could not create isolated incident test data');trades.push(body.data.tradeNo)}return trades})()`);
    if (!Array.isArray(seededIncidents) || seededIncidents.length !== 2) throw new Error('The event-workbench browser test did not seed two synthetic observations.');
    await send('Page.navigate', { url: `${baseUrl}/paylens/incidents` });
    const eventPage = await waitFor(() => evaluate(`({title:document.title,ready:document.readyState,scan:!!document.querySelector('#scan'),startScan:typeof startScan,handler:typeof document.querySelector('#scan')?.onclick})`),
      (state) => state?.ready === 'complete' && state?.scan && state?.startScan === 'function' && state?.handler === 'function', 'event workbench page scripts');
    if (!eventPage.scan || eventPage.startScan !== 'function' || eventPage.handler !== 'function') {
      throw new Error(`The event workbench scan handler did not initialize: ${JSON.stringify(eventPage)}`);
    }
    await evaluate(`startScan(); true`);
    const scanned = await waitFor(() => evaluate(`({state:document.querySelector('#batchState')?.innerText,count:Number(document.querySelector('#eventCount')?.innerText||0),className:document.querySelector('#batchState')?.className})`),
      (state) => (state?.state?.includes('SUCCEEDED') && state?.count > 0) || state?.state?.includes('FAILED') || state?.className === 'error', 'incident observation scan and event-list render');
    if (scanned.state?.includes('FAILED') || scanned.className === 'error') {
      throw new Error(`The incident scan request failed: ${scanned.state}`);
    }
    if (scanned.count < 1) throw new Error('The event scan completed without grouping the synthetic abnormal-order observations.');
    await evaluate(`document.querySelector('#events .event-row .row')?.click(); true`);
    const selectedEvent = await waitFor(() => evaluate(`({members:document.querySelectorAll('#detail [data-member-id]').length,summary:document.querySelector('#detail')?.innerText})`),
      (state) => state?.members >= 2 && state?.summary?.includes('成员记录'), 'incident evidence members');
    if (selectedEvent.members < 2) throw new Error('The event was not grouped with enough members to exercise review adjustments.');
    await evaluate(`document.querySelector('button[onclick="createAnalysis()"]')?.click(); true`);
    const eventAnalysis = await waitFor(() => evaluate(`({state:document.querySelector('#analysisStatus')?.innerText,result:document.querySelector('#analysisResult')?.innerText})`),
      (state) => state?.state?.startsWith('SUCCEEDED') && state?.result?.includes('共同现象'), 'event analysis with mock model', 90000);
    if (!eventAnalysis.result.includes('核查步骤') || !eventAnalysis.result.includes('导出 Markdown')) {
      throw new Error('The event analysis did not render its structured result and report link.');
    }
    const report = await evaluate(`(async()=>{const link=document.querySelector('#analysisResult a[href*="/report"]');if(!link)return {status:0,length:0};const response=await fetch(link.href);const text=await response.text();return {status:response.status,length:text.length}})()`);
    if (report.status !== 200 || report.length < 80) throw new Error('The event analysis Markdown report could not be exported from the browser.');

    await evaluate(`window.confirm=()=>true;window.prompt=()=> '浏览器闭环拆分与合并验证';document.querySelector('#detail [data-member-id]')?.click();document.querySelector('button[onclick="splitSelected()"]')?.click();true`);
    const split = await waitFor(() => evaluate(`({count:Number(document.querySelector('#eventCount')?.innerText||0),members:document.querySelectorAll('#detail [data-member-id]').length,state:document.querySelector('#analysisStatus')?.innerText})`),
      (state) => state?.count >= 2 && state?.members >= 1 && state?.state?.includes('拆分完成'), 'manual incident split');
    if (split.count < 2) throw new Error('The manual split did not preserve both incident versions.');
    const mergePair = await evaluate(`(async()=>{const base=currentIncident;if(!base)return[];for(const box of document.querySelectorAll('#events input[data-incident-id]')){if(box.dataset.incidentId===base.id)continue;const detail=await request('/incidents/'+encodeURIComponent(box.dataset.incidentId));if(detail.batchId===base.batchId&&JSON.stringify(detail.groupingEvidence?.hardBoundary)===JSON.stringify(base.groupingEvidence?.hardBoundary))return[base.id,detail.id]}return[]})()`);
    if (!Array.isArray(mergePair) || mergePair.length !== 2) throw new Error('The browser could not find a same-boundary incident pair to merge.');
    await evaluate(`(()=>{const chosen=${JSON.stringify(mergePair)};for(const box of document.querySelectorAll('#events input[data-incident-id]'))box.checked=chosen.includes(box.dataset.incidentId);document.querySelector('#mergeSelected')?.click();return chosen.length})()`);
    const merged = await waitFor(() => evaluate(`({count:Number(document.querySelector('#eventCount')?.innerText||0),members:currentIncident?.members?.length||0,archived:[...document.querySelectorAll('#events .event-row .row')].filter(row=>row.innerText.includes('ARCHIVED')).length,state:document.querySelector('#analysisStatus')?.innerText,batchState:document.querySelector('#batchState')?.innerText,batchClass:document.querySelector('#batchState')?.className})`),
      (state) => state?.state?.includes('合并完成') || state?.batchClass === 'error', 'manual incident merge');
    if (merged.batchClass === 'error') throw new Error(`The manual incident merge was rejected: ${merged.batchState}`);
    if (merged.members < split.members || merged.archived < 1) throw new Error('The manual merge did not preserve its members and archived source event history.');
    await evaluate(`document.querySelector('button[onclick="refreshProgress()"]')?.click(); true`);
    const refreshed = await waitFor(() => evaluate(`document.querySelector('#currentProgress')?.innerText||''`),
      (text) => text.includes('当前交易状态'), 'incident processing-progress refresh');
    if (!refreshed) throw new Error('The event processing status was not refreshed.');

    await send('Page.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    const screenshot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    await mkdir(path.dirname(screenshotPath), { recursive: true });
    await writeFile(screenshotPath, Buffer.from(screenshot.data, 'base64'));
    console.log(`BROWSER_RESULT=PASSED tradeNo=${tradeNo} diagnosis=SUCCEEDED history=loaded eventScan=SUCCEEDED eventAnalysis=SUCCEEDED split=merged progress=refreshed screenshot=${screenshotPath}`);
  } finally {
    if (socket && socket.readyState === WebSocket.OPEN) socket.close();
    browser.kill();
    await new Promise((resolve) => setTimeout(resolve, 300));
    const resolvedTemp = path.resolve(profile);
    const resolvedRoot = path.resolve(tmpdir());
    if (!resolvedTemp.startsWith(`${resolvedRoot}${path.sep}`)) throw new Error('Refusing to remove a Chromium profile outside the temporary directory.');
    await rm(resolvedTemp, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`BROWSER_RESULT=FAILED reason=${error.message}`);
  process.exitCode = 1;
});
