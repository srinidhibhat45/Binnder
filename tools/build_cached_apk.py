"""Offline SDK build fallback. Uses cached Maven metadata and official Android tools.
Normal builds should use ./gradlew assembleRelease. This script never accesses the network.
"""
from pathlib import Path
import json, os, re, shutil, subprocess, sys, zipfile, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1'
OUT=ROOT/'app/build/manual-apk'
SDK=Path(os.environ.get('ANDROID_HOME','/opt/homebrew/share/android-commandlinetools'))
BT=SDK/'build-tools/36.0.0'
ANDROID=SDK/'platforms/android-36/android.jar'
OUT.mkdir(parents=True,exist_ok=True)
VERSION_CODE=int(os.environ.get('VERSION_CODE','3'))
VERSION_NAME=os.environ.get('VERSION_NAME','0.1.2')
NS={'m':'http://maven.apache.org/POM/4.0.0'}

def run(args,**kwargs):
    print('Running:',Path(str(args[0])).name,flush=True)
    subprocess.run([str(x) for x in args],check=True,**kwargs)

def vkey(v):
    nums=re.findall(r'\d+',v.split('-')[0])
    return tuple((list(map(int,nums))+[0]*5)[:5])+(1 if '-' not in v else 0,)

def cached(g,a,v,ext):
    return next((CACHE/g/a/v).glob('*/*.'+ext),None)

def pom_info(g,a,v):
    p=cached(g,a,v,'pom')
    if not p: return {},[]
    tree=ET.parse(p).getroot()
    properties={'project.version':v,'pom.version':v,'project.groupId':g}
    parent=tree.find('m:parent',NS)
    if parent is not None:
        pg=parent.findtext('m:groupId',namespaces=NS);pa=parent.findtext('m:artifactId',namespaces=NS);pv=parent.findtext('m:version',namespaces=NS)
        properties.update(pom_info(pg,pa,pv)[0])
    props=tree.find('m:properties',NS)
    if props is not None:
        properties.update({x.tag.split('}')[-1]:x.text for x in props})
    def subst(value):
        for _ in range(5):
            value=re.sub(r'\$\{([^}]+)\}',lambda m:properties.get(m[1],m[0]) or '',value or '')
        return value
    deps=[]
    for d in tree.findall('m:dependencies/m:dependency',NS):
        if d.findtext('m:scope',default='compile',namespaces=NS) not in ('compile','runtime'):continue
        if d.findtext('m:optional',default='false',namespaces=NS)=='true':continue
        deps.append({'group':d.findtext('m:groupId',namespaces=NS),'module':d.findtext('m:artifactId',namespaces=NS),'version':{'requires':subst(d.findtext('m:version',namespaces=NS))}})
    return properties,deps

def metadata(g,a,v):
    p=cached(g,a,v,'module')
    if not p:return {'dependencies':pom_info(g,a,v)[1]}
    data=json.loads(p.read_text())
    variants=[x for x in data['variants'] if x.get('attributes',{}).get('org.gradle.usage')=='java-runtime' and x.get('attributes',{}).get('org.gradle.category')!='documentation']
    def score(x):
        at=x.get('attributes',{})
        return (100 if 'android' in x['name'].lower() or at.get('org.gradle.jvm.environment')=='android' else 0)+(20 if at.get('org.jetbrains.kotlin.platform.type') in ('jvm','androidJvm') else 0)-(100 if 'stubs' in x['name'].lower() else 0)
    if not variants:raise RuntimeError(f'No JVM runtime variant: {g}:{a}:{v}')
    return max(variants,key=score)

constraints={}
def require(g,a,v):
    if a in ('kotlin-stdlib-common','kotlin-test-common','kotlin-test-annotations-common'):return False
    v=(v or '').strip('[]()')
    if not v or '${' in v:
        if (g,a) in constraints:return False
        choices=list((CACHE/g/a).iterdir()) if (CACHE/g/a).exists() else []
        if not choices:raise RuntimeError(f'Missing unversioned dependency: {g}:{a}')
        v=max(choices,key=lambda p:vkey(p.name)).name
    if ',' in v:v=v.split(',')[0]
    prev=constraints.get((g,a))
    if prev is None or vkey(v)>vkey(prev):constraints[g,a]=v;return True
    return False

bom=ET.parse(cached('androidx.compose','compose-bom','2025.10.00','pom')).getroot()
for d in bom.findall('m:dependencyManagement/m:dependencies/m:dependency',NS):
    require(d.findtext('m:groupId',namespaces=NS),d.findtext('m:artifactId',namespaces=NS),d.findtext('m:version',namespaces=NS))
roots=[('androidx.activity','activity-compose','1.11.0'),('androidx.compose.material3','material3',None),('androidx.compose.material','material-icons-extended',None),('androidx.core','core-ktx','1.17.0'),('androidx.lifecycle','lifecycle-viewmodel-compose','2.9.4'),('androidx.lifecycle','lifecycle-runtime-compose','2.9.4'),('androidx.work','work-runtime-ktx','2.10.5'),('org.jetbrains.kotlinx','kotlinx-coroutines-android','1.10.2'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21')]
roots += [('androidx.core','core','1.17.0'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),('androidx.room','room-runtime','2.6.1'),('androidx.room','room-ktx','2.6.1'),('androidx.sqlite','sqlite-framework','2.4.0'),('androidx.concurrent','concurrent-futures','1.1.0'),('androidx.startup','startup-runtime','1.1.1'),('com.google.guava','guava','32.0.1-android')]
roots += [('androidx.sqlite','sqlite','2.4.0'),('androidx.room','room-common','2.6.1'),('androidx.concurrent','concurrent-futures-ktx','1.1.0'),('androidx.tracing','tracing','1.2.0'),('androidx.interpolator','interpolator','1.0.0'),('androidx.lifecycle','lifecycle-service','2.9.4'),('androidx.lifecycle','lifecycle-process','2.9.4'),('androidx.lifecycle','lifecycle-livedata','2.9.4'),('androidx.versionedparcelable','versionedparcelable','1.1.1')]
for g,a,v in roots:require(g,a,v)

selected={}
for iteration in range(50):
    changed=False;queue=[(g,a) for g,a,v in roots];seen=set();current={}
    while queue:
        key=queue.pop(0)
        if key in seen:continue
        seen.add(key);g,a=key;v=constraints[key]
        alias=next((name for name in (a+'-android',a+'-jvm') if (CACHE/g/name).exists()),None)
        if not (CACHE/g/a/v).exists() and alias:
            info={'available-at':{'group':g,'module':alias,'version':v}}
            current[key]=(v,info)
            changed=require(g,alias,v) or changed
            queue.append((g,alias))
            continue
        if not (CACHE/g/a/v).exists():
            choices=[p.name for p in (CACHE/g/a).iterdir() if vkey(p.name)>=vkey(v)] if (CACHE/g/a).exists() else []
            if not choices:raise RuntimeError(f'Uncached dependency {g}:{a}:{v}')
            replacement=min(choices,key=vkey)
            changed=require(g,a,replacement) or changed;v=constraints[key]
        info=metadata(g,a,v);current[key]=(v,info)
        for d in info.get('dependencyConstraints',[]):
            version=d.get('version',{});changed=require(d['group'],d['module'],version.get('requires') or version.get('strictly') or version.get('prefers')) or changed
        redirect=info.get('available-at')
        deps=([{'group':redirect['group'],'module':redirect['module'],'version':{'requires':redirect['version']}}] if redirect else info.get('dependencies',[]))
        for d in deps:
            if d.get('attributes',{}).get('org.gradle.category')=='platform':continue
            if d['module'] in ('kotlin-stdlib-common','kotlin-test-common','kotlin-test-annotations-common'):continue
            version=d.get('version',{})
            changed=require(d['group'],d['module'],version.get('requires') or version.get('strictly') or version.get('prefers')) or changed
            queue.append((d['group'],d['module']))
    selected=current
    if not changed:break
else:raise RuntimeError('Dependency resolution did not converge')

archives=[]
for (g,a),(v,info) in selected.items():
    if info.get('available-at'):continue
    files=info.get('files',[])
    if files:
        for f in files:
            if f['name'].endswith(('.aar','.jar')):
                matches=list((CACHE/g/a/v).glob('*/*'))
                p=next((p for p in matches if p.name in (f['name'],f.get('url'))),None)
                if p is None:
                    ext=f['name'].split('.')[-1];p=cached(g,a,v,ext)
                if p is None:raise RuntimeError(f'Uncached archive {g}:{a}:{v}')
                archives.append((g,a,v,p))
    else:
        p=cached(g,a,v,'aar') or cached(g,a,v,'jar')
        if p:archives.append((g,a,v,p))
        elif pom_info(g,a,v)[1]:pass
        else:raise RuntimeError(f'Missing archive for {g}:{a}:{v}')
(OUT/'dependencies.json').write_text(json.dumps([{'group':g,'artifact':a,'version':v,'file':str(p)} for g,a,v,p in archives],indent=2))
print(f'Resolved {len(archives)} runtime archives',flush=True)
if '--resolve-only' in sys.argv:sys.exit(0)
for directory in ('generated','r-classes','app-classes','dex'):
    if (OUT/directory).exists():shutil.rmtree(OUT/directory)

jars=[];aars=[];packages=[]
for g,a,v,p in archives:
    if p.suffix=='.jar':jars.append(p);continue
    dest=OUT/'libraries'/f'{g}.{a}-{v}'
    dest.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(p) as z:z.extractall(dest)
    aars.append(dest)
    if (dest/'classes.jar').exists():jars.append(dest/'classes.jar')
    jars.extend((dest/'libs').glob('*.jar'))
    if (dest/'AndroidManifest.xml').exists():
        package=ET.parse(dest/'AndroidManifest.xml').getroot().get('package')
        if package:packages.append(package)

jars=list(dict.fromkeys(jars))
(OUT/'runtime-jars.json').write_text(json.dumps([str(p) for p in jars]))

# Merge each library manifest with the official Android manifest merger.
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml')
ANDROID_NS='http://schemas.android.com/apk/res/android'
ET.register_namespace('android',ANDROID_NS)
mroot=manifest.getroot();mroot.set('package','com.binnder.app');mroot.set('{'+ANDROID_NS+'}versionCode',str(VERSION_CODE));mroot.set('{'+ANDROID_NS+'}versionName',VERSION_NAME)
source=OUT/'main-manifest.xml';manifest.write(source,encoding='utf-8',xml_declaration=True)
source.write_text(source.read_text().replace('${applicationId}','com.binnder.app'))
tool_jars=[]
for group in CACHE.iterdir():
    if group.name.startswith(('com.android.','com.google.','org.jetbrains.kotlin','org.jetbrains.intellij','net.sf.kxml')) or group.name in ('org.jetbrains','commons-io'):
        for artifact in group.iterdir():
            for version in sorted(artifact.iterdir(),key=lambda p:vkey(p.name),reverse=True):
                found=list(version.glob('*/*.jar'))
                if found:
                    tool_jars.append(str(found[0]));break
toolcp=':'.join(tool_jars)
args=['java','-cp',toolcp,'com.android.manifmerger.Merger','--main',source,'--out',OUT/'AndroidManifest.xml','--property','MIN_SDK_VERSION=30','--property','TARGET_SDK_VERSION=36','--placeholder','applicationId=com.binnder.app']
libs=[p/'AndroidManifest.xml' for p in aars if (p/'AndroidManifest.xml').exists()]
if libs:args+=['--libs',':'.join(map(str,libs))]
run(args)

flats=[]
for index,d in enumerate(aars+[ROOT/'app/src/main']):
    if not (d/'res').exists():continue
    output=OUT/f'resources-{index}.zip'
    run([BT/'aapt2','compile','--dir',d/'res','-o',output]);flats.append(output)
generated=OUT/'generated';generated.mkdir(exist_ok=True)
args=[BT/'aapt2','link','-o',OUT/'resources.apk','--manifest',OUT/'AndroidManifest.xml','-I',ANDROID,'--java',generated,'--auto-add-overlay','--min-sdk-version','30','--target-sdk-version','36','--extra-packages',':'.join(sorted(set(packages))),'--no-static-lib-packages']
for f in flats:args+=['-R',f]
run(args)

rclasses=OUT/'r-classes';rclasses.mkdir(exist_ok=True)
sources=list(generated.rglob('*.java'))
run(['javac','-source','17','-target','17','-classpath',ANDROID,'-d',rclasses]+sources)
run(['jar','cf',OUT/'R.jar','-C',rclasses,'.'])

compiler=cached('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21','jar')
plugin=cached('org.jetbrains.kotlin','kotlin-compose-compiler-plugin-embeddable','2.2.21','jar')
compiler_support=[]
compiler_support.append(str(cached('org.jetbrains','annotations','23.0.0','jar')))
for artifact in ('kotlin-reflect','kotlin-script-runtime','kotlin-daemon-embeddable'):
    choices=sorted((CACHE/'org.jetbrains.kotlin'/artifact).iterdir(),key=lambda p:vkey(p.name),reverse=True)
    found=next((p for v in choices for p in v.glob('*/*.jar')),None)
    if found:compiler_support.append(str(found))
compiler_cp=':'.join([str(compiler)]+compiler_support+[str(p) for p in jars])
buildconfig=generated/'BuildConfig.kt';buildconfig.write_text('package com.binnder.app\nobject BuildConfig { const val VERSION_NAME='+json.dumps(VERSION_NAME)+'; const val UPDATE_REPOSITORY='+json.dumps(os.environ.get('GITHUB_REPOSITORY',''))+' }\n')
cp=':'.join(map(str,jars+[ANDROID,OUT/'R.jar']))
classes=OUT/'app-classes';classes.mkdir(exist_ok=True)
run(['java','-Xmx3g','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+str(plugin),'-classpath',cp,'-d',classes]+list((ROOT/'app/src/main/java').rglob('*.kt'))+[buildconfig])
run(['jar','cf',OUT/'app.jar','-C',classes,'.'])
dex=OUT/'dex';dex.mkdir(exist_ok=True)
run([BT/'d8','--release','--min-api','30','--lib',ANDROID,'--output',dex,OUT/'app.jar',OUT/'R.jar']+jars)
unsigned=OUT/'unsigned.apk'
with zipfile.ZipFile(OUT/'resources.apk') as z,zipfile.ZipFile(unsigned,'w',zipfile.ZIP_DEFLATED) as apk:
    for info in z.infolist():apk.writestr(info,z.read(info.filename))
    for f in dex.glob('*.dex'):apk.write(f,f.name)
    services={}
    for jar in jars:
        with zipfile.ZipFile(jar) as library:
            for name in library.namelist():
                if name.endswith('/') or name.endswith('.class'):continue
                if name.startswith('META-INF/services/'):
                    services.setdefault(name,set()).update(library.read(name).decode().splitlines());continue
                if name.startswith('META-INF/') and (name.endswith(('.SF','.RSA','.DSA')) or name=='META-INF/MANIFEST.MF'):continue
                if name not in apk.namelist():apk.writestr(name,library.read(name))
    for name,lines in services.items():apk.writestr(name,'\n'.join(sorted(lines))+'\n')
    for d in aars:
        for f in (d/'jni').rglob('*.so') if (d/'jni').exists() else []:
            name='lib/'+str(f.relative_to(d/'jni'))
            if name not in apk.namelist():apk.write(f,name,compress_type=zipfile.ZIP_STORED)
        for f in (d/'assets').rglob('*') if (d/'assets').exists() else []:
            if f.is_file():
                name='assets/'+str(f.relative_to(d/'assets'))
                if name not in apk.namelist():apk.write(f,name)
run([BT/'zipalign','-P','16','-f','4',unsigned,OUT/'aligned.apk'])
print('Packaged unsigned APK:',OUT/'aligned.apk')
