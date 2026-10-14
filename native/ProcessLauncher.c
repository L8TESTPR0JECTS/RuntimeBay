#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static volatile sig_atomic_t child_pid = 0;

/* Signal only the session this supervisor created, never processes found by name. */
static void forward_signal(int value) {
    if (child_pid > 1) kill(-(pid_t)child_pid, value);
}
static void *watch_owner(void *unused) {
    (void)unused;
    char byte;
    ssize_t result;
    do { result = read(STDIN_FILENO, &byte, 1); } while (result > 0 || (result < 0 && errno == EINTR));
    forward_signal(SIGTERM);
    sleep(2);
    forward_signal(SIGKILL);
    return NULL;
}
static pid_t parse_pid(const char *value) {
    char *end;
    long pid = strtol(value, &end, 10);
    if (*end || pid <= 1 || pid > 2147483647) return -1;
    return (pid_t)pid;
}
int main(int argc, char **argv) {
    if (argc == 4 && !strcmp(argv[1], "--owns-pid")) {
        pid_t group = parse_pid(argv[2]), pid = parse_pid(argv[3]);
        return group > 1 && pid > 1 && getpgid(pid) == group ? 0 : 1;
    }
    if (argc == 4 && !strcmp(argv[1], "--signal-group")) {
        pid_t group = parse_pid(argv[2]);
        if (group <= 1) return 64;
        int result = kill(-group, !strcmp(argv[3], "KILL") ? SIGKILL : SIGTERM);
        return result == 0 || errno == ESRCH ? 0 : 1;
    }
    if (argc != 2) { fputs("Expected one shell command\n", stderr); return 64; }
    int gate[2], ready[2];
    if (pipe(gate) || pipe(ready)) return 71;
    pid_t pid = fork();
    if (pid < 0) return 71;
    if (pid == 0) {
        close(gate[1]); close(ready[0]);
        if (setsid() < 0) _exit(71);
        char byte = '1';
        if (write(ready[1], &byte, 1) != 1) _exit(71);
        close(ready[1]);
        if (read(gate[0], &byte, 1) != 1) _exit(71);
        close(gate[0]);
        int input = open("/dev/null", O_RDONLY);
        if (input < 0 || dup2(input, STDIN_FILENO) < 0) _exit(71);
        close(input);
        char *args[] = {"/bin/zsh", "-lc", argv[1], NULL};
        execve("/bin/zsh", args, environ);
        _exit(127);
    }
    child_pid = pid;
    close(gate[0]); close(ready[1]);
    char byte;
    if (read(ready[0], &byte, 1) != 1) { kill(pid, SIGKILL); return 71; }
    close(ready[0]);
    struct sigaction action = {0};
    action.sa_handler = forward_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    printf("RUNTIME_BAY_CHILD:%d\n", pid);
    fflush(stdout);
    if (write(gate[1], &byte, 1) != 1) { forward_signal(SIGKILL); return 71; }
    close(gate[1]);
    pthread_t watcher;
    if (pthread_create(&watcher, NULL, watch_owner, NULL)) { forward_signal(SIGKILL); return 71; }
    pthread_detach(watcher);
    int status;
    while (waitpid(pid, &status, 0) < 0) { if (errno != EINTR) return 71; }
    /* Shells can exit while background grandchildren still occupy their session. */
    forward_signal(SIGTERM);
    usleep(150000);
    forward_signal(SIGKILL);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
