// Real Auth + QR JVMs and disposable PostgreSQL. No deployment ENV files are read or written.
// Requires current packaged jars, Docker's cached postgres:17-alpine image, Java 25 and Node 22+.
import { generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream, mkdirSync, existsSync, readdirSync, statSync, readFileSync } from 'node:fs';
import { createServer as tcpServer } from 'node:net';
import { createServer as httpServer } from 'node:http';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const run = randomUUID();
const runtime = resolve(root, `logs/q5-network-${run}`);
let container, proxy, stage = 'prerequisites';
const jobs = [], streams = [];
// Do not inherit application/JVM option overrides that could redirect a child to a real DB or config file.
const osNames=new Set(['PATH','PATHEXT','SYSTEMROOT','WINDIR','TEMP','TMP','TMPDIR','USERPROFILE','HOME','HOMEDRIVE','HOMEPATH',
  'LOCALAPPDATA','APPDATA','PROGRAMDATA','PROGRAMFILES','COMMONPROGRAMFILES','COMSPEC','LANG','LC_ALL','TZ']);
const osEnvironment=Object.fromEntries(Object.entries(process.env).filter(([name]) => osNames.has(name.toUpperCase())));
const javaProbe = spawnSync('java',['-XshowSettings:properties','-version'],{env:osEnvironment,encoding:'utf8',windowsHide:true});
const javaHome = (javaProbe.stdout+javaProbe.stderr).match(/java\.home\s*=\s*([^\r\n]+)/)?.[1]?.trim();
if (!javaHome) throw new Error('Cannot resolve the real JDK executable');
const java = resolve(javaHome,'bin',process.platform==='win32'?'java.exe':'java');
const alive = job => job.exitCode===null && job.signalCode===null;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (condition, message) => { if (!condition) throw new Error(message); };
class FixtureError extends Error { }
const docker = (args, options = {}) => {
  const result = spawnSync('docker', args, { encoding: 'utf8', windowsHide: true, ...options });
  if (result.status !== 0) {
    // Never print arguments, SQL, environment values or unfiltered subprocess output.
    const reason = String(result.stderr || '').match(/shutting down|connection refused|No such file|FATAL|ERROR|PANIC/i)?.[0] || result.error?.code || 'subprocess';
    throw new FixtureError(`docker ${args[0]} failed: exit=${result.status}, reason=${reason}`);
  }
  return result.stdout.trim();
};
// The temporary init server accepts Unix sockets but does NOT listen on TCP.
const sql = (database, statement) => docker(['exec', '-i', container, 'psql', '-h', '127.0.0.1', '-p', '5432', '-U', 'q5test', '-d', database, '-t', '-A', '-v', 'ON_ERROR_STOP=1'], { input: statement });
const wait = async (name, predicate, timeout = 90000) => {
  stage = name;
  const end = Date.now() + timeout;
  let lastFailure;
  while (Date.now() < end) {
    try { if (await predicate()) return; }
    catch (failure) { lastFailure = failure instanceof FixtureError ? failure.message : failure.name; }
    await sleep(300);
  }
  if (lastFailure) throw new FixtureError(`Timed out: ${name}; last=${lastFailure}`);
  throw new Error(`Acceptance condition timed out: ${name}`);
};
const port = () => new Promise((resolve, reject) => {
  const server = tcpServer(); server.on('error', reject);
  server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
});
const keys = () => {
  const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
  return { private: pair.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'),
    public: pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
};
const latestSource = directory => Math.max(...readdirSync(directory,{withFileTypes:true}).map(entry => {
  const path=resolve(directory,entry.name); return entry.isDirectory()?latestSource(path):statSync(path).mtimeMs;
}));
const requireFreshJar = service => {
  const directory=resolve(root,`backend/services/${service}`), jar=resolve(directory,`target/${service}-0.0.1-SNAPSHOT.jar`);
  check(existsSync(jar) && statSync(jar).mtimeMs>=latestSource(resolve(directory,'src/main')),'Package fresh service jars before network acceptance');
};
const launch = (service, servicePort, environment) => {
  const jar = resolve(root, `backend/services/${service}/target/${service}-0.0.1-SNAPSHOT.jar`);
  check(existsSync(jar), 'Package both service jars before running this test');
  const log = createWriteStream(resolve(root, `logs/q5-network-${service}.log`), { flags: 'a' }); streams.push(log);
  check(['auth-service','qr-service'].includes(service),'Unapproved fixture service');
  const databaseUrl=`jdbc:postgresql://127.0.0.1:${environment.POSTGRES_PORT}/${service==='auth-service'?'q5_auth':'q5_qr'}`;
  const childEnvironment={...environment,SPRING_DATASOURCE_URL:databaseUrl,
    SPRING_DATASOURCE_USERNAME:'q5test',SPRING_DATASOURCE_PASSWORD:environment.POSTGRES_PASSWORD,
    SPRING_DATASOURCE_DRIVER_CLASS_NAME:'org.postgresql.Driver',SPRING_FLYWAY_URL:databaseUrl,
    SPRING_FLYWAY_USER:'q5test',SPRING_FLYWAY_PASSWORD:environment.POSTGRES_PASSWORD,SPRING_FLYWAY_DRIVER_CLASS_NAME:'org.postgresql.Driver'};
  const child = spawn(java, ['-jar', jar, `--server.port=${servicePort}`], { cwd: runtime, env: childEnvironment, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false }); jobs.push(child);
  return child;
};
const response = (url, options) => fetch(url, { ...options, signal: AbortSignal.timeout(7000) });
const readyHttp = async url => { const result = await response(`${url}/actuator/health/liveness`); return result.ok; };
const stop = async job => {
  if (!alive(job)) return;
  const exited=new Promise(resolve => job.once('exit',resolve));
  job.kill();
  await Promise.race([exited,new Promise((_,reject)=>setTimeout(()=>reject(new FixtureError('JVM did not stop')),10000))]);
};

try {
  mkdirSync(resolve(root, 'logs'), { recursive: true });
  mkdirSync(runtime);
  requireFreshJar('auth-service'); requireFreshJar('qr-service');
  const contextArgs=['context','inspect'];
  if (process.env.DOCKER_CONTEXT) contextArgs.push(process.env.DOCKER_CONTEXT);
  contextArgs.push('--format','{{.Endpoints.docker.Host}}');
  const endpoint=process.env.DOCKER_CONTEXT ? docker(contextArgs) : (process.env.DOCKER_HOST || docker(contextArgs));
  let localDocker=/^npipe:\/{4}\.\/pipe\//i.test(endpoint);
  try {
    const address=new URL(endpoint);
    localDocker ||= (address.protocol==='unix:' && address.hostname==='')
      || (['tcp:','http:','https:'].includes(address.protocol) && ['localhost','127.0.0.1','[::1]'].includes(address.hostname));
  } catch {}
  check(localDocker,'Network acceptance requires a local Docker endpoint');
  const staff = keys(), command = keys(), receipt = keys();
  const password = `Q5-Fixture-${randomBytes(16).toString('hex')}`;
  const pgPassword = randomBytes(32).toString('base64url');
  const databasePort = String(await port());
  container = docker(['run', '--pull=never', '-d', '--label', `restofy.q5.test=${run}`, '--env', 'POSTGRES_USER=q5test',
    '--env', 'POSTGRES_PASSWORD', '-p', `127.0.0.1:${databasePort}:5432`, 'postgres:17-alpine'], { env: { ...process.env, POSTGRES_PASSWORD: pgPassword } });
  check(/^[a-f0-9]{64}$/.test(container), 'Invalid disposable container ID');
  await wait('PostgreSQL final TCP startup', () => sql('postgres', "SELECT NOT pg_is_in_recovery() AND current_setting('fsync')='on'") === 't');
  sql('postgres', 'CREATE DATABASE q5_auth; CREATE DATABASE q5_qr;');
  await wait('fixture databases ready', () => sql('q5_auth','SELECT 1')==='1' && sql('q5_qr','SELECT 1')==='1');
  const authPort = await port(), qrPort = await port(), qr2Port = await port();
  check(new Set([authPort,qrPort,qr2Port]).size === 3, 'Port reservation collision');
  let offline = false, dropNextAck = false, dropped = false;
  proxy = httpServer(async (request, result) => {
    if (offline) { result.writeHead(503); result.end(); return; }
    try {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const headers = { ...request.headers };
      for (const header of ['host','connection','transfer-encoding','content-length','upgrade','http2-settings','te','trailer','keep-alive']) delete headers[header];
      const peer = await fetch(`http://127.0.0.1:${qrPort}${request.url}`, { method: request.method, headers, body: Buffer.concat(chunks), signal: AbortSignal.timeout(6000) });
      const body = Buffer.from(await peer.arrayBuffer());
      if (dropNextAck && request.url === '/internal/q5/revocations' && peer.status === 200) {
        dropNextAck = false; dropped = true; result.destroy(); return;
      }
      result.writeHead(peer.status, { 'content-type': peer.headers.get('content-type') || 'application/json' }); result.end(body);
    } catch { result.destroy(); }
  });
  await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
  const base = { ...osEnvironment, SERVER_ADDRESS:'127.0.0.1', SPRING_PROFILES_ACTIVE: 'dev', APP_ENV: 'dev', LOG_LEVEL: 'WARN', SWAGGER_ENABLED: 'false',
    POSTGRES_HOST: '127.0.0.1', POSTGRES_PORT: databasePort, POSTGRES_USER: 'q5test', POSTGRES_PASSWORD: pgPassword,
    DB_AUTH_NAME: 'q5_auth', DB_QR_NAME: 'q5_qr', AUTH_RESTAURANT_ID: randomUUID(), AUTH_JWT_PRIVATE_KEY: staff.private,
    MAIL_AUTH_PUBLIC_KEY: staff.public, AUTH_Q5_COMMAND_PRIVATE_KEY: command.private, AUTH_Q5_COMMAND_PUBLIC_KEY: command.public,
    QR_Q5_RECEIPT_PRIVATE_KEY: receipt.private, AUTH_Q5_QR_PUBLIC_KEY: receipt.public, AUTH_Q5_QR_GENERATION: randomUUID(),
    AUTH_Q5_QR_URL: `http://127.0.0.1:${proxy.address().port}`, AUTH_CSRF_SECRET: randomBytes(32).toString('base64'),
    AUTH_MFA_ENCRYPTION_KEY: randomBytes(32).toString('base64'), AUTH_RESET_HMAC_KEY: randomBytes(32).toString('base64'),
    AUTH_COOKIE_SECURE: 'false', AUTH_MFA_REQUIRED: 'false', AUTH_INITIAL_ADMIN_ENABLED: 'false', AUTH_DEV_USERS_ENABLED: 'true',
    AUTH_DEV_ADMIN_EMAIL: 'q5admin@example.com', AUTH_DEV_ADMIN_PASSWORD: password, AUTH_DEV_WAITER_EMAIL: 'q5waiter@example.com',
    AUTH_DEV_WAITER_PASSWORD: password, AUTH_DEV_KITCHEN_EMAIL: 'q5kitchen@example.com', AUTH_DEV_KITCHEN_PASSWORD: password,
    JWT_ISSUER: 'restofy-auth', JWT_ACCESS_TTL_SECONDS: '300', JWT_REFRESH_TTL_SECONDS: '604800',
    CORS_ALLOWED_ORIGINS: 'http://localhost:5000', QR_PAYLOAD_ENCRYPTION_KEY: randomBytes(32).toString('base64'), QR_PUBLIC_ENTRY_URL: 'https://restofy.example/qr' };
  base.SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT = '2000';
  base.SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT = '1000';
  base.LOGGING_LEVEL_ORG_SPRINGFRAMEWORK = 'WARN';
  base.LOGGING_LEVEL_ORG_HIBERNATE = 'WARN';
  for (const service of ['AUTH','ORDER','KITCHEN','WAITER','MENU','QR','PAYMENT','NOTIFICATION','ADMIN','RATING','MAIL','TIMER']) base[`${service}_SERVICE_URL`] = 'http://127.0.0.1:9';
  base.AUTH_SERVICE_URL = `http://127.0.0.1:${authPort}`;
  const authUrl = `http://127.0.0.1:${authPort}`, qrUrl = `http://127.0.0.1:${qrPort}`;
  let auth = launch('auth-service', authPort, base), qr1 = launch('qr-service', qrPort, base);
  await wait('service startup', async () => await readyHttp(authUrl) && await readyHttp(qrUrl));
  await wait('authenticated initial bootstrap', () => sql('q5_auth', "SELECT phase FROM staff_revocation_source_state") === 'ACTIVE'
    && sql('q5_qr', 'SELECT ready FROM staff_revocation_guard_state') === 't');
  console.log('PASS authenticated cold bootstrap');
  let qr2=launch('qr-service',qr2Port,base);
  await wait('second QR instance startup',() => readyHttp(`http://127.0.0.1:${qr2Port}`));
  const jar = new Map();
  const authRequest = async (path, method = 'GET', body) => {
    const headers = { Origin: 'http://localhost:5000', Cookie: [...jar].map(([key,value]) => `${key}=${value}`).join('; ') };
    if (method !== 'GET') { headers['Content-Type'] = 'application/json'; headers['X-CSRF-TOKEN'] = jar.get('restofy_csrf') || ''; }
    const result = await response(`${authUrl}/api/v1/auth${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
    for (const cookie of result.headers.getSetCookie()) {
      const first = cookie.split(';')[0], index = first.indexOf('='); const key = first.slice(0,index), value = first.slice(index+1);
      if (value) jar.set(key,value); else jar.delete(key);
    }
    return result;
  };
  const login = async () => {
    jar.clear(); check((await authRequest('/csrf')).status === 200, 'CSRF setup failed');
    check((await authRequest('/login','POST',{ email:'q5waiter@example.com',password,deviceLabel:'Q5 network fixture' })).status === 200, 'Login failed');
    check(jar.has('restofy_access') && jar.has('restofy_refresh'), 'Credentials missing');
    return jar.get('restofy_access');
  };
  const qrStatus = async (token,peer=qrUrl) => (await response(`${peer}/api/v1/qr/tables`, { headers: { Cookie: `restofy_access=${token}` } })).status;
  const old = await login(); check(await qrStatus(old) === 200, 'Fresh local authorization failed');
  // Real additional instances share the primary DB; a valid UUID is not sufficient source identity.
  const correctAuthPort = await port();
  const correctAuth = launch('auth-service',correctAuthPort,base);
  const correctAuthUrl = `http://127.0.0.1:${correctAuthPort}`;
  await wait('second correct Auth readiness',async () => (await response(`${correctAuthUrl}/actuator/health/readiness`)).status === 200);
  const cookieHeader = () => [...jar].map(([key,value]) => `${key}=${value}`).join('; ');
  for (const setting of ['AUTH_Q5_QR_GENERATION','AUTH_RESTAURANT_ID']) {
    const badPort = await port(), badUrl = `http://127.0.0.1:${badPort}`;
    const badAuth = launch('auth-service',badPort,{...base,[setting]:randomUUID()});
    await wait('mismatched Auth startup',() => readyHttp(badUrl));
    const csrfResponse = await response(`${badUrl}/api/v1/auth/csrf`);
    check(csrfResponse.status === 200,'Mismatched instance did not finish HTTP startup');
    const csrfCookies = csrfResponse.headers.getSetCookie().map(value => value.split(';')[0]);
    const csrfToken = csrfCookies.find(value => value.startsWith('restofy_csrf='))?.slice('restofy_csrf='.length);
    check(Boolean(csrfToken),'Mismatch login proof missing CSRF');
    const denied = await response(`${badUrl}/api/v1/auth/login`,{method:'POST',headers:{Origin:'http://localhost:5000',
      Cookie:csrfCookies.join('; '),'Content-Type':'application/json','X-CSRF-TOKEN':csrfToken},
      body:JSON.stringify({email:'q5waiter@example.com',password})});
    check(denied.status === 503,'Mismatched Auth minted credentials');
    check((await response(`${badUrl}/actuator/health/readiness`)).status === 503,'Mismatched Auth is ready');
    if (setting === 'AUTH_Q5_QR_GENERATION') {
      const deniedLogout = await response(`${badUrl}/api/v1/auth/logout`,{method:'POST',headers:{Origin:'http://localhost:5000',
        Cookie:cookieHeader(),'Content-Type':'application/json','X-CSRF-TOKEN':jar.get('restofy_csrf')}});
      check(deniedLogout.status === 503,'Mismatched logout completed');
    }
    await sleep(5500); // Include at least one scheduled worker attempt, not only the HTTP path.
    check(sql('q5_auth','SELECT count(*) FROM staff_revocation_operations') === '0','Mismatched instance poisoned the operation queue');
    check(sql('q5_auth',"SELECT phase='ACTIVE' AND generation='"+base.AUTH_Q5_QR_GENERATION+"' AND restaurant_id='"+base.AUTH_RESTAURANT_ID+"' FROM staff_revocation_source_state") === 't','Mismatched worker changed the source identity');
    check((await response(`${correctAuthUrl}/api/v1/auth/session`,{headers:{Cookie:cookieHeader()}})).status === 200,'Correct Auth instance was blocked');
    await stop(badAuth);
  }
  await stop(correctAuth);
  console.log('PASS real Auth instances reject wrong generation/restaurant without pending queue poisoning');
  const wrongKeyPort = await port();
  const wrongKeyQr = launch('qr-service',wrongKeyPort,{...base,MAIL_AUTH_PUBLIC_KEY:keys().public});
  await wait('wrong staff peer key instance',() => readyHttp(`http://127.0.0.1:${wrongKeyPort}`));
  check(await qrStatus(old,`http://127.0.0.1:${wrongKeyPort}`) === 401,'Wrong staff key accepted the actual Auth JWT');
  check(await qrStatus(old) === 200,'Correct staff peer key stopped accepting the actual Auth JWT');
  await stop(wrongKeyQr);
  console.log('PASS wrong peer staff key fails real Auth JWT smoke; readiness is not peer provisioning proof');
  auth.kill(); await new Promise(resolve => auth.once('exit', resolve));
  await wait('Auth port is actually closed',async () => { try { await response(`${authUrl}/actuator/health/liveness`); return false; } catch { return true; } },15000);
  check(await qrStatus(old) === 200, 'QR depends on online Auth');
  check(await qrStatus(old,`http://127.0.0.1:${qr2Port}`) === 200, 'Second QR instance depends on Auth');
  auth = launch('auth-service', authPort, base); await wait('Auth restart', () => readyHttp(authUrl));
  console.log('PASS normal QR authorization while Auth is stopped');
  offline = true; stage = 'QR unavailable logout';
  check((await authRequest('/logout','POST')).status === 204, 'QR outage blocked local-first logout');
  check(!jar.has('restofy_access') && !jar.has('restofy_refresh') && !jar.has('restofy_mfa') && !jar.has('restofy_csrf'),
    'Logout did not clear every Auth cookie');
  check(sql('q5_auth', "SELECT count(*) FROM staff_revocation_operations WHERE status='PREPARED' AND completion_policy='LOCAL_FIRST'") === '1',
    'Durable local-first delivery missing');
  check(sql('q5_auth', 'SELECT count(*) FROM user_sessions WHERE revoked_at IS NOT NULL') === '1', 'Local logout was not committed');
  check(sql('q5_auth', 'SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL') === '1', 'Local refresh revocation was not committed');
  check(await qrStatus(old) === 200, 'Offline QR unexpectedly learned the pending fence');
  console.log('PASS QR outage permits atomic local-first logout with durable delivery');
  auth.kill(); await new Promise(resolve => auth.once('exit',resolve));
  auth=launch('auth-service',authPort,base); await wait('Auth crash recovery from PREPARE',() => readyHttp(authUrl));
  check(sql('q5_auth',"SELECT status FROM staff_revocation_operations") === 'PREPARED','PREPARE lost after Auth crash');
  console.log('PASS durable LOCAL_FIRST delivery survives Auth process crash');
  dropNextAck = true; offline = false;
  await wait('lost committed ACK', () => dropped, 65000);
  check(sql('q5_qr','SELECT count(*) FROM staff_revocation_receipts') === '2', 'Durable QR receipt missing');
  check(sql('q5_auth',"SELECT status FROM staff_revocation_operations") === 'PREPARED', 'Lost ACK finalized Auth');
  check(await qrStatus(old) === 401, 'Committed QR fence not enforced');
  check(await qrStatus(old,`http://127.0.0.1:${qr2Port}`) === 401, 'Second instance missed the committed fence');
  console.log('PASS committed QR fence with lost ACK; Auth remains pending');
  auth.kill(); await new Promise(resolve => auth.once('exit',resolve));
  auth=launch('auth-service',authPort,base); await wait('Auth recovery after lost ACK',() => readyHttp(authUrl));
  await wait('durable retry finalization', () => sql('q5_auth',"SELECT status FROM staff_revocation_operations") === 'COMPLETED',65000);
  check(await qrStatus(old) === 401, 'Post-FINALIZE old JWT accepted');
  check(sql('q5_qr','SELECT count(*) FROM staff_revocation_receipts') === '2', 'Retry duplicated durable effects');
  console.log('PASS same-operation retry and post-FINALIZE rejection');
  const beforeRestart = await login(); check(await qrStatus(beforeRestart) === 200, 'Second session failed');
  offline = true; docker(['stop','-t','2',container]);
  stage = 'database outage';
  check(await qrStatus(beforeRestart) === 503, 'Database outage accepted staff');
  console.log('PASS real database outage returns fail-closed 503');
  docker(['start',container]);
  await wait('PostgreSQL restart', () => sql('postgres','SELECT 1') === '1');
  check(await qrStatus(beforeRestart) === 503, 'Old database incarnation accepted staff');
  console.log('PASS database restart fails closed before resynchronization');
  offline = false;
  await wait('safe database rebootstrap', () => sql('q5_auth',"SELECT phase='ACTIVE' AND database_start=pg_postmaster_start_time() FROM staff_revocation_source_state") === 't'
    && sql('q5_qr','SELECT ready AND database_start=pg_postmaster_start_time() FROM staff_revocation_guard_state') === 't');
  check(await qrStatus(beforeRestart) === 401, 'Restored old SID accepted after baseline');
  const recovered=await login();
  check(await qrStatus(recovered) === 200, 'Fresh session could not use recovered service');
  console.log('PASS full rebootstrap rejects old SIDs and admits a fresh session');
  // Snapshot BEFORE the next FINALIZE, in the SAME postmaster incarnation used by restore below.
  const backupStart=sql('q5_qr','SELECT pg_postmaster_start_time()');
  const backupNonce=sql('q5_qr','SELECT bootstrap_nonce FROM staff_revocation_guard_state');
  const oldBaseline=sql('q5_auth','SELECT bootstrap_id FROM staff_revocation_source_state');
  docker(['exec',container,'pg_dump','-h','127.0.0.1','-U','q5test','-d','q5_qr','-Fc','-f','/tmp/q5-before-finalize.dump']);
  stage='ACK_REQUIRED FINALIZE failure after committed ACK';
  const sid=JSON.parse(Buffer.from(recovered.split('.')[1],'base64url').toString()).sid;
  const expiresAt=JSON.parse(Buffer.from(recovered.split('.')[1],'base64url').toString()).exp;
  check(/^[0-9a-f-]{36}$/.test(sid),'Unexpected fixture SID');
  sql('q5_auth',`CREATE FUNCTION q5_fixture_fail_finalize() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'Q5 fixture finalization fault'; END IF;
    RETURN NEW; END; $$;
    CREATE TRIGGER q5_fixture_finalize BEFORE UPDATE OF revoked_at ON user_sessions FOR EACH ROW EXECUTE FUNCTION q5_fixture_fail_finalize();`);
  const strictOperation=randomUUID();
  sql('q5_auth',`WITH bumped AS (
      UPDATE staff_users SET revocation_revision=revocation_revision+1
      WHERE id=(SELECT staff_user_id FROM user_sessions WHERE id='${sid}')
      RETURNING id,revocation_revision,version
    ) INSERT INTO staff_revocation_operations
      (id,user_id,restaurant_id,revision,action,expected_user_version,consumer_generation,status,created_at,completion_policy)
      SELECT '${strictOperation}',id,'${base.AUTH_RESTAURANT_ID}',revocation_revision,'ALL',version,
        '${base.AUTH_Q5_QR_GENERATION}','PREPARED',now(),'ACK_REQUIRED' FROM bumped;
    INSERT INTO staff_revocation_targets(operation_id,session_id,deny_until)
      VALUES ('${strictOperation}','${sid}',to_timestamp(${expiresAt}));`);
  await wait('strict ACK retained after failed FINALIZE',
    () => sql('q5_auth',`SELECT status FROM staff_revocation_operations WHERE id='${strictOperation}'`) === 'ACKNOWLEDGED',65000);
  check(sql('q5_auth',`SELECT revoked_at IS NULL FROM user_sessions WHERE id='${sid}'`) === 't','Failed FINALIZE partly committed');
  check(await qrStatus(recovered)===401,'QR fence removed after failed Auth FINALIZE');
  sql('q5_auth','DROP TRIGGER q5_fixture_finalize ON user_sessions; DROP FUNCTION q5_fixture_fail_finalize();');
  await wait('safe FINALIZE retry',() => sql('q5_auth',`SELECT status FROM staff_revocation_operations WHERE id='${strictOperation}'`) === 'COMPLETED',65000);
  check(await qrStatus(recovered)===401,'Old JWT accepted after recovered FINALIZE');
  console.log('PASS failed strict Auth FINALIZE retains QR fence and durable ACK; retry completes');
  stage='same-postmaster logical restore counterexample';
  offline=true;
  await stop(auth); await stop(qr1); await stop(qr2);
  docker(['exec',container,'pg_restore','-h','127.0.0.1','-U','q5test','-d','q5_qr','--clean','--if-exists','--no-owner','--exit-on-error','/tmp/q5-before-finalize.dump']);
  check(sql('q5_qr','SELECT pg_postmaster_start_time()')===backupStart,'Restore unexpectedly restarted PostgreSQL');
  check(sql('q5_qr','SELECT bootstrap_nonce FROM staff_revocation_guard_state')===backupNonce,'Counterexample nonce changed');
  // Negative control ONLY in this disposable loopback fixture: production MUST NOT start old workers/ingress here.
  qr1=launch('qr-service',qrPort,base);
  await wait('restored QR negative control',()=>readyHttp(qrUrl));
  check(await qrStatus(recovered)===200,'Restore counterexample was not reproduced');
  console.log('PASS reproduced unsafe logical restore without maintenance quarantine (isolated negative control)');
  await stop(qr1);
  sql('q5_qr',readFileSync(resolve(root,'ops/qr/q5-restore-quarantine.sql'),'utf8'));
  const maintenanceNonce=sql('q5_qr','SELECT bootstrap_nonce FROM staff_revocation_guard_state');
  check(maintenanceNonce!==backupNonce,'Maintenance reused restored nonce');
  qr1=launch('qr-service',qrPort,base); qr2=launch('qr-service',qr2Port,base);
  await wait('quarantined QR processes',async()=>await readyHttp(qrUrl)&&await readyHttp(`http://127.0.0.1:${qr2Port}`));
  check(await qrStatus(recovered)===503 && await qrStatus(recovered,`http://127.0.0.1:${qr2Port}`)===503,'Restore quarantine accepted staff');
  check((await response(`${qrUrl}/actuator/health/readiness`)).status===503,'Restore readiness opened early');
  console.log('PASS fresh maintenance nonce keeps both restored QR instances closed');
  offline=false; auth=launch('auth-service',authPort,base);
  await wait('fresh full restore baseline',()=>sql('q5_auth',"SELECT phase='ACTIVE' AND qr_nonce='"+maintenanceNonce+"'::uuid FROM staff_revocation_source_state")==='t'
    &&sql('q5_qr','SELECT ready FROM staff_revocation_guard_state')==='t');
  check(sql('q5_auth','SELECT bootstrap_id FROM staff_revocation_source_state')!==oldBaseline,'Auth reused obsolete baseline');
  check(await qrStatus(recovered)===401 && await qrStatus(recovered,`http://127.0.0.1:${qr2Port}`)===401,'Finalized SID resurrected after supported restore');
  check(await qrStatus(await login())===200,'Fresh login failed after supported restore');
  check(sql('q5_qr','SELECT pg_postmaster_start_time()')===backupStart,'Proof requires same PostgreSQL process');
  console.log('PASS supported same-process restore rejects finalized SIDs with a new complete baseline');
  console.log('Q5 network acceptance passed');
} catch (failure) {
  const kind=failure instanceof FixtureError ? 'FIXTURE' : 'ACCEPTANCE';
  console.error(`Q5 network ${kind} FAILED at: ${stage}; ${failure.message}. Inspect ignored logs/q5-network-*.log.`);
  process.exitCode = 1;
} finally {
  if (proxy) { proxy.closeAllConnections(); proxy.close(); }
  for (const job of jobs) if (alive(job)) job.kill();
  await Promise.all(jobs.filter(alive).map(job => new Promise(resolve => { job.once('exit',resolve); setTimeout(resolve,5000); })));
  for (const stream of streams) stream.end();
  if (container && /^[a-f0-9]{64}$/.test(container)) {
    const owner = docker(['inspect','--format','{{index .Config.Labels "restofy.q5.test"}}',container]);
    check(owner === run, 'Refusing to remove a container not owned by this test');
    docker(['rm','-f','-v',container]);
  }
}
