"""Create and publish Binnder to a personal public GitHub repository using gh.
Run --dry-run to audit the upload list without contacting GitHub or changing anything.
Release signing material is sent only to GitHub Actions encrypted secrets, never Git.
"""
from pathlib import Path
import argparse, base64, json, re, shutil, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
ROOT_FILES=('gradlew','gradlew.bat','build.gradle.kts','settings.gradle.kts','gradle.properties',
    '.gitignore','PRODUCT.md','DESIGN.md','README.md','app/build.gradle.kts','.impeccable/config.json')
SOURCE_DIRS=('app/src','gradle/wrapper','tools','docs','.github/workflows')

def source_files(root):
    root=Path(root).resolve()
    paths=[root/name for name in ROOT_FILES if (root/name).is_file()]
    for name in SOURCE_DIRS:
        paths += [p for p in (root/name).rglob('*') if p.is_file()]
    result=[]
    for path in sorted(set(paths)):
        relative=path.relative_to(root)
        if set(relative.parts) & {'.git','.signing','build','dist','__pycache__'}:continue
        if path.suffix.lower() in ('.jks','.keystore','.p12','.pfx') or path.name=='credentials.json':
            raise ValueError('Refusing to publish signing credentials: '+str(relative))
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError('Refusing to publish a symbolic link: '+str(relative))
        result.append(relative)
    return result

def command(args, cwd=None, data=None, capture=False):
    result=subprocess.run(args,cwd=cwd,input=data,stdout=subprocess.PIPE if capture else None,check=True)
    return result.stdout.decode() if capture else ''

def ensure_repository(repository, create=False):
    result=subprocess.run(['gh','api','repos/'+repository],capture_output=True,text=True)
    if result.returncode:
        if not create or '(HTTP 404)' not in result.stderr:
            raise SystemExit(result.stderr.strip() or 'GitHub repository lookup failed.')
        command(['gh','repo','create',repository,'--public','--description',
                 'Native Android swipe-to-clean storage app with signed GitHub updates'])
    return json.loads(command(['gh','repo','view',repository,'--json',
        'nameWithOwner,isPrivate,viewerPermission,defaultBranchRef'],capture=True))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository',help='Public repository, owner/name; defaults to your authenticated account/Binnder')
    parser.add_argument('--create',action='store_true',help='Create a public repository if it does not exist')
    parser.add_argument('--dry-run',action='store_true')
    args=parser.parse_args()
    paths=source_files(ROOT)
    if args.dry_run:
        print('\n'.join(str(p) for p in paths))
        print(f'{len(paths)} source files; signing keys, credentials, builds, and APKs excluded. No changes made.')
        return
    if args.repository and not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',args.repository):
        parser.error('--repository must be owner/name')
    key=ROOT/'.signing/binnder-release.jks'
    settings=ROOT/'.signing/credentials.json'
    if not key.is_file() or not settings.is_file():
        raise SystemExit('The original APK signing key is required. Do not generate a replacement key for existing installs.')
    credentials=json.loads(settings.read_text())
    user=json.loads(command(['gh','api','user'],capture=True))
    repository=args.repository or user['login']+'/Binnder'
    if repository.split('/')[0].lower()!=user['login'].lower():
        raise SystemExit('This helper only publishes to the authenticated owner’s personal repository.')
    repo=ensure_repository(repository,create=args.create)
    canonical=repo['nameWithOwner']
    if repo['isPrivate']:
        raise SystemExit('The current token-free updater needs public GitHub Releases. No repository visibility was changed.')
    if canonical.split('/')[0].lower()!=user['login'].lower() or repo['viewerPermission']!='ADMIN':
        raise SystemExit('This helper only configures an authenticated owner’s personal repository with admin permission.')
    git=['git','-c','credential.helper=','-c','credential.helper=!gh auth git-credential']
    with tempfile.TemporaryDirectory(prefix='binnder-publish-') as temp:
        checkout=Path(temp)
        command(git+['init','-b','main'],cwd=checkout)
        command(git+['remote','add','origin','https://github.com/'+canonical+'.git'],cwd=checkout)
        branches=command(git+['ls-remote','--heads','origin','main'],cwd=checkout,capture=True)
        default=(repo.get('defaultBranchRef') or {}).get('name')
        base='main' if branches.strip() else default
        if base:
            command(git+['fetch','--depth=1','origin',base],cwd=checkout)
            command(git+['checkout','-B','main','FETCH_HEAD'],cwd=checkout)
        for relative in paths:
            destination=checkout/relative
            destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(ROOT/relative,destination)
        command(git+['config','user.name',user.get('name') or user['login']],cwd=checkout)
        command(git+['config','user.email',str(user['id'])+'+'+user['login']+'@users.noreply.github.com'],cwd=checkout)
        command(git+['add','--all'],cwd=checkout)
        if command(git+['diff','--cached','--name-only'],cwd=checkout,capture=True).strip():
            command(git+['commit','-m','Enable signed GitHub OTA releases for Binnder'],cwd=checkout)
        # Verify push access before changing any release secrets. Never force-push.
        command(git+['push','--dry-run','origin','main'],cwd=checkout)
        secrets={
            'SIGNING_KEYSTORE_BASE64':base64.b64encode(key.read_bytes()),
            'SIGNING_STORE_PASSWORD':credentials['storePassword'].encode(),
            'SIGNING_KEY_ALIAS':credentials['alias'].encode(),
            'SIGNING_KEY_PASSWORD':credentials['storePassword'].encode(),
        }
        for name,value in secrets.items():
            command(['gh','secret','set',name,'--repo',canonical],data=value)
            print('Configured encrypted Actions secret: '+name)
        command(git+['push','origin','main'],cwd=checkout)
        head=command(git+['rev-parse','HEAD'],cwd=checkout,capture=True).strip()
        remote=command(git+['ls-remote','--heads','origin','main'],cwd=checkout,capture=True)
        if not remote.startswith(head+'\t'):raise RuntimeError('Remote main did not match the pushed commit.')
        print('Verified main push: '+head)
        print('Release build: https://github.com/'+canonical+'/actions/workflows/release.yml')
        print('On the currently installed 0.1.1 app, set Settings → GitHub updates → '+canonical+' once.')
        print('Subsequent GitHub-built APKs include this repository automatically. Verify the workflow succeeds before claiming a release is live.')

if __name__=='__main__':
    try:main()
    except subprocess.CalledProcessError as error:
        raise SystemExit('Publishing stopped because a required command failed (exit '+str(error.returncode)+'). No successful push was verified.')
