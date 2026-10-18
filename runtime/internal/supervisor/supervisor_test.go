//go:build darwin || linux

package supervisor

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func awaitExit(t *testing.T, m *Manager, handle string) Snapshot {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		s, err := m.Observe(handle)
		if err != nil {
			t.Fatal(err)
		}
		if s.State == "exited" {
			return s
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("process did not exit")
	return Snapshot{}
}
func TestLifecycleAndCapturedStreams(t *testing.T) {
	m := New()
	defer m.Close()
	s, err := m.Start(Start{Executable: "/bin/sh", Args: []string{"-c", "printf hello; printf error >&2; exit 7"}, Directory: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	s = awaitExit(t, m, s.Handle)
	if s.ExitCode == nil || *s.ExitCode != 7 {
		t.Fatalf("exit: %+v", s)
	}
	streams := map[string]string{}
	for _, event := range s.Events {
		streams[event.Stream] += event.Text
	}
	if streams["stdout"] != "hello" || streams["stderr"] != "error" {
		t.Fatalf("streams: %v", streams)
	}
	if _, err = m.Stop(s.Handle); err != nil {
		t.Fatal(err)
	}
}
func TestStopAndSessionIsolation(t *testing.T) {
	m := New()
	defer m.Close()
	other := New()
	defer other.Close()
	s, err := m.Start(Start{Executable: "/bin/sleep", Args: []string{"30"}, Directory: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = other.Stop(s.Handle); !errors.Is(err, ErrUnknownProcess) {
		t.Fatalf("foreign handle accepted: %v", err)
	}
	if _, err = m.Stop("1"); !errors.Is(err, ErrUnknownProcess) {
		t.Fatalf("PID accepted: %v", err)
	}
	s, err = m.Stop(s.Handle)
	if err != nil || s.State != "exited" {
		t.Fatalf("stop: %+v %v", s, err)
	}
}
func TestCloseTerminatesChildrenAndRejectsFurtherStart(t *testing.T) {
	m := New()
	s, err := m.Start(Start{Executable: "/bin/sh", Args: []string{"-c", "sleep 30 & wait"}, Directory: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if err = m.Close(); err != nil {
		t.Fatal(err)
	}
	if got := awaitExit(t, m, s.Handle); got.State != "exited" {
		t.Fatal(got)
	}
	if _, err = m.Start(Start{Executable: "/bin/true", Directory: t.TempDir()}); err == nil {
		t.Fatal("start after close accepted")
	}
}
func TestOutputBoundAndTruncation(t *testing.T) {
	p := &process{events: []Event{}}
	writer := logWriter{p, "stdout"}
	_, _ = writer.Write([]byte(strings.Repeat("x", MaxLogBytes*3)))
	for i := 0; i < 300; i++ {
		_, _ = writer.Write([]byte("y"))
	}
	s := p.snapshot()
	total := 0
	for _, event := range s.Events {
		total += len(event.Text)
	}
	if !s.Truncated || total > MaxLogBytes || len(s.Events) > 256 {
		t.Fatalf("unbounded snapshot: %d bytes %d entries", total, len(s.Events))
	}
}
func TestRejectRelativePaths(t *testing.T) {
	m := New()
	defer m.Close()
	if _, err := m.Start(Start{Executable: "sleep", Directory: t.TempDir()}); err == nil {
		t.Fatal("relative executable accepted")
	}
	if _, err := m.Start(Start{Executable: "/bin/true", Directory: "."}); err == nil {
		t.Fatal("relative directory accepted")
	}
}

// The child PID is observed only in a test fixture; the worker API never accepts it.
func TestCloseStopsForegroundDescendant(t *testing.T) {
	m := New()
	defer m.Close()
	dir := t.TempDir()
	pidfile := filepath.Join(dir, "child.pid")
	_, err := m.Start(Start{Executable: "/bin/sh", Args: []string{"-c", "sleep 30 & echo $! > child.pid; wait"}, Directory: dir})
	if err != nil {
		t.Fatal(err)
	}
	var pid int
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		data, readErr := os.ReadFile(pidfile)
		if readErr == nil {
			pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
			if pid > 0 {
				break
			}
		}
		time.Sleep(5 * time.Millisecond)
	}
	if pid == 0 {
		t.Fatal("fixture did not report child")
	}
	if err = m.Close(); err != nil {
		t.Fatal(err)
	}
	// A zombie is terminated but may remain until the host's reaper collects it.
	output, psErr := exec.Command("/bin/ps", "-o", "stat=", "-p", strconv.Itoa(pid)).Output()
	if psErr == nil && strings.TrimSpace(string(output)) != "" && !strings.HasPrefix(strings.TrimSpace(string(output)), "Z") {
		t.Fatalf("owned foreground descendant is still live: %s", output)
	}
}
