"""Remote host-only supervisor tests. Never run the Android probe on the phone."""
from pathlib import Path
import os,subprocess,tempfile,time,json,signal

root=Path(__file__).resolve().parents[2]
if os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('GITHUB_WORKSPACE')!=str(root) or os.uname().machine!='x86_64':
    raise SystemExit('Recognized remote x86_64 CI required')
with tempfile.TemporaryDirectory(prefix='pocket-probe-tests-') as temporary:
    out=Path(temporary)
    flags=['-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-asynchronous-unwind-tables','-Wall','-Wextra','-Werror','-static','-nostdlib','-nostartfiles','-no-pie','-Wl,--build-id=none','-Wl,-z,noexecstack']
    subprocess.run(['cc',*flags,'-I'+str(root/'tawcroot/include'),str(root/'sigsys-probe/native/runner.c'),str(root/'sigsys-probe/native/start_x86_64.S'),str(root/'sigsys-probe/native/raw_x86_64.S'),'-o',str(out/'runner')],check=True,timeout=20)
    assert not subprocess.check_output(['nm','-u',str(out/'runner')]).strip()
    source=r'''#define _GNU_SOURCE
#include <sys/resource.h>
#include <sched.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
int main(int argc,char **argv) {
 if(argc!=5 || strcmp(argv[1],"-r") || strcmp(argv[3],"--") || strcmp(argv[4],"/probe") || getenv("LEAK_MARKER")) return 1;
 struct rlimit value;
 if(getrlimit(RLIMIT_AS,&value) || value.rlim_cur>128UL*1024*1024) return 2;
 if(getrlimit(RLIMIT_CPU,&value) || value.rlim_cur>2) return 3;
 if(getrlimit(RLIMIT_FSIZE,&value) || value.rlim_cur>1048576) return 4;
 if(getrlimit(RLIMIT_CORE,&value) || value.rlim_cur!=0) return 5;
 cpu_set_t cpus; CPU_ZERO(&cpus); if(sched_getaffinity(0,sizeof cpus,&cpus) || CPU_COUNT(&cpus)!=1) return 6;
 int nice=getpriority(PRIO_PROCESS,0); if(nice<15) return 7;
 char path[4096];snprintf(path,sizeof path,"%s/case",argv[2]);FILE *file=fopen(path,"r");if(!file)return 8;
 int mode=fgetc(file);fclose(file);
 if(mode=='H') { pid_t pid=fork();if(pid<0)return 9; for(;;)pause(); }
 if(mode=='O') { pid_t pid=fork();if(pid<0)return 9;if(pid==0)for(;;)pause(); }
 puts("probe-ok");return 0;
}'''
    (out/'fake.c').write_text(source)
    subprocess.run(['cc','-O2','-Wall','-Wextra','-Werror',str(out/'fake.c'),'-o',str(out/'libpocket_baseline.so')],check=True,timeout=20)
    cases=[]
    for mode,action,expected in [('S',None,0),('O',None,0),('H','cancel',90),('H','deadline',90)]:
        directory=out/(mode+(action or ''));folder=directory/'rootfs';folder.mkdir(parents=True);(folder/'case').write_text(mode)
        start=time.monotonic()
        process=subprocess.Popen([str(out/'runner'),str(out/'libpocket_baseline.so'),str(folder)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env={**os.environ,'LEAK_MARKER':'inert-local-control'})
        if action=='cancel':time.sleep(.3);process.terminate()
        stdout,stderr=process.communicate(timeout=15)
        assert process.returncode==expected and stdout==b'probe-ok\ncleanup=ok\n' if expected==0 else process.returncode==expected and stdout==b'cleanup=ok\n'
        assert not stderr
        elapsed=round(time.monotonic()-start,3)
        cases.append({'case':mode+':'+str(action),'exit':process.returncode,'elapsed_seconds':elapsed,'owned_group_cleanup_verified':True})
    print(json.dumps({'host_supervisor_checks_passed':True,'cases':cases,'scope':'x86_64 fake fixed child only; Android runtime not executed'},indent=2))
