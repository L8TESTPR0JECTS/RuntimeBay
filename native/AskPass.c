/* Reads an SSH password through a private app-owned socket; no credential files. */
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <signal.h>
#include <errno.h>
static void wipe(void *buffer, size_t length) { volatile unsigned char *p = buffer; while (length--) *p++ = 0; }
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    /* Never use the machine password for private-key passphrases or host verification. */
    if (argc != 2 || !strcasestr(argv[1], "password") || strcasestr(argv[1], "passphrase")) return 1;
    const char *path = getenv("RUNTIMEBAY_SSH_SOCKET"), *token = getenv("RUNTIMEBAY_SSH_TOKEN");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (!path || strlen(path) >= sizeof(address.sun_path) || !token || strlen(token) != 64) return 1;
    memcpy(address.sun_path, path, strlen(path) + 1);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return 1;
    struct timeval timeout = {.tv_sec = 5};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    if (connect(fd, (struct sockaddr *)&address, sizeof(address))) { close(fd); return 1; }
    size_t sent = 0;
    while (sent < 64) { ssize_t n = write(fd, token + sent, 64 - sent); if (n < 0 && errno == EINTR) continue; if (n <= 0) { close(fd); return 1; } sent += (size_t)n; }
    char password[16385]; size_t length = 0; ssize_t n;
    while (length < sizeof(password) && (n = read(fd, password + length, sizeof(password) - length)) > 0) length += (size_t)n;
    close(fd);
    if (!length || length == sizeof(password)) { wipe(password, sizeof(password)); return 1; }
    int failed = fwrite(password, 1, length, stdout) != length || fputc('\n', stdout) == EOF;
    wipe(password, sizeof(password));
    return failed ? 1 : 0;
}
