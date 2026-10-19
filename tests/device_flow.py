"""Device UI verification, deliberately restricted to the authorized Moto."""
from pathlib import Path
import subprocess, xml.etree.ElementTree as ET, time, json, sys, os
ROOT=Path(__file__).resolve().parents[1]
ADB=os.environ.get('LANGYU_ADB','adb')
SERIAL=os.environ.get('LANGYU_TEST_DEVICE','')
OUT=ROOT/'work'/'verification';OUT.mkdir(exist_ok=True,parents=True)
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
def adb(*args,binary=False):
    if not SERIAL:raise RuntimeError('Set LANGYU_TEST_DEVICE to the explicitly authorized test device serial')
    p=subprocess.run([ADB,'-s',SERIAL,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if p.returncode:raise RuntimeError(p.stderr.decode('utf-8',errors='replace'))
    return p.stdout if binary else p.stdout.decode('utf-8',errors='replace').strip()
def guard():
    if os.environ.get('LANGYU_TEST_MODEL','moto').lower() not in adb('shell','getprop','ro.product.model').lower():raise RuntimeError('Test device does not match LANGYU_TEST_MODEL')
def dump(name=None):
    message=adb('shell','uiautomator','dump','/sdcard/langyu-ui.xml')
    if 'dumped to' not in message:raise RuntimeError('Cannot obtain a fresh UI hierarchy: '+message)
    raw=adb('exec-out','cat','/sdcard/langyu-ui.xml',binary=True)
    if name:(OUT/(name+'.xml')).write_bytes(raw)
    return ET.fromstring(raw)
def labels(root=None):
    return [(n.get('text') or n.get('content-desc'),n.get('bounds')) for n in (root if root is not None else dump()).iter('node') if n.get('text') or n.get('content-desc')]
def tap_text(text,contains=False):
    root=dump()
    candidates=[n for n in root.iter('node') if any((text in n.get(k,'') if contains else text==n.get(k,'')) for k in ('text','content-desc'))]
    if not candidates:raise RuntimeError('UI target absent: '+text+'; visible: '+str(labels(root)))
    import re
    n=candidates[-1];v=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
    adb('shell','input','tap',str((v[0]+v[2])//2),str((v[1]+v[3])//2));time.sleep(.35)
def screenshot(name):
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
def wait_text(text,timeout=15):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if any(text in t for t,b in labels()):return
        time.sleep(.4)
    raise RuntimeError('Timeout waiting for: '+text)
def tabs():
    guard();adb('shell','am','start','-W','-n','com.langyu.assistant/.MainActivity')
    evidence=[]
    for idx,label in enumerate(('对局','记录','分析','话术','设置')):
        tap_text(label+'标签',contains=True)
        root=dump('moto-tab-'+str(idx));screenshot('moto-tab-'+str(idx))
        evidence.append({'tab':label,'texts':[t for t,b in labels(root)]})
    (OUT/'ui-tabs.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS: five native tabs opened and screenshot evidence saved')
if __name__=='__main__':
    guard()
    if len(sys.argv)>1 and sys.argv[1]=='tabs':tabs()
    else:print(json.dumps(labels(dump('moto-current')),ensure_ascii=False,indent=2))
