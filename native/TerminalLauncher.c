/* Runtime Bay PTY supervisor. stdin: I + uint32 length + bytes, R + length(4) + two uint16 dimensions. */
#include <util.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/sysctl.h>
#include <sys/wait.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
static volatile sig_atomic_t stopping;
static void stop(int signal) { (void)signal; stopping = 1; }
static int read_all(int fd, void *buffer, size_t length) {
    char *p = buffer;
    while (length) { ssize_t n = read(fd, p, length); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; length -= n; }
    return 0;
}
static int write_all(int fd, const void *buffer, size_t length) {
    const char *p = buffer;
    while (length) { ssize_t n = write(fd, p, length); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; length -= n; }
    return 0;
}
/* Interactive jobs have separate process groups; clean the entire owned session. */
static void clean_session(pid_t session, int signal) {
    int mib[] = {CTL_KERN, KERN_PROC, KERN_PROC_ALL, 0}; size_t length = 0;
    if (sysctl(mib, 4, NULL, &length, NULL, 0)) return;
    length += 32 * sizeof(struct kinfo_proc);
    struct kinfo_proc *processes = malloc(length);
    if (!processes) return;
    if (!sysctl(mib, 4, processes, &length, NULL, 0)) {
        for (size_t i = 0; i < length / sizeof(*processes); i++) {
            pid_t pid = processes[i].kp_proc.p_pid;
            if (pid > 1 && getsid(pid) == session) kill(pid, signal);
        }
    }
    free(processes);
}
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, stop); signal(SIGINT, stop); signal(SIGHUP, stop);
    int master; struct winsize size = {.ws_row = 24, .ws_col = 80};
    pid_t child = forkpty(&master, NULL, NULL, &size);
    if (child < 0) return 71;
    if (!child) {
        signal(SIGPIPE, SIG_DFL); signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL); signal(SIGHUP, SIG_DFL);
        if (argc > 1) execvp(argv[1], &argv[1]);
        else execl("/bin/zsh", "zsh", "-il", (char *)NULL);
        _exit(127);
    }
    fcntl(master, F_SETFL, O_NONBLOCK);
    unsigned char pending[65536]; size_t pending_count = 0;
    int status = 0, reaped = 0;
    while (!stopping) {
        struct pollfd fds[] = {{STDIN_FILENO, pending_count <= sizeof(pending) - 16384 ? POLLIN : 0, 0}, {master, POLLIN | (pending_count ? POLLOUT : 0), 0}};
        int result = poll(fds, 2, 100);
        if (result < 0) { if (errno == EINTR) continue; break; }
        if (fds[0].revents & (POLLHUP | POLLERR)) break;
        if (fds[0].revents & POLLIN) {
            unsigned char header[5], data[16384];
            if (read_all(0, header, 5)) break;
            uint32_t length = ((uint32_t)header[1]<<24) | ((uint32_t)header[2]<<16) | ((uint32_t)header[3]<<8) | header[4];
            if (length > sizeof(data) || read_all(0, data, length)) break;
            if (header[0] == 'I') { memcpy(pending + pending_count, data, length); pending_count += length; }
            else if (header[0] == 'R' && length == 4) {
                size.ws_col = ((unsigned)data[0]<<8) | data[1]; size.ws_row = ((unsigned)data[2]<<8) | data[3];
                if (size.ws_col && size.ws_row) ioctl(master, TIOCSWINSZ, &size);
            } else break;
        }
        if (fds[1].revents & POLLOUT) {
            ssize_t n = write(master, pending, pending_count);
            if (n > 0) { pending_count -= n; memmove(pending, pending + n, pending_count); }
            else if (n < 0 && errno != EAGAIN && errno != EINTR) break;
        }
        if (fds[1].revents & POLLIN) {
            unsigned char output[16384]; ssize_t n = read(master, output, sizeof(output));
            if (n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (n <= 0 || write_all(1, output, (size_t)n)) break;
        } else if (fds[1].revents & (POLLHUP | POLLERR)) break;
        if (!reaped && waitpid(child, &status, WNOHANG) == child) { reaped = 1; stopping = 1; }
    }
    unsigned char tail[16384]; ssize_t count;
    while ((count = read(master, tail, sizeof(tail))) > 0) if (write_all(1, tail, (size_t)count)) break;
    clean_session(child, SIGTERM); usleep(150000); clean_session(child, SIGKILL);
    close(master);
    if (!reaped) while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
