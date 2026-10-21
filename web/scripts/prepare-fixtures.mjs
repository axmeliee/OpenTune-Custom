// Pinned upstream test responses, fetched only for tests and verified before use.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const entries=JSON.parse(await readFile(new URL('./fixtures.json',import.meta.url),'utf8'));
const upstream='https://raw.githubusercontent.com/kushagrasinghx/BitChord/edc1e6f36febf71a118e649411d2fc21b70ead45/web/';
for(const entry of entries){
 const path=fileURLToPath(new URL('../'+entry.path,import.meta.url));
 let content=await readFile(path).catch(()=>null);
 const matches=value=>value&&createHash('sha256').update(value).digest('hex')===entry.sha256;
 if(matches(content))continue;
 const response=await fetch(upstream+entry.path,{signal:AbortSignal.timeout(30000)});
 if(!response.ok)throw new Error('Could not fetch pinned test fixture '+entry.path);
 content=Buffer.from(await response.arrayBuffer());if(!matches(content))throw new Error('Fixture checksum mismatch: '+entry.path);
 await mkdir(dirname(path),{recursive:true});await writeFile(path,content);
}
console.log('Pinned browser test fixtures verified.');
