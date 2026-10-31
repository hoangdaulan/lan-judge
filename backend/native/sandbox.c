/* Linux syscall helper for the Java worker. The JVM never runs student code.
 * This executable is NOT setuid: only the root worker container can use it.
 * The supervisor retains privileges; the child loses every capability, group,
 * and network interface before exec. Setup errors travel over a CLOEXEC pipe.
 */
#define _GNU_SOURCE
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sched.h>
#include <unistd.h>
#include <grp.h>
#include <signal.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <time.h>

static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping=1; }
static long long millis(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000; }
static unsigned long long number(const char *s) { char *end;errno=0;unsigned long long n=strtoull(s,&end,10);if(errno||!*s||*end){fprintf(stderr,"invalid numeric argument\n");exit(125);}return n; }
static void fail(int fd,const char *s) { int saved=errno;dprintf(fd,"%s: %s",s,strerror(saved));_exit(126); }
static void limit(int fd,int resource,rlim_t soft,rlim_t hard) { struct rlimit r={soft,hard};if(setrlimit(resource,&r))fail(fd,"setrlimit"); }
static int drop(uid_t uid) { return setgroups(0,NULL)||setgid(uid)||setuid(uid); }
static void reap_uid(uid_t uid) { pid_t p=fork();if(p==0){if(drop(uid))_exit(126);kill(-1,SIGKILL);_exit(0);}if(p>0)while(waitpid(p,NULL,0)<0&&errno==EINTR){} }

int main(int argc,char **argv) {
    if(argc==3&&!strcmp(argv[1],"reap")){uid_t uid=(uid_t)number(argv[2]);if(uid<20000||uid>20063||geteuid()!=0)return 125;reap_uid(uid);return 0;}
    if(argc<11||geteuid()!=0){fprintf(stderr,"sandbox requires root and limit arguments\n");return 125;}
    uid_t uid=(uid_t)number(argv[1]);long long wall=(long long)number(argv[2]);
    rlim_t cpu=number(argv[3]),as=number(argv[4]),stack=number(argv[5]),fsize=number(argv[6]),nproc=number(argv[7]),nofile=number(argv[8]);
    if(uid<20000||uid>20063||wall<=0)return 125;
    int status_pipe[2];if(pipe2(status_pipe,O_CLOEXEC))return 125;
    /* Open result before exec; the runner cannot replace it with a symlink. */
    int result=open(argv[9],O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600);if(result<0)return 125;
    struct sigaction action={0};action.sa_handler=stop;sigaction(SIGTERM,&action,NULL);sigaction(SIGINT,&action,NULL);
    if(prctl(PR_SET_CHILD_SUBREAPER,1))return 125;
    long long start=millis();pid_t child=fork();if(child<0)return 125;
    if(child==0){close(status_pipe[0]);close(result);setpgid(0,0);
        if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()==1)fail(status_pipe[1],"parent died");
        if(unshare(CLONE_NEWNET))fail(status_pipe[1],"network namespace");
        if(drop(uid))fail(status_pipe[1],"runner identity");
        if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))fail(status_pipe[1],"no_new_privs");
        /* setuid clears parent-death signal; install it again after dropping. */
        if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()==1)fail(status_pipe[1],"parent died");
        limit(status_pipe[1],RLIMIT_CORE,0,0);
        if(cpu)limit(status_pipe[1],RLIMIT_CPU,cpu,cpu+1);
        if(as)limit(status_pipe[1],RLIMIT_AS,as,as);
        if(stack)limit(status_pipe[1],RLIMIT_STACK,stack,stack);
        if(fsize)limit(status_pipe[1],RLIMIT_FSIZE,fsize,fsize);
        if(nproc)limit(status_pipe[1],RLIMIT_NPROC,nproc,nproc);
        if(nofile)limit(status_pipe[1],RLIMIT_NOFILE,nofile,nofile);
        execv(argv[10],argv+10);fail(status_pipe[1],"execve");
    }
    close(status_pipe[1]);setpgid(child,child);int status=0,timed_out=0;struct rusage usage={0};
    while(1){pid_t done=wait4(child,&status,WNOHANG,&usage);if(done==child)break;if(done<0&&errno!=EINTR){close(result);return 125;}
        if(stopping||millis()-start>=wall){timed_out=!stopping;kill(-child,SIGKILL);kill(child,SIGKILL);while(wait4(child,&status,0,&usage)<0&&errno==EINTR){}break;}
        struct timespec pause={0,1000000};nanosleep(&pause,NULL);
    }
    kill(-child,SIGKILL);reap_uid(uid);
    while(waitpid(-1,NULL,0)>0||errno==EINTR){}
    char setup[512]={0};ssize_t count=read(status_pipe[0],setup,sizeof(setup)-1);close(status_pipe[0]);
    if(count>0){dprintf(STDERR_FILENO,"sandbox setup: %s\n",setup);close(result);return 125;}
    double cpu_ms=usage.ru_utime.tv_sec*1000.0+usage.ru_utime.tv_usec/1000.0+usage.ru_stime.tv_sec*1000.0+usage.ru_stime.tv_usec/1000.0;
    dprintf(result,"{\"exit\":%d,\"signal\":%d,\"cpu_ms\":%.3f,\"rss_kb\":%ld,\"wall_timeout\":%s}\n",WIFEXITED(status)?WEXITSTATUS(status):-1,WIFSIGNALED(status)?WTERMSIG(status):0,cpu_ms,usage.ru_maxrss,timed_out?"true":"false");close(result);
    return stopping?125:0;
}
