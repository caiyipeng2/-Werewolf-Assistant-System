"""Reproducible SDK-only Android build; all outputs stay in this project."""
from pathlib import Path
import subprocess, zipfile, shutil, hashlib, json, os, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('LANGYU_ANDROID_SDK', r'D:\Unity\Editor\Data\PlaybackEngines\AndroidPlayer\SDK'))
JDK = Path(os.environ.get('LANGYU_JDK', r'D:\java'))
BT = SDK / 'build-tools' / '36.0.0'
ANDROID = SDK / 'platforms' / 'android-35' / 'android.jar'
WORK = ROOT / 'work' / 'build'
OUT = ROOT / 'outputs'

def run(*args):
    command = []
    for x in args:
        if isinstance(x, Path):
            try: x = x.relative_to(ROOT)
            except ValueError: pass
        command.append(str(x))
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try: output = result.stdout.decode('utf-8')
    except UnicodeDecodeError: output = result.stdout.decode('gb18030',errors='replace')
    if output.strip(): print(output, flush=True)
    if result.returncode: raise SystemExit(f'Failed ({result.returncode}): {command[0]}')

def main():
    WORK.mkdir(parents=True, exist_ok=True); OUT.mkdir(exist_ok=True)
    for directory in ('generated','classes','dex'):
        target = WORK / directory
        if target.exists(): shutil.rmtree(target)
        target.mkdir()
    for name in ('vosk','jna','tesseract'):
        with zipfile.ZipFile(ROOT / 'vendor' / (name+'.aar')) as z: z.extractall(ROOT/'vendor'/name)
    run(BT/'aapt2.exe','compile','--dir',ROOT/'app'/'res','-o',WORK/'res.zip')
    run(BT/'aapt2.exe','link','-I',ANDROID,'--manifest',ROOT/'app'/'AndroidManifest.xml',
        '--java',WORK/'generated','--min-sdk-version','29','--target-sdk-version','35',
        '-A',ROOT/'app'/'assets','-o',WORK/'resources.apk',WORK/'res.zip')
    sources = list((ROOT/'app'/'src').rglob('*.java'))+list((WORK/'generated').rglob('*.java'))
    jars = [ROOT/'vendor'/n/'classes.jar' for n in ('vosk','jna','tesseract')]
    javac_args = ['-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',os.pathsep.join(map(str,[ANDROID]+jars)),'-d',str(WORK/'classes')]+list(map(str,sources))
    # javac @argfile avoids the Windows command line size limit.
    argfile = WORK/'javac.args';argfile.write_text('\n'.join('"'+x.replace('\\','/')+'"' for x in javac_args),encoding='utf-8')
    run(JDK/'bin'/'javac.exe','-J-Dfile.encoding=UTF-8','@work/build/javac.args')
    with zipfile.ZipFile(WORK/'app-classes.jar','w',zipfile.ZIP_DEFLATED) as z:
        for f in (WORK/'classes').rglob('*.class'):z.write(f,f.relative_to(WORK/'classes').as_posix())
    run(JDK/'bin'/'java.exe','-cp',BT/'lib'/'d8.jar','com.android.tools.r8.D8','--release','--min-api','29','--lib',ANDROID,'--output',WORK/'dex',WORK/'app-classes.jar',*jars)
    shutil.copyfile(WORK/'resources.apk',WORK/'unsigned.apk')
    with zipfile.ZipFile(WORK/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
        for f in (WORK/'dex').glob('*.dex'): z.write(f,f.name)
        for abi in ('arm64-v8a','armeabi-v7a','x86_64','x86'):
            for name in ('vosk','jna','tesseract'):
                for lib in (ROOT/'vendor'/name/'jni'/abi).glob('*.so'):z.write(lib,'lib/'+abi+'/'+lib.name)
    run(BT/'zipalign.exe','-f','-p','4',WORK/'unsigned.apk',WORK/'aligned.apk')
    key = ROOT/'signing'/'langyu-release.p12';key.parent.mkdir(exist_ok=True)
    # Project-specific local signing identity retained for compatible future updates.
    if not key.exists():run(JDK/'bin'/'keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass','langyu-local-release','-keypass','langyu-local-release','-alias','langyu','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Langyu Local Release, O=Langyu, C=CN')
    apk = OUT/'Langyu-1.1.0-universal.apk'
    run(JDK/'bin'/'java.exe','-jar',BT/'lib'/'apksigner.jar','sign','--ks',key,'--ks-key-alias','langyu','--ks-pass','pass:langyu-local-release','--key-pass','pass:langyu-local-release','--out',apk,WORK/'aligned.apk')
    run(JDK/'bin'/'java.exe','-jar',BT/'lib'/'apksigner.jar','verify','--verbose','--print-certs',apk)
    run(BT/'zipalign.exe','-c','-v','4',apk)
    digest=hashlib.sha256(apk.read_bytes()).hexdigest();(OUT/(apk.name+'.sha256')).write_text(digest+'  '+apk.name+'\n',encoding='ascii')
    print(json.dumps({'apk':str(apk),'bytes':apk.stat().st_size,'sha256':digest},ensure_ascii=False),flush=True)

if __name__=='__main__':main()
