import {describe,it,expect} from 'vitest';
import {endpoint,publicHttps,matches,flacInfo} from './protocol';
describe('addon contract',()=>{
 it('preserves private paths and query parameters without breaking opaque ids',()=>{expect(endpoint('https://example.com/private/manifest.json?token=test','stream/a%2Fb',{quality:'lossless'})).toBe('https://example.com/private/stream/a%2Fb?token=test&quality=lossless');});
 it('rejects local destinations and credentials',()=>{for(const url of ['http://example.com','https://127.0.0.1','https://10.0.0.1','https://user:password@example.com'])expect(()=>publicHttps(url)).toThrow();});
 it('requires matching title, performer and duration',()=>{const wanted={title:'Example',artist:'An Artist',duration:200};expect(matches(wanted,{...wanted,id:'1'})).toBe(true);expect(matches(wanted,{...wanted,id:'2',title:'Example Live'})).toBe(false);expect(matches(wanted,{...wanted,id:'3',duration:250})).toBe(false);});
 it('does not trust a filename or provider label for FLAC',()=>{expect(()=>flacInfo(new TextEncoder().encode('<html>error</html>'))).toThrow();});
});
