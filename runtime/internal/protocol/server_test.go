package protocol

import (
	"bufio"
	"bytes"
	"dev.runtimebay/worker/internal/supervisor"
	"encoding/json"
	"io"
	"strings"
	"testing"
)

func TestGuardsAndHandshake(t *testing.T) {
	cases := []struct{ name, input, code string }{
		{"version", `{"version":2,"id":"a","targetId":"local","operation":"handshake"}`, "incompatible_version"},
		{"target", `{"version":1,"id":"a","targetId":"other","operation":"handshake"}`, "target_mismatch"},
		{"capability", `{"version":1,"id":"a","targetId":"local","operation":"handshake","requires":["persistent.v1"]}`, "unsupported_capability"},
		{"handshake", `{"version":1,"id":"a","targetId":"local","operation":"process.start"}`, "handshake_required"},
		{"fields", `{"version":1,"id":"a","targetId":"local","operation":"handshake","password":"secret"}`, "invalid_request"},
		{"trailing", `{"version":1,"id":"a","targetId":"local","operation":"handshake"} {}`, "invalid_request"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var out bytes.Buffer
			if err := Serve(strings.NewReader(tc.input+"\n"), &out, "local"); err != nil {
				t.Fatal(err)
			}
			var response Response
			if err := json.Unmarshal(out.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			if response.Error == nil || response.Error.Code != tc.code {
				t.Fatalf("response: %s", out.String())
			}
		})
	}
}
func TestEOFClosesSessionAndRequiresFreshSession(t *testing.T) {
	input, send := io.Pipe()
	receive, output := io.Pipe()
	done := make(chan error, 1)
	go func() { done <- Serve(input, output, "local"); output.Close() }()
	scanner := bufio.NewScanner(receive)
	exchange := func(req Request) Response {
		t.Helper()
		data, _ := json.Marshal(req)
		if _, err := send.Write(append(data, '\n')); err != nil {
			t.Fatal(err)
		}
		if !scanner.Scan() {
			t.Fatal("missing response")
		}
		var response Response
		if err := json.Unmarshal(scanner.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		return response
	}
	base := Request{Version: Version, ID: "request", TargetID: "local", Operation: "handshake"}
	hello := exchange(base)
	if hello.Error != nil || hello.SessionID == "" {
		t.Fatal(hello)
	}
	duplicate := exchange(base)
	if duplicate.Error == nil || duplicate.Error.Code != "duplicate_operation" {
		t.Fatal(duplicate)
	}
	base.ID = "owned-start"
	base.SessionID = hello.SessionID
	base.Operation = "process.start"
	base.Start = &supervisor.Start{Executable: "/bin/sleep", Args: []string{"30"}, Directory: t.TempDir()}
	started := exchange(base)
	if started.Error != nil {
		t.Fatal(started)
	}
	base.Start = nil
	base.ID = "stale-request"
	base.Operation = "process.observe"
	base.SessionID = "stale-session"
	base.Handle = "123"
	rejected := exchange(base)
	if rejected.Error == nil || rejected.Error.Code != "session_mismatch" {
		t.Fatal(rejected)
	}
	base.ID = "fresh-request"
	base.SessionID = hello.SessionID
	rejected = exchange(base)
	if rejected.Error == nil || rejected.Error.Code != "operation_failed" {
		t.Fatal(rejected)
	}
	send.Close()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
func TestOversizeRequestTerminatesStream(t *testing.T) {
	var output bytes.Buffer
	err := Serve(strings.NewReader(strings.Repeat("x", MaxRequestBytes+1)), &output, "local")
	if err == nil || output.Len() != 0 {
		t.Fatal("oversized request was accepted")
	}
}
