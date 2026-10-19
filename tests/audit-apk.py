from pathlib import Path
import zipfile,json,hashlib,io,subprocess,sys,xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
ROOT=Path(__file__).resolve().parents[1]
version=ET.parse(ROOT/'app/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
apk=ROOT/'outputs'/('Langyu-'+version+'-universal.apk')
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    boards=json.loads(z.read('assets/boards.json'))
    assert len(boards)==30 and len(set(b['id'] for b in boards))==30
    assert z.read('assets/boards.json')==(ROOT/'app/assets/boards.json').read_bytes()
    for b in boards:assert 5<=len(b['roles'])<=20 and len(b['roles'])==b['players']
    with zipfile.ZipFile(io.BytesIO(z.read('assets/asr-model.zip'))) as model:assert model.testzip() is None and any(n.endswith('am/final.mdl') for n in model.namelist())
    for abi in ('arm64-v8a','armeabi-v7a','x86_64','x86'):
        for lib in ('libvosk.so','libjnidispatch.so'):
            assert z.read('lib/'+abi+'/'+lib)==(ROOT/'vendor'/('vosk' if lib=='libvosk.so' else 'jna')/'jni'/abi/lib).read_bytes()
        for lib in (ROOT/'vendor/tesseract/jni'/abi).glob('*.so'):
            assert z.read('lib/'+abi+'/'+lib.name)==lib.read_bytes()
    for model in ('chi_sim','eng'):
        assert z.read('assets/ocr/'+model+'.traineddata')==(ROOT/'app/assets/ocr'/(model+'.traineddata')).read_bytes()
    assert not any('instrumentation' in n.lower() or n.endswith('chinese.wav') for n in z.namelist()),'test artifact leaked into release'
    assert 'assets/LICENSE-APACHE-2.0.txt' in z.namelist()
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest in (apk.parent/(apk.name+'.sha256')).read_text()
result={'apk':str(apk),'version':version,'size_bytes':apk.stat().st_size,'sha256':digest,'boards':len(boards),'embedded_chinese_model':True,'embedded_offline_ocr':True,'abis':['arm64-v8a','armeabi-v7a','x86_64','x86'],'zip_integrity':'PASS','test_assets_excluded':True,'byte_identical_dependencies':True}
(ROOT/'work/verification/apk-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: final APK integrity, 30 boards, model integrity, four ABIs, exact dependency bytes and SHA-256')
