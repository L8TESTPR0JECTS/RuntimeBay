//go:build darwin || linux

// Package supervisor is an experimental, session-scoped foreground process owner.
package supervisor

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

const MaxProcesses = 16
const MaxLogBytes = 32 * 1024

var ErrUnknownProcess = errors.New("process handle is not owned by this worker session")

type Event struct {
	Sequence uint64 `json:"sequence"`
	Stream   string `json:"stream"`
	Text     string `json:"text"`
}
type Snapshot struct {
	Handle    string  `json:"handle"`
	State     string  `json:"state"`
	ExitCode  *int    `json:"exitCode,omitempty"`
	Events    []Event `json:"events"`
	Truncated bool    `json:"truncated"`
}
type Start struct {
	Executable string   `json:"executable"`
	Args       []string `json:"args"`
	Directory  string   `json:"directory"`
}
type process struct {
	mu        sync.Mutex
	cmd       *exec.Cmd
	handle    string
	done      chan struct{}
	state     string
	exitCode  *int
	events    []Event
	bytes     int
	sequence  uint64
	truncated bool
}
type Manager struct {
	mu        sync.Mutex
	processes map[string]*process
	closed    bool
}

func New() *Manager { return &Manager{processes: make(map[string]*process)} }
func Token() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}
func (m *Manager) Start(s Start) (Snapshot, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return Snapshot{}, errors.New("worker is shutting down")
	}
	// Completed handles remain available until shutdown; sessions have a fixed bound.
	if len(m.processes) >= MaxProcesses {
		return Snapshot{}, errors.New("session process limit reached")
	}
	if !filepath.IsAbs(s.Executable) || !filepath.IsAbs(s.Directory) {
		return Snapshot{}, errors.New("executable and directory must be absolute paths")
	}
	dir, err := filepath.EvalSymlinks(s.Directory)
	if err != nil {
		return Snapshot{}, errors.New("working directory is unavailable")
	}
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return Snapshot{}, errors.New("working directory must be a directory")
	}
	handle, err := Token()
	if err != nil {
		return Snapshot{}, err
	}
	cmd := exec.Command(s.Executable, s.Args...)
	cmd.Dir = dir
	cmd.WaitDelay = 200 * time.Millisecond
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	// No protocol/session secrets or Java bootstrap credentials are inherited.
	cmd.Env = []string{"PATH=/usr/bin:/bin:/usr/sbin:/sbin", "LANG=C.UTF-8"}
	p := &process{cmd: cmd, handle: handle, done: make(chan struct{}), state: "running", events: []Event{}}
	cmd.Stdout = logWriter{p, "stdout"}
	cmd.Stderr = logWriter{p, "stderr"}
	if err := cmd.Start(); err != nil {
		return Snapshot{}, errors.New("executable could not be started")
	}
	m.processes[handle] = p
	go func() {
		err := cmd.Wait()
		// Foreground programs must not leave children behind when their leader exits.
		// This experimental group policy still needs platform PID-reuse validation.
		_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		code := 0
		if err != nil {
			code = cmd.ProcessState.ExitCode()
		}
		p.mu.Lock()
		p.state = "exited"
		p.exitCode = &code
		p.mu.Unlock()
		close(p.done)
	}()
	return p.snapshot(), nil
}
func (m *Manager) lookup(handle string) (*process, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	p, ok := m.processes[handle]
	if !ok {
		return nil, ErrUnknownProcess
	}
	return p, nil
}
func (m *Manager) Observe(handle string) (Snapshot, error) {
	p, err := m.lookup(handle)
	if err != nil {
		return Snapshot{}, err
	}
	return p.snapshot(), nil
}
func (m *Manager) Stop(handle string) (Snapshot, error) {
	p, err := m.lookup(handle)
	if err != nil {
		return Snapshot{}, err
	}
	if err := p.stop(); err != nil {
		return Snapshot{}, err
	}
	return p.snapshot(), nil
}
func (m *Manager) Close() error {
	m.mu.Lock()
	m.closed = true
	processes := make([]*process, 0, len(m.processes))
	for _, p := range m.processes {
		processes = append(processes, p)
	}
	m.mu.Unlock()
	var wg sync.WaitGroup
	errs := make(chan error, len(processes))
	for _, p := range processes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := p.stop(); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	var result error
	for err := range errs {
		result = errors.Join(result, err)
	}
	return result
}
func (p *process) stop() error {
	select {
	case <-p.done:
		return nil
	default:
	}
	if err := syscall.Kill(-p.cmd.Process.Pid, syscall.SIGTERM); err != nil && err != syscall.ESRCH {
		return fmt.Errorf("terminate owned group: %w", err)
	}
	timer := time.NewTimer(300 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-p.done:
		return nil
	case <-timer.C:
	}
	if err := syscall.Kill(-p.cmd.Process.Pid, syscall.SIGKILL); err != nil && err != syscall.ESRCH {
		return fmt.Errorf("kill owned group: %w", err)
	}
	select {
	case <-p.done:
		return nil
	case <-time.After(2 * time.Second):
		return errors.New("owned process cleanup timed out")
	}
}
func (p *process) snapshot() Snapshot {
	p.mu.Lock()
	defer p.mu.Unlock()
	events := append([]Event{}, p.events...)
	return Snapshot{p.handle, p.state, p.exitCode, events, p.truncated}
}

type logWriter struct {
	p      *process
	stream string
}

func (w logWriter) Write(b []byte) (int, error) {
	n := len(b)
	p := w.p
	p.mu.Lock()
	defer p.mu.Unlock()
	// Byte-to-string JSON conversion replaces invalid UTF-8 on serialization.
	if len(b) > MaxLogBytes {
		b = b[len(b)-MaxLogBytes:]
		p.truncated = true
	}
	p.sequence++
	p.events = append(p.events, Event{p.sequence, w.stream, string(b)})
	p.bytes += len(b)
	for p.bytes > MaxLogBytes || len(p.events) > 256 {
		p.bytes -= len(p.events[0].Text)
		p.events = p.events[1:]
		p.truncated = true
	}
	return n, nil
}
