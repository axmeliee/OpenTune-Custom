"""Read-only protocol probes; never log signed audio URLs or URL path tokens."""
import json, urllib.request, urllib.parse, time, pathlib
ROOTS = [
    ('bluebit', 'https://bluebit.jucepe0929.workers.dev'),
    ('mono', 'https://eljohnzc-mono.de.deplexo.com'),
    ('bridge', 'https://losslessbridge.de.deplexo.com'),
]
out=pathlib.Path('build/addon-probes');out.mkdir(parents=True,exist_ok=True)
def get(url, limit=1048576):
    req=urllib.request.Request(url,headers={'User-Agent':'OpenTune-Addon-Compatibility/1.0','Accept':'application/json'})
    with urllib.request.urlopen(req,timeout=18) as response:
        data=response.read(limit+1)
        if len(data)>limit:raise ValueError('oversized response')
        return json.loads(data)
def safe(value):
    if isinstance(value,dict):return {k:safe(v) for k,v in value.items() if k.lower() not in ('headers','token','authorization','secret','cookie')}
    if isinstance(value,list):return [safe(v) for v in value[:3]]
    if isinstance(value,str) and ('://' in value or len(value)>500):return '[redacted URL or long value]'
    return value
for name,base in ROOTS:
    report={'name':name}
    try:
        manifest=get(base+'/manifest.json');report['manifest']=safe(manifest)
        params={s['key']:s['default'] for s in manifest.get('settings',[]) if 'key' in s and isinstance(s.get('default'),(str,int,float,bool))}
        # Respect the declared lossless tier when one exists.
        setting=next((s for s in manifest.get('settings',[]) if s.get('key')=='quality'),{})
        choices=[str(o.get('value','')) for o in setting.get('options',[])]
        params['quality']=next((x for x in choices if 'lossless' in x.lower() or 'hi_res' in x.lower()),'LOSSLESS')
        for query in ['Wo Noor AP Dhillon','Blinding Lights The Weeknd']:
            result=get(base+'/search?'+urllib.parse.urlencode(dict(params,q=query)))
            tracks=result.get('tracks',[]);report.setdefault('searches',[]).append({'query':query,'count':len(tracks),'examples':safe(tracks[:2])})
            if tracks:
                track=tracks[0]
                stream=get(base+'/stream/'+urllib.parse.quote(str(track['id']),safe='')+'?'+urllib.parse.urlencode(params))
                report['stream']=safe(stream)
                url=stream.get('url')
                if url and urllib.parse.urlparse(url).scheme=='https' and not stream.get('encrypted',False):
                    req=urllib.request.Request(url,headers={'Range':'bytes=0-65535','User-Agent':'OpenTune-Addon-Compatibility/1.0'})
                    with urllib.request.urlopen(req,timeout=18) as response:
                        prefix=response.read(65536)
                        report['media']={'status':response.status,'contentType':response.headers.get('Content-Type'),'bytesRead':len(prefix),'flac':prefix.startswith(b'fLaC'),'mp4':b'ftyp' in prefix[:32],'dash':b'<MPD' in prefix[:2048]}
                break
    except Exception as error:report['error']=type(error).__name__+(': HTTP '+str(error.code) if hasattr(error,'code') else '')
    (out/(name+'.json')).write_text(json.dumps(report,indent=2))
    print('ADDON_PROBE '+json.dumps(report),flush=True)
