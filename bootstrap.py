"""Fetch only pinned build inputs; the installed Android app is completely offline."""
from pathlib import Path
import hashlib,json,urllib.request,os,sys
ROOT=Path(__file__).resolve().parent
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1048576),b''):h.update(block)
    return h.hexdigest()
def main():
    entries=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf-8'))
    for item in entries:
        target=(ROOT/item['path']).resolve()
        if not target.is_relative_to(ROOT):raise ValueError('Dependency path escapes project')
        if target.is_file() and digest(target)==item['sha256']:
            print('OK '+item['path'],flush=True);continue
        target.parent.mkdir(parents=True,exist_ok=True)
        temporary=target.with_name(target.name+'.part')
        request=urllib.request.Request(item['url'],headers={'User-Agent':'Langyu-build-bootstrap/1.1'})
        with urllib.request.urlopen(request,timeout=90) as response,temporary.open('wb') as out:
            while True:
                data=response.read(1048576)
                if not data:break
                out.write(data)
        if digest(temporary)!=item['sha256']:
            temporary.unlink(missing_ok=True);raise ValueError('Checksum mismatch: '+item['path'])
        os.replace(temporary,target);print('VERIFIED '+item['path'],flush=True)
if __name__=='__main__':main()
