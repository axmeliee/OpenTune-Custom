// Credentials are read only from CI secrets and piped to Wrangler, never logged.
import {spawnSync} from 'node:child_process';
const names = ['ACCESS_TEAM_DOMAIN','ACCESS_AUD','ACCESS_OWNER_EMAIL'];
const present = names.filter(name => process.env[name]);
if (present.length === 0) {
  console.log('Access configuration not supplied. Existing Worker secrets are preserved; new deployments remain locked until Access is configured. See usage-api/README.md.');
} else {
  if (present.length !== names.length || !/^[a-z0-9-]+\.cloudflareaccess\.com$/.test(process.env.ACCESS_TEAM_DOMAIN) ||
      !/^[a-f0-9]{64}$/.test(process.env.ACCESS_AUD) || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(process.env.ACCESS_OWNER_EMAIL)) {
    throw new Error('Set all three valid Access configuration secrets; no partial update was made.');
  }
  const values = Object.fromEntries(names.map(name => [name, process.env[name]]));
  const result = spawnSync('npx',['--yes','wrangler@4','secret','bulk'],{input:JSON.stringify(values),encoding:'utf8',stdio:['pipe','pipe','pipe']});
  if (result.status !== 0) throw new Error('Access secret update failed; check the Cloudflare token permissions.');
  console.log('Stored Access validation configuration in the existing Worker.');
}
