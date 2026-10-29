"""Narrow Nginx renewal-prefix routing. Never dumps private Nginx configuration."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

DOMAIN='resellerworker.extremeiptv.net'
BEGIN='# BEGIN MANAGED IPTV RENEWAL WORKER\n'
END='# END MANAGED IPTV RENEWAL WORKER\n'


def tokens(text):
    result=[];i=0
    while i<len(text):
        if text[i].isspace():i+=1;continue
        if text[i]=='#':
            j=text.find('\n',i);i=len(text) if j<0 else j+1;continue
        start=i
        if text[i] in '{};':result.append((text[i],i,i+1));i+=1;continue
        if text[i] in "\"'":
            quote=text[i];i+=1;value=''
            while i<len(text) and text[i]!=quote:
                if text[i]=='\\' and i+1<len(text):i+=1
                value+=text[i];i+=1
            if i>=len(text):raise ValueError('Unclosed Nginx quote')
            i+=1;result.append((value,start,i));continue
        while i<len(text) and not text[i].isspace() and text[i] not in '{};#':
            if text[i]=='\\' and i+1<len(text):i+=2
            elif text[i:i+2]=='${':
                j=text.find('}',i+2)
                if j<0:raise ValueError('Unclosed Nginx variable')
                i=j+1
            else:i+=1
        result.append((text[start:i],start,i))
    return result


def targets(text):
    t=tokens(text);stack=[];pairs={}
    for i,(word,a,b) in enumerate(t):
        if word=='{':stack.append(i)
        elif word=='}':
            if not stack:raise ValueError('Unbalanced Nginx braces')
            pairs[stack.pop()]=i
    if stack:raise ValueError('Unbalanced Nginx braces')
    found=[];origins=[]
    for i in range(len(t)-1):
        if t[i][0]!='server' or t[i+1][0]!='{':continue
        start=i+1;end=pairs[start];j=start+1;names=[];tls=False;http=False;redirect=False;locations=[]
        while j<end:
            words=[]
            while j<end and t[j][0] not in [';','{','}']:words.append(t[j][0]);j+=1
            if words and words[0]=='server_name':names+=words[1:]
            if words and words[0]=='listen' and 'ssl' in words and any(v=='443' or v.endswith(':443') for v in words[1:]):tls=True
            if words and words[0]=='listen' and any(v=='80' or v.endswith(':80') for v in words[1:]):http=True
            if words and words[0]=='return' and len(words)>1 and words[1] in ['301','302','307','308']:redirect=True
            if words and words[0]=='location' and '/branded-renewals/' in words:locations.append(True)
            if j<end and t[j][0]=='{':j=pairs[j]+1
            else:j+=1
        service_origin=http and not redirect and any(t[k][0]=='proxy_pass' and k+1<end and t[k+1][0].rstrip('/') in ['http://127.0.0.1:9091','http://localhost:9091'] for k in range(start+1,end))
        if DOMAIN in [n.lower() for n in names] and (tls or service_origin):
            if any(n.lower() not in [DOMAIN,'www.'+DOMAIN] for n in names):raise ValueError('Shared HTTPS server block needs explicit operator review; nothing changed')
            (found if tls else origins).append((t[start][2],t[end][1],bool(locations),tls))
    return found or origins


def plan(text,snippet):
    found=targets(text)
    if len(found)!=1:raise ValueError('Need one unambiguous existing HTTPS server for '+DOMAIN)
    begin,end,has_location,tls=found[0];block=text[begin:end];managed=BEGIN+snippet.strip()+'\n'+END
    if BEGIN in block or END in block:
        if block.count(BEGIN)!=1 or block.count(END)!=1:raise ValueError('Managed proxy markers differ; original retained')
        a=block.index(BEGIN);b=block.index(END)+len(END)
        if block[a:b]==managed:return text
        # Recovery moves ONLY our exact previous managed loopback upstream.
        # Never overwrite a customized route or any auth/trial location.
        previous=managed.replace('http://127.0.0.1:19093;', 'http://127.0.0.1:9093;')
        if previous==managed or block[a:b]!=previous:raise ValueError('Existing managed proxy differs; review before replacing it')
        return text[:begin+a]+managed+text[begin+b:]
    if has_location:raise ValueError('Existing renewal location is not managed; original retained')
    return text[:end]+'\n'+managed+text[end:]


def main():
    if os.geteuid()!=0:raise ValueError('Run on the actual Nginx proxy host as root')
    p=argparse.ArgumentParser();p.add_argument('--site');args=p.parse_args()
    binary=shutil.which('nginx') or next((str(p) for p in [Path('/usr/sbin/nginx'),Path('/www/server/nginx/sbin/nginx')] if p.is_file()),None)
    if not binary:raise ValueError('Nginx is not on this host; apply nginx-location.conf on the actual proxy host')
    inspect=subprocess.run([binary,'-T'],capture_output=True,text=True)
    if inspect.returncode:raise ValueError('Existing Nginx configuration check failed; original retained')
    paths=[Path(args.site).resolve(strict=True)] if args.site else list({Path(v).resolve() for v in re.findall(r'^# configuration file (.+):$',inspect.stdout,re.M)})
    candidates=[]
    for path in paths:
        if not path.is_file():continue
        text=path.read_text()
        if DOMAIN not in text.lower():continue
        choices=targets(text)
        if choices:candidates.append((path,text,any(c[3] for c in choices)))
    if any(c[2] for c in candidates):candidates=[c for c in candidates if c[2]]
    if len(candidates)!=1:raise ValueError('Could not identify exactly one active HTTPS site; use --site with its reviewed config path')
    path,original,tls=candidates[0];snippet=Path(__file__).with_name('nginx-location.conf').read_text();updated=plan(original,snippet)
    if updated==original:print('Renewal proxy already configured; original auth/trial routing unchanged.');return
    folder=Path('/root/iptv-renewal-proxy-backups');folder.mkdir(mode=0o700,exist_ok=True)
    fd,name=tempfile.mkstemp(prefix='site-',suffix='.conf',dir=folder);os.close(fd);backup=Path(name);subprocess.run(['cp','--preserve=all',str(path),str(backup)],check=True)
    def install(data,metadata):
        fd,temp=tempfile.mkstemp(prefix='.renewal-proxy-',dir=path.parent);os.close(fd)
        try:
            subprocess.run(['cp','--preserve=all',str(metadata),temp],check=True);Path(temp).write_text(data);os.replace(temp,path)
        finally:
            if Path(temp).exists():Path(temp).unlink()
    if hashlib.sha256(path.read_bytes()).digest()!=hashlib.sha256(original.encode()).digest():raise ValueError('Nginx site changed concurrently; original retained')
    install(updated,backup)
    try:
        if subprocess.run([binary,'-t'],capture_output=True).returncode:raise ValueError('Nginx validation failed')
        if subprocess.run([binary,'-s','reload'],capture_output=True).returncode:raise ValueError('Nginx reload failed')
    except Exception:
        install(original,backup);subprocess.run([binary,'-t'],capture_output=True);subprocess.run([binary,'-s','reload'],capture_output=True)
        raise ValueError('Proxy change failed; original site restored') from None
    print('Renewal prefix routed to the separate worker. Auth/trial routes unchanged. Backup: '+str(backup))


if __name__=='__main__':
    try:main()
    except Exception as error:print(str(error) if isinstance(error,ValueError) else 'Proxy setup failed; private configuration omitted',file=__import__('sys').stderr);raise SystemExit(1)
