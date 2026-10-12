"""Read-only companion APK policy/provenance check; never execute its code."""
from pathlib import Path
import hashlib,json,os,struct,sys,zipfile

BASELINE='343db96e9ac6eab32224a58e440bfea92524d6dac7c3c201897d70b982b0d112'
TRACE='5a65fb93d44677310c48f3130a1c3fb002e0afa0b7a5863435e473c63f08012f'
PACKAGE='me.phie.tawc.sigsysprobe'

def u32(data,offset): return struct.unpack_from('<I',data,offset)[0]
def xml_elements(blob):
    strings=[];elements=[];stack=[];offset=8
    while offset<len(blob):
        kind,header,size=struct.unpack_from('<HHI',blob,offset)
        if size<header or offset+size>len(blob): raise ValueError('binary XML bounds')
        data=blob[offset:offset+size]
        if kind==1:
            count=u32(data,8);flags=u32(data,16);base=u32(data,20)
            def length8(pos):
                value=data[pos]
                return (((value&127)<<8)|data[pos+1],pos+2) if value&128 else (value,pos+1)
            for index in range(count):
                pos=base+u32(data,header+4*index)
                if flags&0x100:
                    _,pos=length8(pos);length,pos=length8(pos);strings.append(data[pos:pos+length].decode('utf-8'))
                else:
                    length=struct.unpack_from('<H',data,pos)[0];pos+=2
                    if length&0x8000:
                        length=((length&0x7fff)<<16)|struct.unpack_from('<H',data,pos)[0];pos+=2
                    strings.append(data[pos:pos+2*length].decode('utf-16le'))
        elif kind==0x0102:
            name=strings[u32(data,header+4)];start,width,count=struct.unpack_from('<HHH',data,header+8)
            attrs={}
            for index in range(count):
                pos=header+start+index*width;key=strings[u32(data,pos+4)];raw=u32(data,pos+8);typ=data[pos+15];value=u32(data,pos+16)
                attrs[key]=strings[raw] if raw!=0xffffffff else strings[value] if typ==3 else bool(value) if typ==18 else value
            elements.append({'name':name,'parent':stack[-1] if stack else None,'attrs':attrs});stack.append(name)
        elif kind==0x0103:
            if not stack or stack.pop()!=strings[u32(data,header+4)]: raise ValueError('binary XML nesting')
        offset+=size
    if stack: raise ValueError('binary XML nesting')
    return elements

def elf(blob):
    assert blob[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<HH',blob,16)==(2,183)
    offset=struct.unpack_from('<Q',blob,32)[0];width,count=struct.unpack_from('<HH',blob,54)
    assert width>=56 and offset+count*width<=len(blob)
    assert all(u32(blob,offset+i*width)!=3 for i in range(count)), 'Unexpected ELF interpreter'
    return {'class':'ELF64','machine':'AArch64','type':'ET_EXEC','interpreter':False}

def inspect(path,source):
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist();assert len(names)==len(set(names))
        elements=xml_elements(archive.read('AndroidManifest.xml'))
        allowed={'manifest','uses-sdk','queries','package','application','activity','intent-filter','action','category'}
        assert all(e['name'] in allowed for e in elements), 'Unexpected permission/component/surface'
        def tags(name):return [e for e in elements if e['name']==name]
        manifest=tags('manifest')[0]['attrs'];app=tags('application')[0]['attrs']
        assert manifest['package']==PACKAGE and manifest['versionCode']==2
        assert not app.get('debuggable',False) and not app.get('testOnly',False)
        assert app['allowBackup'] is False and app['fullBackupContent'] is False
        assert app['extractNativeLibs'] is True and app['usesCleartextTraffic'] is False
        assert tags('uses-sdk')[0]['attrs']['minSdkVersion']==29 and tags('uses-sdk')[0]['attrs']['targetSdkVersion']==36
        assert len(tags('activity'))==1 and tags('activity')[0]['attrs']['exported'] is True
        assert tags('activity')[0]['attrs']['name'] in ('.ProbeActivity',PACKAGE+'.ProbeActivity')
        assert [e['attrs']['name'] for e in tags('action')]==['android.intent.action.MAIN']
        assert [e['attrs']['name'] for e in tags('category')]==['android.intent.category.LAUNCHER']
        assert [(e['parent'],e['attrs']['name']) for e in tags('package')]==[('queries','me.phie.tawc')]
        provenance=json.loads(archive.read('assets/provenance.json'))
        assert provenance['source_commit']==source and provenance['runtime_source_commit']=='5bbafd8490d4687d5f845bdab4212c99a5150cb4'
        assert provenance['baseline_sha256']==BASELINE and provenance['trace_sha256']==TRACE
        files={'lib/arm64-v8a/libpocket_baseline.so':'baseline_sha256','lib/arm64-v8a/libpocket_trace.so':'trace_sha256','lib/arm64-v8a/libpocket_runner.so':'runner_sha256','assets/probe-fixture':'fixture_sha256'}
        assert {n for n in names if n.startswith('lib/')}=={n for n in files if n.startswith('lib/')}
        hashes={}
        for name,field in files.items():
            data=archive.read(name);assert hashlib.sha256(data).hexdigest()==provenance[field]
            hashes[name]={'sha256':provenance[field],'bytes':len(data),'elf':elf(data)}
        assert b'[te] ' not in archive.read('lib/arm64-v8a/libpocket_baseline.so')
        assert b'[te] ' in archive.read('lib/arm64-v8a/libpocket_trace.so')
        assert archive.read('assets/LICENSE')==Path(__file__).resolve().parents[2].joinpath('LICENSE').read_bytes()
        return {'source_commit':source,'package':PACKAGE,'version_code':2,'debuggable':False,'permissions':[],
                'exported_components':['fixed launcher Activity only; ignores intent data/extras'],
                'package_visibility':['me.phie.tawc'],'native_and_fixture':hashes,
                'runtime_source_commit':provenance['runtime_source_commit'],'apk_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                'certificate_sha256':os.environ.get('DEBUG_CERT_SHA256'),'signature_verification':'CI apksigner verification and exact certificate check before inspection',
                'apk_bytes':path.stat().st_size,'installed_or_executed':False,
                'phone_verification':'Pending separate installation/activation approval. Host tests and APK inspection do not establish Android execution.',
                'equivalence_limits':'Companion UID/permissions/storage differ from TAWC; fixed marker fixture is not Firefox or Omarchy validation.',
                'installation_route':'Separate APK through an already-authorized Android installer after approval; current Controller package allowlist does not include this package.'}

if __name__=='__main__':
    path=Path(sys.argv[1]);report=inspect(path,os.environ['GITHUB_SHA'])
    path.with_name('manifest.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Companion APK manifest, exact runtime bytes, ELF and surface checks passed')
