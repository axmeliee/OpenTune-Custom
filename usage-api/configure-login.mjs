// Read credentials from GitHub secrets; never print them.
import {spawnSync} from 'node:child_process';
import {randomBytes} from 'node:crypto';
const username=process.env.USAGE_ADMIN_USERNAME,password=process.env.USAGE_ADMIN_PASSWORD;
if(!username && !password) {
  console.log('Login secrets not supplied. Existing Worker credentials are preserved; new logins stay locked until configured.');
} else {
  if(!/^[A-Za-z0-9_.@+-]{3,128}$/.test(username || '')) throw new Error('USAGE_ADMIN_USERNAME must contain 3–128 letters, numbers or ._@+- characters; email addresses are accepted.');
  if(typeof password!=='string' || password.length<16 || password.length>256) throw new Error('USAGE_ADMIN_PASSWORD must contain 16–256 characters. Update this repository secret and rerun Usage API.');
  const values={USAGE_ADMIN_USERNAME:username,USAGE_ADMIN_PASSWORD:password,USAGE_SESSION_SECRET:randomBytes(32).toString('hex')};
  const result=spawnSync('npx',['--yes','wrangler@4','secret','bulk'],{input:JSON.stringify(values),encoding:'utf8',stdio:['pipe','pipe','pipe']});
  if(result.status!==0) throw new Error('Login secret update failed; check Cloudflare deployment permissions.');
  console.log('Owner login configured. Previous browser sessions must sign in again.');
}
